package io.aura.db;

import java.util.ArrayList;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies ordered migrations while retaining applied checksums for drift detection. */
final class SqlMigrationRunner {
    private static final String TABLE = "_migrations";
    private static final String LOCK_TABLE = "_migration_lock";
    private final Db db;

    SqlMigrationRunner(Db db) {
        this.db = db;
    }

    SqlMigrationResult run(SqlMigrationSource source) {
        if (source == null) throw new IllegalArgumentException("SQL migration source must not be null");
        if (db.currentTransactionContext() != null) {
            throw new IllegalStateException("Migrations must run outside an active transaction");
        }
        List<SqlMigration> supplied = source.migrations();
        if (supplied == null) throw new MigrationException("SQL migration source returned null");
        List<SqlMigration> migrations = new ArrayList<>(supplied);
        migrations.sort((left, right) -> compareVersions(left.version(), right.version()));
        ensureUniqueVersions(migrations);
        acquireLockRow();
        return db.transaction(() -> {
            // Serializes migration across nodes sharing the database. A second node blocks here
            // until the first one commits, then observes its rows as already applied.
            if (db.find("SELECT id FROM " + LOCK_TABLE + " WHERE id = 1 FOR UPDATE").isEmpty()) {
                throw new MigrationException("Migration lock row is missing");
            }
            db.execute("CREATE TABLE IF NOT EXISTS " + TABLE +
                    " (version VARCHAR(255) PRIMARY KEY, name VARCHAR(255) NOT NULL, checksum VARCHAR(128) NOT NULL)");

            Map<String, String> applied = new HashMap<>();
            for (Row row : db.find("SELECT version, checksum FROM " + TABLE)) {
                applied.put(row.getStr("version"), row.getStr("checksum"));
            }
            List<String> appliedNow = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (SqlMigration migration : migrations) {
                String appliedChecksum = applied.get(migration.version());
                if (appliedChecksum != null) {
                    if (!appliedChecksum.equals(migration.checksum())) {
                        throw new MigrationException("Checksum mismatch for migration " + migration.version());
                    }
                    skipped.add(migration.version());
                    continue;
                }
                try {
                    db.transaction(() -> {
                        for (String statement : splitStatements(migration.sql())) {
                            db.execute(statement);
                        }
                        db.execute("INSERT INTO " + TABLE + " (version, name, checksum) VALUES (?, ?, ?)",
                                migration.version(), migration.name(), migration.checksum());
                    });
                    appliedNow.add(migration.version());
                    applied.put(migration.version(), migration.checksum());
                } catch (RuntimeException e) {
                    throw new MigrationException("Failed migration " + migration.version() + " (" + migration.name() + ")", e);
                }
            }
            return new SqlMigrationResult(appliedNow, skipped);
        });
    }

    /**
     * Creates the single lock row outside a transaction: a duplicate-key race is harmless, and
     * failing inside a transaction would poison it on databases that abort after any error.
     */
    private void acquireLockRow() {
        db.execute("CREATE TABLE IF NOT EXISTS " + LOCK_TABLE + " (id INT PRIMARY KEY)");
        try {
            db.execute("INSERT INTO " + LOCK_TABLE + " (id) VALUES (1)");
        } catch (RuntimeException alreadyPresent) {
            // Another node created or locked the row first; the FOR UPDATE above is the guard.
        }
    }

    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false, dbl = false, lineComment = false, blockComment = false;
        String dollarTag = null;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) { if (c == '\n') { lineComment = false; current.append(c); } continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; current.append(' '); i++; } continue; }
            if (dollarTag != null) {
                if (sql.startsWith(dollarTag, i)) {
                    current.append(dollarTag);
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (!single && !dbl && c == '$') {
                String tag = dollarTagAt(sql, i);
                if (tag != null) {
                    dollarTag = tag;
                    current.append(tag);
                    i += tag.length() - 1;
                    continue;
                }
            }
            if (!single && !dbl && c == '-' && next == '-') { lineComment = true; i++; continue; }
            if (!single && !dbl && c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '\'' && !dbl) {
                if (single && next == '\'') { current.append(c).append(next); i++; continue; }
                single = !single;
            } else if (c == '"' && !single) {
                if (dbl && next == '"') { current.append(c).append(next); i++; continue; }
                dbl = !dbl;
            }
            if (c == ';' && !single && !dbl) {
                addStatement(statements, current);
            } else {
                current.append(c);
            }
        }
        addStatement(statements, current);
        return statements;
    }

    /** Returns the full {@code $tag$} delimiter starting at {@code start}, or null. */
    private static String dollarTagAt(String sql, int start) {
        int closing = sql.indexOf('$', start + 1);
        if (closing < 0) return null;
        String body = sql.substring(start + 1, closing);
        if (!body.isEmpty() && !body.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
        return sql.substring(start, closing + 1);
    }

    static int compareVersions(String a, String b) {
        if (a.equals(b)) return 0;
        String[] coreA = core(a);
        String[] coreB = core(b);
        int length = Math.max(coreA.length, coreB.length);
        for (int i = 0; i < length; i++) {
            int compared = compareSegment(
                    i < coreA.length ? coreA[i] : "0",
                    i < coreB.length ? coreB[i] : "0");
            if (compared != 0) return compared;
        }
        String preA = prerelease(a);
        String preB = prerelease(b);
        if (preA == null && preB == null) return 0;
        if (preA == null) return 1;
        if (preB == null) return -1;
        String[] partsA = preA.split("\\.");
        String[] partsB = preB.split("\\.");
        int preLength = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < preLength; i++) {
            if (i >= partsA.length) return -1;
            if (i >= partsB.length) return 1;
            int compared = comparePrerelease(partsA[i], partsB[i]);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static String[] core(String version) {
        String value = version;
        int cut = value.indexOf('-');
        if (cut >= 0) value = value.substring(0, cut);
        cut = value.indexOf('+');
        if (cut >= 0) value = value.substring(0, cut);
        return value.split("\\.");
    }

    private static String prerelease(String version) {
        int start = version.indexOf('-');
        if (start < 0) return null;
        String value = version.substring(start + 1);
        int build = value.indexOf('+');
        return build >= 0 ? value.substring(0, build) : value;
    }

    private static int compareSegment(String left, String right) {
        if (isNumeric(left) && isNumeric(right)) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        return left.compareTo(right);
    }

    private static int comparePrerelease(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) return new BigInteger(left).compareTo(new BigInteger(right));
        if (leftNumeric) return -1;
        if (rightNumeric) return 1;
        return left.compareTo(right);
    }

    private static boolean isNumeric(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    private static void addStatement(List<String> statements, StringBuilder statement) {
        String value = statement.toString().trim();
        if (!value.isEmpty()) statements.add(value);
        statement.setLength(0);
    }

    private static void ensureUniqueVersions(List<SqlMigration> migrations) {
        Set<String> versions = new HashSet<>();
        for (SqlMigration migration : migrations) {
            if (!versions.add(migration.version())) {
                throw new MigrationException("Duplicate migration version: " + migration.version());
            }
        }
    }
}

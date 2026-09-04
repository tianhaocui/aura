package io.aura.db;

import java.util.ArrayList;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies ordered migrations while retaining applied checksums for drift detection. */
public final class MigrationRunner {
    private static final String TABLE = "_migrations";
    private final Db db;

    public MigrationRunner(Db db) {
        this.db = db;
    }

    public MigrationResult run(MigrationSource source) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (db.currentTransactionContext() != null) {
            throw new IllegalStateException("Migrations must run outside an active transaction");
        }
        List<Migration> supplied = source.migrations();
        if (supplied == null) throw new MigrationException("Migration source returned null");
        List<Migration> migrations = new ArrayList<>(supplied);
        migrations.sort(MigrationRunner::compareVersions);
        ensureUniqueVersions(migrations);
        db.execute("CREATE TABLE IF NOT EXISTS " + TABLE +
                " (version VARCHAR(255) PRIMARY KEY, name VARCHAR(255) NOT NULL, checksum VARCHAR(128) NOT NULL)");

        Map<String, String> applied = new HashMap<>();
        for (Row row : db.find("SELECT version, checksum FROM " + TABLE)) {
            applied.put(row.getStr("version"), row.getStr("checksum"));
        }
        List<String> appliedNow = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Migration migration : migrations) {
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
        return new MigrationResult(appliedNow, skipped);
    }

    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false, dbl = false, lineComment = false, blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) { if (c == '\n') { lineComment = false; current.append(c); } continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; current.append(' '); i++; } continue; }
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

    private static int compareVersions(Migration left, Migration right) {
        String a = left.version(), b = right.version();
        if (a.chars().allMatch(Character::isDigit) && b.chars().allMatch(Character::isDigit)) {
            int numeric = new BigInteger(a).compareTo(new BigInteger(b));
            if (numeric != 0) return numeric;
        }
        return a.compareTo(b);
    }

    private static void addStatement(List<String> statements, StringBuilder statement) {
        String value = statement.toString().trim();
        if (!value.isEmpty()) statements.add(value);
        statement.setLength(0);
    }

    private static void ensureUniqueVersions(List<Migration> migrations) {
        Set<String> versions = new HashSet<>();
        for (Migration migration : migrations) {
            if (!versions.add(migration.version())) {
                throw new MigrationException("Duplicate migration version: " + migration.version());
            }
        }
    }
}

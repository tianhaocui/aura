package io.aura.db.liquibase;

import io.aura.db.MigrationContext;
import io.aura.db.SchemaMigrationResult;
import io.aura.db.SchemaMigrator;
import liquibase.Scope;
import liquibase.UpdateSummaryEnum;
import liquibase.changelog.visitor.DefaultChangeExecListener;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.ChangeExecListenerCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.command.core.helpers.ShowSummaryArgument;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Runs a Liquibase classpath changelog during {@code DatabaseModule} startup. */
public final class LiquibaseSchemaMigrator implements SchemaMigrator {
    public static final String ID = "liquibase";
    private static final int MAX_BOOTSTRAP_RETRIES = 3;
    private static final long[] RETRY_BACKOFF_MILLIS = {25, 50, 100};

    private final String changelogFile;
    private final ClassLoader classLoader;

    private LiquibaseSchemaMigrator(String changelogFile, ClassLoader classLoader) {
        this.changelogFile = requireChangelogFile(changelogFile);
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /** Uses a changelog available from the current thread context class loader. */
    public static LiquibaseSchemaMigrator classpath(String changelogFile) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = LiquibaseSchemaMigrator.class.getClassLoader();
        return new LiquibaseSchemaMigrator(changelogFile, loader);
    }

    /** Uses a changelog from an explicit class loader, primarily for isolated modules and tests. */
    public static LiquibaseSchemaMigrator classpath(String changelogFile, ClassLoader classLoader) {
        return new LiquibaseSchemaMigrator(changelogFile, classLoader);
    }

    @Override public SchemaMigrationResult migrate(MigrationContext context) throws Exception {
        Objects.requireNonNull(context, "context");
        for (int attempt = 0; ; attempt++) {
            try {
                return migrateOnce(context);
            } catch (ConcurrentHistoryBootstrapException collision) {
                if (attempt >= MAX_BOOTSTRAP_RETRIES) {
                    throw collision.getCause();
                }
                try {
                    Thread.sleep(RETRY_BACKOFF_MILLIS[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    interrupted.addSuppressed(collision.getCause());
                    throw interrupted;
                }
            }
        }
    }

    private SchemaMigrationResult migrateOnce(MigrationContext context) throws Exception {
        try (Connection connection = context.dataSource().getConnection();
             ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor(classLoader)) {
            boolean historyBootstrapNeeded = !hasTable(connection, "DATABASECHANGELOG")
                    || !hasTable(connection, "DATABASECHANGELOGLOCK");
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try {
                try {
                    int applied = runUpdate(database, resources);
                    return new SchemaMigrationResult(ID, applied);
                } catch (Exception failure) {
                    if (historyBootstrapNeeded && isConcurrentHistoryBootstrap(failure)) {
                        throw new ConcurrentHistoryBootstrapException(failure);
                    }
                    throw failure;
                }
            } finally {
                database.close();
            }
        }
    }

    private static boolean hasTable(Connection connection, String expectedName) throws SQLException {
        try (var tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                String actual = tables.getString("TABLE_NAME");
                if (expectedName.equalsIgnoreCase(actual)) return true;
            }
            return false;
        }
    }

    private static final class ConcurrentHistoryBootstrapException extends Exception {
        private ConcurrentHistoryBootstrapException(Exception cause) {
            super(cause);
        }

        @Override public synchronized Exception getCause() {
            return (Exception) super.getCause();
        }
    }

    static boolean isConcurrentHistoryBootstrap(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (!(current instanceof SQLException sqlFailure)) continue;
            String state = sqlFailure.getSQLState();
            int vendorCode = sqlFailure.getErrorCode();
            String message = sqlFailure.getMessage();
            if (message == null) continue;
            String normalized = message.toUpperCase(java.util.Locale.ROOT);
            boolean historyTable = java.util.regex.Pattern.compile(
                    "(?<![A-Z0-9_])DATABASECHANGELOG(?:LOCK)?(?![A-Z0-9_])")
                    .matcher(normalized).find();
            boolean duplicateObject = "42101".equals(state) // H2 table already exists
                    || "42P07".equals(state) // PostgreSQL duplicate_table
                    || "42S01".equals(state) // MySQL table already exists
                    || "S0001".equals(state) // SQL Server object already exists
                    || vendorCode == 1050 // MySQL ER_TABLE_EXISTS_ERROR
                    || vendorCode == 2714 // SQL Server object already exists
                    || vendorCode == 955; // Oracle name is already used
            if (historyTable && duplicateObject) return true;
        }
        return false;
    }

    private int runUpdate(Database database, ClassLoaderResourceAccessor resources) throws Exception {
        Map<String, Object> scopeValues = new HashMap<>();
        scopeValues.put(Scope.Attr.database.name(), database);
        scopeValues.put(Scope.Attr.resourceAccessor.name(), resources);
        return Scope.child(scopeValues, () -> {
            DefaultChangeExecListener listener = new DefaultChangeExecListener();
            new CommandScope(UpdateCommandStep.COMMAND_NAME)
                    .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
                    .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, changelogFile)
                    .addArgumentValue(ChangeExecListenerCommandStep.CHANGE_EXEC_LISTENER_ARG, listener)
                    .addArgumentValue(ShowSummaryArgument.SHOW_SUMMARY, UpdateSummaryEnum.OFF)
                    .execute();
            return listener.getDeployedChangeSets().size();
        });
    }

    private static String requireChangelogFile(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Liquibase changelog file must not be blank");
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Liquibase changelog file must not be blank");
        }
        return normalized;
    }
}

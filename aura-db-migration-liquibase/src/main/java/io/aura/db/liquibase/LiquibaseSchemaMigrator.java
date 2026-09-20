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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Runs a Liquibase classpath changelog during {@code DatabaseModule} startup. */
public final class LiquibaseSchemaMigrator implements SchemaMigrator {
    public static final String ID = "liquibase";

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
        try (Connection connection = context.dataSource().getConnection();
             ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor(classLoader)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try {
                int applied = runUpdate(database, resources);
                return new SchemaMigrationResult(ID, applied);
            } finally {
                database.close();
            }
        }
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

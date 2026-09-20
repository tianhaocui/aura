package io.aura.db;

import java.util.Objects;

/** Explicit lightweight SQL migrator; use Flyway/Liquibase adapters for production dialects. */
public final class SqlSchemaMigrator implements SchemaMigrator {
    public static final String ID = "aura-sql";
    private final SqlMigrationSource source;

    private SqlSchemaMigrator(SqlMigrationSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public static SqlSchemaMigrator from(SqlMigrationSource source) {
        return new SqlSchemaMigrator(source);
    }

    public static SqlSchemaMigrator classpath(String location) {
        return from(new ClasspathSqlMigrationSource(location));
    }

    @Override public SchemaMigrationResult migrate(MigrationContext context) {
        Objects.requireNonNull(context, "context");
        SqlMigrationResult result = new SqlMigrationRunner(context.db()).run(source);
        return new SchemaMigrationResult(ID, result.appliedCount());
    }
}

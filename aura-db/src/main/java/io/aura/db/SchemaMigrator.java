package io.aura.db;

/** Executes one schema migration strategy against an Aura-owned database. */
@FunctionalInterface
public interface SchemaMigrator {
    SchemaMigrationResult migrate(MigrationContext context) throws Exception;
}

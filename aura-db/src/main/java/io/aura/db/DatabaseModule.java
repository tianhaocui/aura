package io.aura.db;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;

import java.util.Objects;

/** Explicit Aura module that contributes one named database capability. */
public final class DatabaseModule implements AuraModule {
    private final Db db;
    private final SchemaMigrator migrator;
    private volatile SchemaMigrationResult migrationResult;

    private DatabaseModule(Db db, SchemaMigrator migrator) {
        this.db = Objects.requireNonNull(db, "db");
        this.migrator = migrator;
    }

    public static DatabaseModule create(Db db) {
        return new DatabaseModule(db, null);
    }

    /** Executes exactly one explicit migration strategy during module startup. */
    public DatabaseModule migrateWith(SchemaMigrator value) {
        return new DatabaseModule(db, Objects.requireNonNull(value, "migrator"));
    }

    @Override public ModuleMetadata descriptor() {
        ModuleMetadata metadata = ModuleMetadata.of("db", "1.0.0").provides("db");
        return migrator == null ? metadata : metadata.provides("db.migrations");
    }

    @Override public void install(ModuleContext context) {
        context.register(db);
    }

    @Override public void start(ModuleContext context) {
        if (migrator == null) return;
        if (db.currentTransactionContext() != null) {
            throw new MigrationException("Schema migration cannot start inside a transaction");
        }
        migrationResult = null;
        try {
            SchemaMigrationResult result = migrator.migrate(db.migrationContext());
            if (result == null) throw new MigrationException("Schema migrator returned null");
            migrationResult = result;
        } catch (MigrationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new MigrationException("Schema migrator failed", failure);
        }
    }

    @Override public void stop(ModuleContext context) {
        db.close();
    }

    public java.util.Optional<SchemaMigrationResult> migrationResult() {
        return java.util.Optional.ofNullable(migrationResult);
    }
}

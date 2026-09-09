package io.aura.db;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;

import java.util.Objects;

/** Explicit Aura module that contributes one named database capability. */
public final class DatabaseModule implements AuraModule {
    private final Db db;
    private final String migrationLocation;
    private final MigrationSource migrationSource;

    private DatabaseModule(Db db, String migrationLocation, MigrationSource migrationSource) {
        this.db = Objects.requireNonNull(db, "db");
        this.migrationLocation = migrationLocation;
        this.migrationSource = migrationSource;
    }

    public static DatabaseModule create(Db db) {
        return new DatabaseModule(db, null, null);
    }

    public DatabaseModule migrations(String classpathLocation) {
        if (classpathLocation == null || classpathLocation.isBlank()) {
            throw new IllegalArgumentException("migration location must not be blank");
        }
        return new DatabaseModule(db, classpathLocation, null);
    }

    /** Supplies a custom migration adapter, such as a Flyway/Liquibase bridge. */
    public DatabaseModule migrations(MigrationSource source) {
        return new DatabaseModule(db, null, Objects.requireNonNull(source, "migrationSource"));
    }

    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("db", "1.0.0")
                .provides("db", "db.migrations");
    }

    @Override public void install(ModuleContext context) {
        context.register(db);
    }

    @Override public void start(ModuleContext context) {
        if (migrationSource != null) db.migrate(migrationSource);
        else if (migrationLocation != null) db.migrate(migrationLocation);
    }

    @Override public void stop(ModuleContext context) {
        db.close();
    }
}

package io.aura.db;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.ModuleDescriptor;

import java.util.Objects;

/** Explicit Aura module that contributes one named database capability. */
public final class DatabaseModule implements AuraModule {
    private final Db db;
    private final String migrationLocation;

    private DatabaseModule(Db db, String migrationLocation) {
        this.db = Objects.requireNonNull(db, "db");
        this.migrationLocation = migrationLocation;
    }

    public static DatabaseModule create(Db db) {
        return new DatabaseModule(db, null);
    }

    public DatabaseModule migrations(String classpathLocation) {
        if (classpathLocation == null || classpathLocation.isBlank()) {
            throw new IllegalArgumentException("migration location must not be blank");
        }
        return new DatabaseModule(db, classpathLocation);
    }

    @Override public ModuleDescriptor descriptor() {
        return ModuleDescriptor.of("db", "1.0.0")
                .provides("db", "db.migrations")
                .build();
    }

    @Override public void install(ModuleContext context) {
        context.register(db);
    }

    @Override public void start(ModuleContext context) {
        if (migrationLocation != null) db.migrate(migrationLocation);
    }

    @Override public void stop(ModuleContext context) {
        db.close();
    }
}

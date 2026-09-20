package io.aura.db;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.ModuleException;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseModuleTest {
    @Test
    void databaseModuleContributesDbToDependentModule() {
        Db db = Db.create("jdbc:h2:mem:module;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            AuraApp app = AuraApp.create()
                    .use(new AuraModule() {
                        @Override public ModuleMetadata descriptor() {
                            return ModuleMetadata.of("orders", "1.0.0").requires("db");
                        }

                        @Override public void install(ModuleContext context) {
                            assertThat(context.require(Db.class)).isSameAs(db);
                        }
                    })
                    .use(DatabaseModule.create(db));

            app.prepare();
            assertThat(app.require(Db.class)).isSameAs(db);
        } finally {
            db.close();
        }
    }

    @Test
    void descriptorAdvertisesMigrationCapabilityOnlyWhenConfigured() {
        Db db = Db.create("jdbc:h2:mem:module-descriptor;DB_CLOSE_DELAY=-1", "sa", "");
        try {
            assertThat(DatabaseModule.create(db).descriptor().capabilities())
                    .containsExactly("db");
            assertThat(DatabaseModule.create(db)
                    .migrateWith(context -> new SchemaMigrationResult("test", 0))
                    .descriptor().capabilities())
                    .containsExactly("db", "db.migrations");
        } finally {
            db.close();
        }
    }

    @Test
    void startPassesAuraOwnedMigrationContextAndStoresResult() throws Exception {
        Db db = Db.create("catalog", "jdbc:h2:mem:module-context;DB_CLOSE_DELAY=-1", "sa", "");
        MigrationContext expected = db.migrationContext();
        AtomicReference<MigrationContext> seen = new AtomicReference<>();
        SchemaMigrationResult result = new SchemaMigrationResult("custom", 3);
        DatabaseModule module = DatabaseModule.create(db).migrateWith(context -> {
            seen.set(context);
            return result;
        });

        try {
            module.start(null);

            assertThat(seen.get().db()).isSameAs(db);
            assertThat(seen.get().dataSource()).isSameAs(expected.dataSource());
            assertThat(seen.get().databaseName()).isEqualTo("catalog");
            assertThat(module.migrationResult()).containsSame(result);
            try (Connection connection = seen.get().dataSource().getConnection()) {
                assertThat(connection.isClosed()).isFalse();
            }
        } finally {
            db.close();
        }
    }

    @Test
    void startRejectsNullMigrationResult() {
        Db db = Db.create("jdbc:h2:mem:module-null-result;DB_CLOSE_DELAY=-1", "sa", "");
        DatabaseModule module = DatabaseModule.create(db).migrateWith(context -> null);
        try {
            assertThatThrownBy(() -> module.start(null))
                    .isInstanceOfSatisfying(MigrationException.class, exception -> {
                        assertThat(exception).hasMessage("Schema migrator returned null");
                        assertThat(exception.code()).isEqualTo(MigrationException.CODE);
                    });
            assertThat(module.migrationResult()).isEmpty();
        } finally {
            db.close();
        }
    }

    @Test
    void startWrapsCheckedMigrationFailureWithStableCode() {
        Db db = Db.create("jdbc:h2:mem:module-checked-failure;DB_CLOSE_DELAY=-1", "sa", "");
        IOException failure = new IOException("adapter unavailable");
        DatabaseModule module = DatabaseModule.create(db).migrateWith(context -> {
            throw failure;
        });
        try {
            assertThatThrownBy(() -> module.start(null))
                    .isInstanceOfSatisfying(MigrationException.class, exception -> {
                        assertThat(exception).hasMessage("Schema migrator failed").hasCause(failure);
                        assertThat(exception.code()).isEqualTo(MigrationException.CODE);
                    });
            assertThat(module.migrationResult()).isEmpty();
        } finally {
            db.close();
        }
    }

    @Test
    void applicationStartupPreservesMigrationFailureCodeAndCause() {
        Db db = Db.create("jdbc:h2:mem:module-app-failure;DB_CLOSE_DELAY=-1", "sa", "");
        MigrationException failure = new MigrationException("checksum mismatch");
        AuraApp app = AuraApp.create()
                .use(DatabaseModule.create(db).migrateWith(context -> { throw failure; }));

        try {
            assertThatThrownBy(app::start)
                    .isInstanceOfSatisfying(ModuleException.class, exception -> {
                        assertThat(exception.code()).isEqualTo("AURA_MODULE_START_FAILED");
                        assertThat(exception.module()).isEqualTo("db");
                        assertThat(exception.causeCode()).isEqualTo(MigrationException.CODE);
                        assertThat(exception.getCause()).isSameAs(failure);
                    });
            assertThatThrownBy(() -> db.find("SELECT 1"))
                    .isInstanceOf(Db.DbException.class);
        } finally {
            db.close();
        }
    }

    @Test
    void sqlMigratorCreatesSchemaDuringModuleStart() throws Exception {
        Db db = Db.create("jdbc:h2:mem:module-sql-migration;DB_CLOSE_DELAY=-1", "sa", "");
        SqlMigrationSource source = () -> List.of(
                new SqlMigration("1", "create_items", "CREATE TABLE items (id INT PRIMARY KEY)"));
        DatabaseModule module = DatabaseModule.create(db).migrateWith(SqlSchemaMigrator.from(source));
        try {
            module.start(null);

            assertThat(db.find("SELECT * FROM items")).isEmpty();
            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(SqlSchemaMigrator.ID, 1));
        } finally {
            db.close();
        }
    }

    @Test
    void stopClosesOwnedDatabase() throws Exception {
        Db db = Db.create("jdbc:h2:mem:module-stop;DB_CLOSE_DELAY=-1", "sa", "");
        DatabaseModule module = DatabaseModule.create(db);

        module.stop(null);

        assertThatThrownBy(() -> db.find("SELECT 1"))
                .isInstanceOf(Db.DbException.class);
    }
}

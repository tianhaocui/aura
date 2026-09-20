package io.aura.db.flyway;

import io.aura.AuraApp;
import io.aura.ModuleException;
import io.aura.db.DatabaseModule;
import io.aura.db.Db;
import io.aura.db.MigrationException;
import io.aura.db.SchemaMigrationResult;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywaySchemaMigratorTest {
    @Test
    void appliesClasspathMigrationsOnceAndKeepsDatasourceUsable() throws Exception {
        Db db = database("flyway-direct");
        FlywaySchemaMigrator migrator = FlywaySchemaMigrator.classpath("db/flyway");
        DatabaseModule module = DatabaseModule.create(db).migrateWith(migrator);
        try {
            module.start(null);

            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(FlywaySchemaMigrator.ID, 2));
            assertThat(db.find("SELECT name FROM items WHERE id = ?", 1).get(0).getStr("name"))
                    .isEqualTo("first");

            module.start(null);
            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(FlywaySchemaMigrator.ID, 0));
            assertThat(db.find("SELECT COUNT(*) AS item_count FROM items").get(0).getLong("item_count"))
                    .isEqualTo(1L);
        } finally {
            db.close();
        }
    }

    @Test
    void applicationStartupPropagatesFlywayFailureWithStableAuraCodes() {
        Db db = database("flyway-failure");
        AuraApp app = AuraApp.create().use(DatabaseModule.create(db)
                .migrateWith(FlywaySchemaMigrator.classpath("db/flyway-broken")));

        assertThatThrownBy(app::start)
                .isInstanceOfSatisfying(ModuleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("AURA_MODULE_START_FAILED");
                    assertThat(failure.module()).isEqualTo("db");
                    assertThat(failure.causeCode()).isEqualTo(MigrationException.CODE);
                    assertThat(failure.getCause()).isInstanceOf(MigrationException.class);
                    assertThat(failure.getCause().getCause())
                            .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
                });
    }

    @Test
    void applicationStartupRunsFlywayThroughDatabaseModule() throws Exception {
        Db db = database("flyway-app");
        AuraApp app = AuraApp.create().use(DatabaseModule.create(db)
                .migrateWith(FlywaySchemaMigrator.classpath("classpath:/db/flyway")));
        try {
            app.start();

            assertThat(db.find("SELECT name FROM items")).hasSize(1);
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsMissingOrBlankLocationsBeforeStartup() {
        assertThatThrownBy(FlywaySchemaMigrator::classpath)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlywaySchemaMigrator.classpath(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlywaySchemaMigrator.classpath((String[]) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsMissingClasspathLocationDuringMigration() {
        Db db = database("flyway-missing");
        try {
            assertThatThrownBy(() -> DatabaseModule.create(db)
                    .migrateWith(FlywaySchemaMigrator.classpath("db/not-present"))
                    .start(null))
                    .isInstanceOf(MigrationException.class)
                    .hasCauseInstanceOf(org.flywaydb.core.api.FlywayException.class);
        } finally {
            db.close();
        }
    }

    @Test
    void bundlesCommonProductionDatabasePlugins() {
        Set<String> databaseTypes = Flyway.configure().getPluginRegister()
                .getPlugins(org.flywaydb.core.internal.database.DatabaseType.class)
                .stream()
                .map(type -> type.getClass().getName())
                .collect(Collectors.toSet());

        assertThat(databaseTypes)
                .contains(
                        "org.flywaydb.database.postgresql.PostgreSQLDatabaseType",
                        "org.flywaydb.database.mysql.MySQLDatabaseType",
                        "org.flywaydb.database.mysql.mariadb.MariaDBDatabaseType");
    }

    private static Db database(String name) {
        return Db.create("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }
}

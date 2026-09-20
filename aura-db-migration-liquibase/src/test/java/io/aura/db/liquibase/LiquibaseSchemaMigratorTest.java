package io.aura.db.liquibase;

import io.aura.AuraApp;
import io.aura.ModuleException;
import io.aura.db.DatabaseModule;
import io.aura.db.Db;
import io.aura.db.MigrationException;
import io.aura.db.SchemaMigrationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiquibaseSchemaMigratorTest {
    @Test
    void appliesClasspathChangelogOnceAndKeepsDatasourceUsable() throws Exception {
        Db db = database("liquibase-direct");
        LiquibaseSchemaMigrator migrator = LiquibaseSchemaMigrator.classpath(
                "db/liquibase/changelog.xml");
        DatabaseModule module = DatabaseModule.create(db).migrateWith(migrator);
        try {
            module.start(null);

            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(LiquibaseSchemaMigrator.ID, 2));
            assertThat(db.find("SELECT name FROM items WHERE id = ?", 1).get(0).getStr("name"))
                    .isEqualTo("first");

            module.start(null);
            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(LiquibaseSchemaMigrator.ID, 0));
            assertThat(db.find("SELECT COUNT(*) AS item_count FROM items").get(0).getLong("item_count"))
                    .isEqualTo(1L);
        } finally {
            db.close();
        }
    }

    @Test
    void applicationStartupPropagatesLiquibaseFailureWithStableAuraCodes() {
        Db db = database("liquibase-failure");
        AuraApp app = AuraApp.create().use(DatabaseModule.create(db)
                .migrateWith(LiquibaseSchemaMigrator.classpath("db/liquibase/broken.xml")));

        assertThatThrownBy(app::start)
                .isInstanceOfSatisfying(ModuleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("AURA_MODULE_START_FAILED");
                    assertThat(failure.module()).isEqualTo("db");
                    assertThat(failure.causeCode()).isEqualTo(MigrationException.CODE);
                    assertThat(failure.getCause()).isInstanceOf(MigrationException.class);
                    assertThat(failure.getCause().getCause())
                            .isInstanceOf(liquibase.exception.LiquibaseException.class);
                });
    }

    @Test
    void applicationStartupRunsLiquibaseThroughDatabaseModule() throws Exception {
        Db db = database("liquibase-app");
        AuraApp app = AuraApp.create().use(DatabaseModule.create(db)
                .migrateWith(LiquibaseSchemaMigrator.classpath("/db/liquibase/changelog.xml")));
        try {
            app.start();

            assertThat(db.find("SELECT name FROM items")).hasSize(1);
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsBlankChangelogBeforeStartup() {
        assertThatThrownBy(() -> LiquibaseSchemaMigrator.classpath(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LiquibaseSchemaMigrator.classpath(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LiquibaseSchemaMigrator.classpath("changelog.xml", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsMissingClasspathChangelogDuringMigration() {
        Db db = database("liquibase-missing");
        try {
            assertThatThrownBy(() -> DatabaseModule.create(db)
                    .migrateWith(LiquibaseSchemaMigrator.classpath("db/not-present.xml"))
                    .start(null))
                    .isInstanceOf(MigrationException.class)
                    .hasCauseInstanceOf(liquibase.exception.LiquibaseException.class);
        } finally {
            db.close();
        }
    }

    private static Db database(String name) {
        return Db.create("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }
}

package io.aura.db.liquibase;

import io.aura.AuraApp;
import io.aura.ModuleException;
import io.aura.db.DatabaseModule;
import io.aura.db.Db;
import io.aura.db.MigrationException;
import io.aura.db.SchemaMigrationResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.sql.SQLException;

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
    void concurrentFirstStartsSerializeChangesetsAcrossIndependentPools() throws Exception {
        String url = "jdbc:h2:mem:liquibase-concurrent;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        Db firstDb = Db.create("liquibase-first", url, "sa", "");
        Db secondDb = Db.create("liquibase-second", url, "sa", "");
        DatabaseModule first = DatabaseModule.create(firstDb)
                .migrateWith(LiquibaseSchemaMigrator.classpath("db/liquibase/changelog.xml"));
        DatabaseModule second = DatabaseModule.create(secondDb)
                .migrateWith(LiquibaseSchemaMigrator.classpath("db/liquibase/changelog.xml"));
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRun = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                first.start(null);
                return null;
            });
            Future<?> secondRun = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                second.start(null);
                return null;
            });
            firstRun.get(15, TimeUnit.SECONDS);
            secondRun.get(15, TimeUnit.SECONDS);

            int firstApplied = first.migrationResult().orElseThrow().appliedCount();
            int secondApplied = second.migrationResult().orElseThrow().appliedCount();
            assertThat(firstApplied + secondApplied).isEqualTo(2);
            assertThat(java.util.List.of(firstApplied, secondApplied)).containsExactlyInAnyOrder(0, 2);
            assertThat(firstDb.find("SELECT COUNT(*) AS item_count FROM items")
                    .get(0).getLong("item_count")).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            firstDb.close();
            secondDb.close();
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
                .migrateWith(LiquibaseSchemaMigrator.classpath(
                        "classpath:/db/liquibase/changelog.xml")));
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

    @Test
    void retriesOnlyExactLiquibaseHistoryTableBootstrapCollisions() {
        SQLException historyCollision = new SQLException(
                "Table PUBLIC.DATABASECHANGELOG already exists", "42101", 42101);
        SQLException historyLockCollision = new SQLException(
                "Table DATABASECHANGELOGLOCK already exists", "42S01", 1050);
        SQLException sqlServerCollision = new SQLException(
                "There is already an object named DATABASECHANGELOG", "S0001", 2714);
        SQLException oracleCollision = new SQLException(
                "name is already used by an existing object DATABASECHANGELOG", "42000", 955);
        SQLException businessTableCollision = new SQLException(
                "Table DATABASECHANGELOG_ARCHIVE already exists", "42101", 42101);
        SQLException ordinaryMigrationFailure = new SQLException(
                "Syntax error in migration", "42001", 42001);

        assertThat(LiquibaseSchemaMigrator.isConcurrentHistoryBootstrap(historyCollision)).isTrue();
        assertThat(LiquibaseSchemaMigrator.isConcurrentHistoryBootstrap(historyLockCollision)).isTrue();
        assertThat(LiquibaseSchemaMigrator.isConcurrentHistoryBootstrap(sqlServerCollision)).isTrue();
        assertThat(LiquibaseSchemaMigrator.isConcurrentHistoryBootstrap(oracleCollision)).isTrue();
        assertThat(LiquibaseSchemaMigrator.isConcurrentHistoryBootstrap(businessTableCollision)).isFalse();
        assertThat(LiquibaseSchemaMigrator.isConcurrentHistoryBootstrap(ordinaryMigrationFailure)).isFalse();
    }

    private static Db database(String name) {
        return Db.create("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }
}

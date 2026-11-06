package io.aura.db.flyway;

import io.aura.db.DatabaseModule;
import io.aura.db.Db;
import io.aura.db.SchemaMigrationResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FlywayExternalDatabaseTest {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void migratesPostgresAndIsIdempotent() throws Exception {
        Db db = Db.create("external", POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        DatabaseModule module = DatabaseModule.create(db)
                .migrateWith(FlywaySchemaMigrator.classpath("db/flyway"));
        try {
            dropMigrationState(db);
            module.start(null);
            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(FlywaySchemaMigrator.ID, 2));
            module.start(null);
            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(FlywaySchemaMigrator.ID, 0));
            assertThat(db.find("SELECT COUNT(*) AS item_count FROM items")
                    .get(0).getLong("item_count")).isEqualTo(1L);
        } finally {
            try {
                dropMigrationState(db);
            } finally {
                db.close();
            }
        }
    }

    /**
     * Flyway writes its history table in the same case on both engines, so this only has to move the
     * cleanup before the run as well: the first assertion is true only of an empty schema, and a
     * previous run aborted inside its teardown would otherwise fail this one for a reason it did not
     * cause. Dropping just the tables these migrations create is deliberate -- the container is
     * disposable, but keeping the cleanup narrow also proves the migrator does not depend on
     * teardown privileges over unrelated schemas.
     */
    private static void dropMigrationState(Db db) throws Exception {
        db.execute("DROP TABLE IF EXISTS items");
        db.execute("DROP TABLE IF EXISTS flyway_schema_history");
    }
}

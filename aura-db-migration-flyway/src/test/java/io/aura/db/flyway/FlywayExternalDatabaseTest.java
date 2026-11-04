package io.aura.db.flyway;

import io.aura.db.DatabaseModule;
import io.aura.db.Db;
import io.aura.db.SchemaMigrationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "AURA_MIGRATION_JDBC_URL", matches = ".+")
class FlywayExternalDatabaseTest {
    @Test
    void migratesConfiguredExternalDatabaseAndIsIdempotent() throws Exception {
        Db db = Db.create("external", System.getenv("AURA_MIGRATION_JDBC_URL"),
                System.getenv().getOrDefault("AURA_MIGRATION_JDBC_USER", "aura"),
                System.getenv().getOrDefault("AURA_MIGRATION_JDBC_PASSWORD", "aura"));
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
     * cause. Dropping just the tables these migrations create is deliberate -- a schema-wide cleanup
     * against whatever `AURA_MIGRATION_JDBC_URL` happens to point at would be a destructive act.
     */
    private static void dropMigrationState(Db db) throws Exception {
        db.execute("DROP TABLE IF EXISTS items");
        db.execute("DROP TABLE IF EXISTS flyway_schema_history");
    }
}

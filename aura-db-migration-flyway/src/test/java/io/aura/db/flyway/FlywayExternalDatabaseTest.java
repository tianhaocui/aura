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
            module.start(null);
            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(FlywaySchemaMigrator.ID, 2));
            module.start(null);
            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(FlywaySchemaMigrator.ID, 0));
            assertThat(db.find("SELECT COUNT(*) AS item_count FROM items")
                    .get(0).getLong("item_count")).isEqualTo(1L);
        } finally {
            db.close();
        }
    }
}

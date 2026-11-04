package io.aura.db.liquibase;

import io.aura.db.DatabaseModule;
import io.aura.db.Db;
import io.aura.db.SchemaMigrationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "AURA_MIGRATION_JDBC_URL", matches = ".+")
class LiquibaseExternalDatabaseTest {
    @Test
    void migratesConfiguredExternalDatabaseAndIsIdempotent() throws Exception {
        Db db = Db.create("external", System.getenv("AURA_MIGRATION_JDBC_URL"),
                System.getenv().getOrDefault("AURA_MIGRATION_JDBC_USER", "aura"),
                System.getenv().getOrDefault("AURA_MIGRATION_JDBC_PASSWORD", "aura"));
        DatabaseModule module = DatabaseModule.create(db)
                .migrateWith(LiquibaseSchemaMigrator.classpath("db/liquibase/changelog.xml"));
        try {
            dropMigrationState(db);
            module.start(null);
            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(LiquibaseSchemaMigrator.ID, 2));
            module.start(null);
            assertThat(module.migrationResult())
                    .contains(new SchemaMigrationResult(LiquibaseSchemaMigrator.ID, 0));
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
     * The first assertion is only true of an empty schema, so the state it needs is established before
     * the run and not only after it; a run aborted inside its own teardown would otherwise make every
     * later run fail for the wrong reason. Both spellings of the bookkeeping tables are dropped because
     * Liquibase creates them in upper case, MySQL on Linux matches identifiers case-sensitively, and
     * PostgreSQL folds unquoted names to lower case -- one spelling silently leaves them behind on the
     * first engine while `DROP TABLE IF EXISTS` reports success.
     */
    private static void dropMigrationState(Db db) throws Exception {
        db.execute("DROP TABLE IF EXISTS items");
        db.execute("DROP TABLE IF EXISTS databasechangeloglock");
        db.execute("DROP TABLE IF EXISTS DATABASECHANGELOGLOCK");
        db.execute("DROP TABLE IF EXISTS databasechangelog");
        db.execute("DROP TABLE IF EXISTS DATABASECHANGELOG");
    }
}

package io.aura.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlMigrationRunnerTest {
    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.create("jdbc:h2:mem:migrations;DB_CLOSE_DELAY=-1", "sa", "");
        db.execute("DROP ALL OBJECTS");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void appliesInNumericVersionOrderAndSkipsAppliedMigrations() {
        SqlMigrationSource source = () -> List.of(
                new SqlMigration("10", "insert_user", "INSERT INTO migrated_users VALUES (1, 'Ada')"),
                new SqlMigration("2", "create_users", "CREATE TABLE migrated_users (id INT PRIMARY KEY, name VARCHAR(50))")
        );

        SqlMigrationResult first = run(source);
        SqlMigrationResult second = run(source);

        assertThat(first.applied()).containsExactly("2", "10");
        assertThat(second.applied()).isEmpty();
        assertThat(second.skipped()).containsExactly("2", "10");
        assertThat(db.findOne("SELECT name FROM migrated_users WHERE id = 1").getStr("name")).isEqualTo("Ada");
    }

    @Test
    void rejectsChangedAppliedMigration() {
        run(() -> List.of(new SqlMigration("1", "create", "CREATE TABLE stable_table (id INT)")));

        assertThatThrownBy(() -> run(() -> List.of(
                new SqlMigration("1", "create", "CREATE TABLE stable_table (id BIGINT)"))))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("Checksum mismatch");
    }

    @Test
    void rollsBackDataChangesWhenMigrationFails() {
        db.execute("CREATE TABLE events (id INT PRIMARY KEY)");
        SqlMigration migration = new SqlMigration("1", "broken", "INSERT INTO events VALUES (1); INSERT INTO missing VALUES (1)");

        assertThatThrownBy(() -> run(() -> List.of(migration)))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("Failed migration 1");
        assertThat(db.find("SELECT * FROM events")).isEmpty();
        assertThat(db.find("SELECT * FROM _migrations")).isEmpty();
    }

    @Test
    void classpathSourceLoadsSqlFilesThroughSchemaMigrator(@TempDir Path root) throws Exception {
        Path migrations = Files.createDirectories(root.resolve("migrations"));
        Files.writeString(migrations.resolve("001_create_items.sql"),
                "-- create schema; comment\nCREATE TABLE items (id INT PRIMARY KEY, item_value VARCHAR(50));");
        Files.writeString(migrations.resolve("002_insert_item.sql"),
                "/* semicolon; in comment */ INSERT INTO items VALUES (1, 'a;b');");

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, null)) {
            SchemaMigrationResult result = SqlSchemaMigrator.from(
                    new ClasspathSqlMigrationSource("classpath:migrations", loader)).migrate(db.migrationContext());
            assertThat(result).isEqualTo(new SchemaMigrationResult(SqlSchemaMigrator.ID, 2));
            assertThat(db.findOne("SELECT item_value FROM items WHERE id = 1").getStr("item_value")).isEqualTo("a;b");
        }
    }

    @Test
    void classpathSourceRejectsMissingLocation(@TempDir Path root) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> SqlSchemaMigrator.from(
                    new ClasspathSqlMigrationSource("missing", loader)).migrate(db.migrationContext()))
                    .isInstanceOf(MigrationException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Test
    void classpathSourceRejectsOversizedMigration(@TempDir Path root) throws Exception {
        Path migrations = Files.createDirectories(root.resolve("migrations"));
        Files.writeString(migrations.resolve("001_oversized.sql"), "x".repeat(16 * 1024 * 1024 + 1));

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> new ClasspathSqlMigrationSource("migrations", loader).migrations())
                    .isInstanceOf(MigrationException.class)
                    .hasMessageContaining("exceeds maximum size");
        }
    }

    @Test
    void classpathSourceRejectsBlankLocation() {
        assertThatThrownBy(() -> new ClasspathSqlMigrationSource(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("location must not be blank");
        assertThatThrownBy(() -> new ClasspathSqlMigrationSource("classpath:"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("location must not be blank");
    }

    private SqlMigrationResult run(SqlMigrationSource source) {
        return new SqlMigrationRunner(db).run(source);
    }
}

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

class MigrationRunnerTest {
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
        MigrationSource source = () -> List.of(
                new Migration("10", "insert_user", "INSERT INTO migrated_users VALUES (1, 'Ada')"),
                new Migration("2", "create_users", "CREATE TABLE migrated_users (id INT PRIMARY KEY, name VARCHAR(50))")
        );

        MigrationResult first = db.migrate(source);
        MigrationResult second = db.migrate(source);

        assertThat(first.applied()).containsExactly("2", "10");
        assertThat(second.applied()).isEmpty();
        assertThat(second.skipped()).containsExactly("2", "10");
        assertThat(db.findOne("SELECT name FROM migrated_users WHERE id = 1").getStr("name")).isEqualTo("Ada");
    }

    @Test
    void rejectsChangedAppliedMigration() {
        db.migrate(() -> List.of(new Migration("1", "create", "CREATE TABLE stable_table (id INT)")));

        assertThatThrownBy(() -> db.migrate(() -> List.of(
                new Migration("1", "create", "CREATE TABLE stable_table (id BIGINT)"))))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("Checksum mismatch");
    }

    @Test
    void rollsBackDataChangesWhenMigrationFails() {
        db.execute("CREATE TABLE events (id INT PRIMARY KEY)");
        Migration migration = new Migration("1", "broken", "INSERT INTO events VALUES (1); INSERT INTO missing VALUES (1)");

        assertThatThrownBy(() -> db.migrate(() -> List.of(migration)))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("Failed migration 1");
        assertThat(db.find("SELECT * FROM events")).isEmpty();
        assertThat(db.find("SELECT * FROM _migrations")).isEmpty();
    }

    @Test
    void classpathSourceLoadsSqlFiles(@TempDir Path root) throws Exception {
        Path migrations = Files.createDirectories(root.resolve("migrations"));
        Files.writeString(migrations.resolve("001_create_items.sql"),
                "-- create schema; comment\nCREATE TABLE items (id INT PRIMARY KEY, item_value VARCHAR(50));");
        Files.writeString(migrations.resolve("002_insert_item.sql"),
                "/* semicolon; in comment */ INSERT INTO items VALUES (1, 'a;b');");

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, null)) {
            MigrationResult result = db.migrate(new ClasspathMigrationSource("classpath:migrations", loader));
            assertThat(result.applied()).containsExactly("001", "002");
            assertThat(db.findOne("SELECT item_value FROM items WHERE id = 1").getStr("item_value")).isEqualTo("a;b");
        }
    }

    @Test
    void classpathSourceRejectsMissingLocation(@TempDir Path root) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> new ClasspathMigrationSource("missing", loader).migrations())
                    .isInstanceOf(MigrationException.class)
                    .hasMessageContaining("not found");
        }
    }
}

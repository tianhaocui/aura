package io.aura.db;

import io.aura.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FindOrThrowTest {

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.create("jdbc:h2:mem:findorthrow;DB_CLOSE_DELAY=-1", "sa", "");
        db.execute("DROP TABLE IF EXISTS users");
        db.execute("""
            CREATE TABLE users (
                id   BIGINT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(100)
            )
        """);
        Row.of("users").set("name", "Alice").insert(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void findOneOrThrow_returnsRow() {
        Row row = db.table("users").where("name", "Alice").findOneOrThrow();
        assertThat(row.get("name")).isEqualTo("Alice");
    }

    @Test
    void findOneOrThrow_throwsWhenNotFound() {
        assertThatThrownBy(() -> db.table("users").where("name", "Nobody").findOneOrThrow())
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Not found: users");
    }

    @Test
    void findOneOrThrow_customMessage() {
        assertThatThrownBy(() -> db.table("users").where("name", "X").findOneOrThrow("User X missing"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User X missing");
    }

    @Test
    void findByIdOrThrow_returnsRow() {
        Row row = db.findByIdOrThrow("users", 1L);
        assertThat(row.get("name")).isEqualTo("Alice");
    }

    @Test
    void findByIdOrThrow_throwsWhenNotFound() {
        assertThatThrownBy(() -> db.findByIdOrThrow("users", 999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("users not found: id=999");
    }

    @Test
    void findByIdOrThrow_customPrimaryKey() {
        db.execute("DROP TABLE IF EXISTS items");
        db.execute("CREATE TABLE items (code VARCHAR(10) PRIMARY KEY, label VARCHAR(50))");
        Row.of("items").set("code", "A1").set("label", "Item A").insert(db);

        Row row = db.findByIdOrThrow("items", "code", "A1");
        assertThat(row.get("label")).isEqualTo("Item A");

        assertThatThrownBy(() -> db.findByIdOrThrow("items", "code", "ZZ"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("items not found: code=ZZ");
    }
}

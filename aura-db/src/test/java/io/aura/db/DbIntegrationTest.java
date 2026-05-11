package io.aura.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DbIntegrationTest {

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.create("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
        db.execute("DROP TABLE IF EXISTS users");
        db.execute("""
            CREATE TABLE users (
                id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                name       VARCHAR(100),
                active     BOOLEAN,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """);
    }

    @AfterEach
    void tearDown() {
        db.execute("DROP TABLE IF EXISTS users");
        db.close();
    }

    // --- Row.insert / findById table propagation ---

    @Test
    void findById_rowHasTableSet_canUpdate() {
        Row inserted = Row.of("users").set("name", "Alice").set("active", true).insert(db);
        Object id = inserted.id();
        assertThat(id).isNotNull();

        Row found = db.findById("users", id);
        assertThat(found).isNotNull();
        assertThat(found.table()).isEqualTo("users");
        assertThat(found.getStr("name")).isEqualTo("Alice");

        found.set("name", "Bob");
        boolean updated = found.update(db);
        assertThat(updated).isTrue();

        Row refetched = db.findById("users", id);
        assertThat(refetched.getStr("name")).isEqualTo("Bob");
    }

    @Test
    void findById_rowHasTableSet_canDelete() {
        Row inserted = Row.of("users").set("name", "ToDelete").set("active", false).insert(db);
        Object id = inserted.id();

        Row found = db.findById("users", id);
        assertThat(found.table()).isEqualTo("users");

        boolean deleted = found.delete(db);
        assertThat(deleted).isTrue();
        assertThat(db.findById("users", id)).isNull();
    }

    @Test
    void findBy_rowsHaveTableSet() {
        Row.of("users").set("name", "Alice").set("active", true).insert(db);
        Row.of("users").set("name", "Bob").set("active", true).insert(db);

        List<Row> rows = db.findBy("users", "active = ?", true);
        assertThat(rows).hasSize(2);
        for (Row row : rows) {
            assertThat(row.table()).isEqualTo("users");
        }
    }

    // --- Row.insertFull ---

    @Test
    void insertFull_returnsRowWithServerGeneratedColumns() {
        Row row = Row.of("users").set("name", "Charlie").set("active", true).insertFull(db);
        assertThat((Object) row.id()).isNotNull();
        assertThat(row.getStr("name")).isEqualTo("Charlie");
        // created_at should be populated from the DB
        assertThat(row.get("created_at")).isNotNull();
    }

    // --- Timestamp serialization ---

    @Test
    void timestamp_serializedAsLocalDateTime_noZSuffix() {
        Row.of("users").set("name", "TimestampTest").set("active", true).insert(db);
        Row row = db.findOne("SELECT * FROM users WHERE name = ?", "TimestampTest");
        assertThat(row).isNotNull();
        Object createdAt = row.get("created_at");
        assertThat(createdAt).isInstanceOf(String.class);
        String ts = (String) createdAt;
        // must not end with Z (UTC marker)
        assertThat(ts).doesNotEndWith("Z");
        // must look like a local datetime: 2024-01-15T10:30:00
        assertThat(ts).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
    }

    // --- Row.insert returns generated key ---

    @Test
    void insert_setsGeneratedId() {
        Row row = Row.of("users").set("name", "KeyTest").set("active", false).insert(db);
        assertThat((Object) row.id()).isNotNull();
    }

    // --- Row.update / delete without table throws ---

    @Test
    void update_withoutTable_throwsIllegalState() {
        Row row = Row.of("").set("name", "x").id(1);
        assertThatThrownBy(() -> row.update(db))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Table name not set");
    }

    @Test
    void delete_withoutTable_throwsIllegalState() {
        Row row = Row.of("").id(1);
        assertThatThrownBy(() -> row.delete(db))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Table name not set");
    }

    // --- transaction ---

    @Test
    void transaction_rollsBackOnException() {
        assertThatThrownBy(() -> db.transaction(() -> {
            Row.of("users").set("name", "TxTest").set("active", true).insert(db);
            throw new RuntimeException("rollback");
        })).isInstanceOf(RuntimeException.class);

        Row found = db.findOne("SELECT * FROM users WHERE name = ?", "TxTest");
        assertThat(found).isNull();
    }

    @Test
    void transaction_commitsOnSuccess() {
        db.transaction(() -> {
            Row.of("users").set("name", "TxCommit").set("active", true).insert(db);
        });
        Row found = db.findOne("SELECT * FROM users WHERE name = ?", "TxCommit");
        assertThat(found).isNotNull();
    }
}

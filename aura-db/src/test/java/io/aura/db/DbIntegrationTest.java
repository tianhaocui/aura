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

        List<Row> rows = db.findWhere("users", "active = ?", true);
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

    // --- Timestamp preserved as LocalDateTime (JDBC type, not String) ---

    @Test
    void timestamp_preservedAsLocalDateTime() {
        Row.of("users").set("name", "TimestampTest").set("active", true).insert(db);
        Row row = db.findOne("SELECT * FROM users WHERE name = ?", "TimestampTest");
        assertThat(row).isNotNull();
        Object createdAt = row.get("created_at");
        // stored as LocalDateTime so update() can write it back via JDBC
        assertThat(createdAt).isInstanceOf(java.time.LocalDateTime.class);
    }

    @Test
    void timestamp_getStr_returnsReadableString() {
        Row.of("users").set("name", "StrTest").set("active", true).insert(db);
        Row row = db.findOne("SELECT * FROM users WHERE name = ?", "StrTest");
        String ts = row.getStr("created_at");
        assertThat(ts).isNotNull().isNotBlank();
    }

    // --- Row.insert returns generated key ---

    @Test
    void insert_setsGeneratedId() {
        Row row = Row.of("users").set("name", "KeyTest").set("active", false).insert(db);
        assertThat((Object) row.id()).isNotNull();
    }

    // --- Row.exclude ---

    @Test
    void exclude_preventsColumnFromBeingUpdated() {
        Row inserted = Row.of("users").set("name", "ExcludeTest").set("active", true).insertFull(db);
        Object id = inserted.id();
        String originalCreatedAt = inserted.getStr("created_at");

        // simulate read-modify-write: exclude server-managed column
        Row found = db.findById("users", id);
        found.exclude("created_at").set("name", "Updated");
        found.update(db);

        Row refetched = db.findById("users", id);
        assertThat(refetched.getStr("name")).isEqualTo("Updated");
        assertThat(refetched.getStr("created_at")).isEqualTo(originalCreatedAt);
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

    @Test
    void transaction_nested_reusesOuter() {
        db.transaction(() -> {
            Row.of("users").set("name", "Outer").set("active", true).insert(db);
            db.transaction(() -> {
                Row.of("users").set("name", "Inner").set("active", true).insert(db);
            });
        });
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Outer")).isNotNull();
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Inner")).isNotNull();
    }

    @Test
    void transaction_nested_outerRollbackRollsBothBack() {
        assertThatThrownBy(() -> db.transaction(() -> {
            Row.of("users").set("name", "Outer2").set("active", true).insert(db);
            db.transaction(() -> {
                Row.of("users").set("name", "Inner2").set("active", true).insert(db);
            });
            throw new RuntimeException("rollback outer");
        }));
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Outer2")).isNull();
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Inner2")).isNull();
    }

    @Test
    void transactionIndependent_independentFromOuter() {
        assertThatThrownBy(() -> db.transaction(() -> {
            Row.of("users").set("name", "OuterNew").set("active", true).insert(db);
            db.transactionIndependent(() -> {
                Row.of("users").set("name", "InnerNew").set("active", true).insert(db);
            });
            throw new RuntimeException("rollback outer");
        }));
        // outer rolled back
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "OuterNew")).isNull();
        // inner committed independently
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "InnerNew")).isNotNull();
    }

    @Test
    void beginTransaction_manualCommit() {
        try (var tx = db.beginTransaction()) {
            Row.of("users").set("name", "Manual").set("active", true).insert(db);
            tx.commit();
        }
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "Manual")).isNotNull();
    }

    @Test
    void beginTransaction_manualRollback() {
        try (var tx = db.beginTransaction()) {
            Row.of("users").set("name", "ManualRollback").set("active", true).insert(db);
            tx.rollback();
        }
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "ManualRollback")).isNull();
    }

    @Test
    void beginTransaction_autoCloseRollsBack() {
        try (var tx = db.beginTransaction()) {
            Row.of("users").set("name", "AutoClose").set("active", true).insert(db);
            // no commit — close() should rollback
        }
        assertThat(db.findOne("SELECT * FROM users WHERE name = ?", "AutoClose")).isNull();
    }
}

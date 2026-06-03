package io.aura.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DbUpdateDynamicTest {

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.create("jdbc:h2:mem:update_dyn_test;DB_CLOSE_DELAY=-1", "sa", "");
        db.execute("DROP TABLE IF EXISTS rides");
        db.execute("""
            CREATE TABLE rides (
                id       BIGINT AUTO_INCREMENT PRIMARY KEY,
                title    VARCHAR(100),
                distance DOUBLE,
                status   VARCHAR(20)
            )
        """);
        Row.of("rides").set("title", "Morning Ride").set("distance", 10.5).set("status", "draft").insert(db);
    }

    @AfterEach
    void tearDown() {
        db.execute("DROP TABLE IF EXISTS rides");
        db.close();
    }

    @Test
    void updateDynamic_onlyNonNullFieldsUpdated() {
        Row original = db.findOne("SELECT * FROM rides WHERE title = ?", "Morning Ride");
        Object id = original.id();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "Evening Ride");
        data.put("distance", null);
        data.put("status", "published");

        int affected = db.updateDynamic("rides", data, "id", id);
        assertThat(affected).isEqualTo(1);

        Row updated = db.findById("rides", id);
        assertThat(updated.getStr("title")).isEqualTo("Evening Ride");
        assertThat(updated.get("distance")).isEqualTo(10.5); // unchanged
        assertThat(updated.getStr("status")).isEqualTo("published");
    }

    @Test
    void updateDynamic_allNull_returnsZero() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", null);
        data.put("status", null);

        int affected = db.updateDynamic("rides", data, "id", 1L);
        assertThat(affected).isEqualTo(0);
    }

    @Test
    void updateDynamic_noMatchingRow_returnsZero() {
        Map<String, Object> data = Map.of("title", "Changed");
        int affected = db.updateDynamic("rides", data, "id", 9999L);
        assertThat(affected).isEqualTo(0);
    }
}

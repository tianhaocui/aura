package io.aura.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DbBatchInsertTest {

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.create("jdbc:h2:mem:batch_test;DB_CLOSE_DELAY=-1", "sa", "");
        db.execute("DROP TABLE IF EXISTS points");
        db.execute("""
            CREATE TABLE points (
                id  BIGINT AUTO_INCREMENT PRIMARY KEY,
                lat DOUBLE,
                lng DOUBLE,
                ts  BIGINT
            )
        """);
    }

    @AfterEach
    void tearDown() {
        db.execute("DROP TABLE IF EXISTS points");
        db.close();
    }

    @Test
    void batchInsert_multipleRows() {
        List<Row> rows = List.of(
                Row.of("points").set("lat", 39.9).set("lng", 116.4).set("ts", 1000L),
                Row.of("points").set("lat", 40.0).set("lng", 116.5).set("ts", 2000L),
                Row.of("points").set("lat", 40.1).set("lng", 116.6).set("ts", 3000L)
        );

        int count = db.batchInsert("points", rows);
        assertThat(count).isEqualTo(3);

        List<Row> found = db.find("SELECT * FROM points ORDER BY ts");
        assertThat(found).hasSize(3);
        assertThat(found.get(0).get("lat")).isEqualTo(39.9);
        assertThat(found.get(2).get("ts")).isEqualTo(3000L);
    }

    @Test
    void batchInsert_emptyList_returnsZero() {
        int count = db.batchInsert("points", List.of());
        assertThat(count).isEqualTo(0);
    }

    @Test
    void batchInsert_unevenColumns_fillsNullForMissing() {
        List<Row> rows = List.of(
                Row.of("points").set("lat", 1.0).set("lng", 2.0).set("ts", 100L),
                Row.of("points").set("lat", 3.0).set("lng", 4.0)
        );

        int count = db.batchInsert("points", rows);
        assertThat(count).isEqualTo(2);

        List<Row> found = db.find("SELECT * FROM points ORDER BY lat");
        assertThat(found.get(0).get("ts")).isEqualTo(100L);
        assertThat(found.get(1).get("ts")).isNull();
    }

    @Test
    void rowBatchInsert_staticHelper() {
        List<Row> rows = List.of(
                Row.of("points").set("lat", 10.0).set("lng", 20.0).set("ts", 500L),
                Row.of("points").set("lat", 11.0).set("lng", 21.0).set("ts", 600L)
        );

        int count = Row.batchInsert(db, rows);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void rowBatchInsert_noTable_throws() {
        List<Row> rows = List.of(Row.of("").set("lat", 1.0));
        assertThatThrownBy(() -> Row.batchInsert(db, rows))
                .isInstanceOf(IllegalStateException.class);
    }
}

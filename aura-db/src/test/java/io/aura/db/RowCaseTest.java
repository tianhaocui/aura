package io.aura.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RowCaseTest {

    @Test
    void set_preservesOriginalCase() {
        Row row = Row.of("test").set("userName", "Alice");
        assertThat(row.containsKey("userName")).isTrue();
        assertThat(row.containsKey("username")).isFalse();
    }

    @Test
    void get_caseInsensitiveFallback() {
        Row row = Row.of("test").set("userName", "Alice");
        assertThat(row.get("userName")).isEqualTo("Alice");
        assertThat(row.get("username")).isEqualTo("Alice");
        assertThat(row.get("USERNAME")).isEqualTo("Alice");
    }

    @Test
    void get_exactMatchTakesPrecedence() {
        Row row = Row.of("test");
        row.put("Name", "exact");
        row.put("name", "lower");
        assertThat(row.get("name")).isEqualTo("lower");
        assertThat(row.get("Name")).isEqualTo("exact");
    }

    @Test
    void getDouble_fromNumber() {
        Row row = Row.of("test").set("price", 9.99);
        assertThat(row.getDouble("price")).isEqualTo(9.99);
    }

    @Test
    void getDouble_fromString() {
        Row row = Row.of("test").set("rate", "3.14");
        assertThat(row.getDouble("rate")).isEqualTo(3.14);
    }

    @Test
    void getDouble_null_returnsNull() {
        Row row = Row.of("test");
        assertThat(row.getDouble("missing")).isNull();
    }

    @Test
    void getDouble_withDefault() {
        Row row = Row.of("test");
        assertThat(row.getDouble("missing", 1.0)).isEqualTo(1.0);
        row.set("val", 2.5);
        assertThat(row.getDouble("val", 1.0)).isEqualTo(2.5);
    }
}

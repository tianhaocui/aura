package io.aura.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RowGetterDefaultTest {

    @Test
    void getStr_returnsDefault_whenNull() {
        Row row = Row.of("test");
        assertThat(row.getStr("missing", "fallback")).isEqualTo("fallback");
    }

    @Test
    void getStr_returnsValue_whenPresent() {
        Row row = Row.of("test").set("name", "Alice");
        assertThat(row.getStr("name", "fallback")).isEqualTo("Alice");
    }

    @Test
    void getInt_returnsDefault_whenNull() {
        Row row = Row.of("test");
        assertThat(row.getInt("missing", 42)).isEqualTo(42);
    }

    @Test
    void getInt_returnsValue_whenPresent() {
        Row row = Row.of("test").set("age", 25);
        assertThat(row.getInt("age", 0)).isEqualTo(25);
    }

    @Test
    void getLong_returnsDefault_whenNull() {
        Row row = Row.of("test");
        assertThat(row.getLong("missing", 99L)).isEqualTo(99L);
    }

    @Test
    void getLong_returnsValue_whenPresent() {
        Row row = Row.of("test").set("score", 100L);
        assertThat(row.getLong("score", 0L)).isEqualTo(100L);
    }

    @Test
    void getBool_returnsDefault_whenNull() {
        Row row = Row.of("test");
        assertThat(row.getBool("missing", true)).isTrue();
    }

    @Test
    void getBool_returnsValue_whenPresent() {
        Row row = Row.of("test").set("active", false);
        assertThat(row.getBool("active", true)).isFalse();
    }
}

package io.aura.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RowTest {

    // --- factory methods ---

    @Test
    void of_table_setsTableName() {
        Row row = Row.of("users");
        assertThat(row.table()).isEqualTo("users");
    }

    @Test
    void of_table_defaultPrimaryKeyIsId() {
        Row row = Row.of("users");
        assertThat(row.primaryKey()).isEqualTo("id");
    }

    @Test
    void of_tableAndPrimaryKey_setsCustomPk() {
        Row row = Row.of("orders", "order_id");
        assertThat(row.table()).isEqualTo("orders");
        assertThat(row.primaryKey()).isEqualTo("order_id");
    }

    // --- set / chaining ---

    @Test
    void set_returnsSelf() {
        Row row = Row.of("users");
        Row result = row.set("name", "Alice");
        assertThat(result).isSameAs(row);
    }

    @Test
    void set_storesValue() {
        Row row = Row.of("users").set("name", "Alice").set("age", 30);
        assertThat(row.get("name")).isEqualTo("Alice");
        assertThat(row.get("age")).isEqualTo(30);
    }

    // --- id ---

    @Test
    void id_setsAndReadsPrimaryKeyValue() {
        Row row = Row.of("users").id(42);
        assertThat((Object) row.id()).isEqualTo(42);
    }

    @Test
    void id_usesCustomPrimaryKeyField() {
        Row row = Row.of("orders", "order_id").id(99L);
        assertThat(row.get("order_id")).isEqualTo(99L);
        assertThat((Object) row.id()).isEqualTo(99L);
    }

    // --- getStr ---

    @Test
    void getStr_returnsStringValue() {
        Row row = Row.of("t").set("col", "hello");
        assertThat(row.getStr("col")).isEqualTo("hello");
    }

    @Test
    void getStr_convertsNonStringToString() {
        Row row = Row.of("t").set("col", 123);
        assertThat(row.getStr("col")).isEqualTo("123");
    }

    @Test
    void getStr_returnsNullForMissingKey() {
        Row row = Row.of("t");
        assertThat(row.getStr("missing")).isNull();
    }

    // --- getInt ---

    @Test
    void getInt_returnsIntFromNumber() {
        Row row = Row.of("t").set("count", 7);
        assertThat(row.getInt("count")).isEqualTo(7);
    }

    @Test
    void getInt_parsesFromString() {
        Row row = Row.of("t").set("count", "42");
        assertThat(row.getInt("count")).isEqualTo(42);
    }

    @Test
    void getInt_returnsNullForMissingKey() {
        Row row = Row.of("t");
        assertThat(row.getInt("missing")).isNull();
    }

    // --- getLong ---

    @Test
    void getLong_returnsLongFromNumber() {
        Row row = Row.of("t").set("big", 1_000_000_000_000L);
        assertThat(row.getLong("big")).isEqualTo(1_000_000_000_000L);
    }

    @Test
    void getLong_parsesFromString() {
        Row row = Row.of("t").set("big", "9876543210");
        assertThat(row.getLong("big")).isEqualTo(9876543210L);
    }

    @Test
    void getLong_returnsNullForMissingKey() {
        Row row = Row.of("t");
        assertThat(row.getLong("missing")).isNull();
    }

    // --- getBool ---

    @Test
    void getBool_returnsBooleanValue() {
        Row row = Row.of("t").set("active", true);
        assertThat(row.getBool("active")).isTrue();
    }

    @Test
    void getBool_parsesFromString() {
        Row row = Row.of("t").set("active", "true");
        assertThat(row.getBool("active")).isTrue();
    }

    @Test
    void getBool_returnsFalseForStringFalse() {
        Row row = Row.of("t").set("active", "false");
        assertThat(row.getBool("active")).isFalse();
    }

    @Test
    void getBool_returnsNullForMissingKey() {
        Row row = Row.of("t");
        assertThat(row.getBool("missing")).isNull();
    }

    // --- table / primaryKey accessors ---

    @Test
    void tableAndPrimaryKey_accessors() {
        Row row = Row.of("products", "sku");
        assertThat(row.table()).isEqualTo("products");
        assertThat(row.primaryKey()).isEqualTo("sku");
    }
}

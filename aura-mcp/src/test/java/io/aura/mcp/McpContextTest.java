package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpContextTest {

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** Build a context with no mappings. */
    private static McpContext ctx(Map<String, Object> args) {
        return new McpContext(args, Map.of());
    }

    /** Build a context with one named mapping. */
    private static McpContext ctx(Map<String, Object> args, String paramName, Map<String, Object> mapping) {
        return new McpContext(args, Map.of(paramName, mapping));
    }

    // -----------------------------------------------------------------------
    // get()
    // -----------------------------------------------------------------------

    @Test
    void get_presentKey_returnsValue() {
        McpContext c = ctx(Map.of("x", "hello"));
        assertThat(c.get("x")).isEqualTo("hello");
    }

    @Test
    void get_missingKey_returnsNull() {
        McpContext c = ctx(Map.of());
        assertThat(c.get("missing")).isNull();
    }

    @Test
    void get_mappingHit_returnsResolvedValue() {
        Map<String, Object> mapping = Map.of("北京", "010");
        McpContext c = ctx(Map.of("city", "北京"), "city", mapping);
        assertThat(c.get("city")).isEqualTo("010");
    }

    @Test
    void get_mappingMiss_returnsOriginalValue() {
        Map<String, Object> mapping = Map.of("北京", "010");
        McpContext c = ctx(Map.of("city", "深圳"), "city", mapping);
        assertThat(c.get("city")).isEqualTo("深圳");
    }

    @Test
    void get_nullValue_returnsNull() {
        Map<String, Object> args = new HashMap<>();
        args.put("x", null);
        McpContext c = ctx(args);
        assertThat(c.get("x")).isNull();
    }

    // -----------------------------------------------------------------------
    // getString()
    // -----------------------------------------------------------------------

    @Test
    void getString_presentString_returnsString() {
        McpContext c = ctx(Map.of("name", "Alice"));
        assertThat(c.getString("name")).isEqualTo("Alice");
    }

    @Test
    void getString_missingKey_returnsNull() {
        McpContext c = ctx(Map.of());
        assertThat(c.getString("name")).isNull();
    }

    @Test
    void getString_integerValue_returnsStringRepresentation() {
        McpContext c = ctx(Map.of("n", 42));
        assertThat(c.getString("n")).isEqualTo("42");
    }

    @Test
    void getString_nullValue_returnsNull() {
        Map<String, Object> args = new HashMap<>();
        args.put("name", null);
        McpContext c = ctx(args);
        assertThat(c.getString("name")).isNull();
    }

    // -----------------------------------------------------------------------
    // getInt()
    // -----------------------------------------------------------------------

    @Test
    void getInt_numberValue_returnsIntValue() {
        McpContext c = ctx(Map.of("n", 7));
        assertThat(c.getInt("n")).isEqualTo(7);
    }

    @Test
    void getInt_stringValue_parsesInt() {
        McpContext c = ctx(Map.of("n", "42"));
        assertThat(c.getInt("n")).isEqualTo(42);
    }

    @Test
    void getInt_missingKey_returnsZero() {
        McpContext c = ctx(Map.of());
        assertThat(c.getInt("n")).isEqualTo(0);
    }

    @Test
    void getInt_nullValue_returnsZero() {
        Map<String, Object> args = new HashMap<>();
        args.put("n", null);
        McpContext c = ctx(args);
        assertThat(c.getInt("n")).isEqualTo(0);
    }

    @Test
    void getInt_longNumber_truncatesToInt() {
        McpContext c = ctx(Map.of("n", 100L));
        assertThat(c.getInt("n")).isEqualTo(100);
    }

    @Test
    void getInt_mappingResolvesBeforeParsing() {
        Map<String, Object> mapping = Map.of("北京", 10);
        McpContext c = ctx(Map.of("zone", "北京"), "zone", mapping);
        assertThat(c.getInt("zone")).isEqualTo(10);
    }

    @Test
    void getInt_invalidString_throwsNumberFormatException() {
        McpContext c = ctx(Map.of("n", "not-a-number"));
        assertThatThrownBy(() -> c.getInt("n"))
                .isInstanceOf(NumberFormatException.class);
    }

    // -----------------------------------------------------------------------
    // getLong()
    // -----------------------------------------------------------------------

    @Test
    void getLong_numberValue_returnsLongValue() {
        McpContext c = ctx(Map.of("t", 21000000L));
        assertThat(c.getLong("t")).isEqualTo(21000000L);
    }

    @Test
    void getLong_stringValue_parsesLong() {
        McpContext c = ctx(Map.of("t", "9999999999"));
        assertThat(c.getLong("t")).isEqualTo(9999999999L);
    }

    @Test
    void getLong_missingKey_returnsZero() {
        McpContext c = ctx(Map.of());
        assertThat(c.getLong("t")).isEqualTo(0L);
    }

    @Test
    void getLong_nullValue_returnsZero() {
        Map<String, Object> args = new HashMap<>();
        args.put("t", null);
        McpContext c = ctx(args);
        assertThat(c.getLong("t")).isEqualTo(0L);
    }

    @Test
    void getLong_intNumber_returnsLong() {
        McpContext c = ctx(Map.of("t", 42));
        assertThat(c.getLong("t")).isEqualTo(42L);
    }

    @Test
    void getLong_mappingResolvesBeforeParsing() {
        Map<String, Object> mapping = Map.of("北京", 21000000L);
        McpContext c = ctx(Map.of("pop", "北京"), "pop", mapping);
        assertThat(c.getLong("pop")).isEqualTo(21000000L);
    }

    // -----------------------------------------------------------------------
    // getEnum()
    // -----------------------------------------------------------------------

    enum Color { RED, GREEN, BLUE }

    @Test
    void getEnum_validName_returnsEnumConstant() {
        McpContext c = ctx(Map.of("color", "GREEN"));
        assertThat(c.getEnum("color", Color.class)).isEqualTo(Color.GREEN);
    }

    @Test
    void getEnum_missingKey_returnsNull() {
        McpContext c = ctx(Map.of());
        assertThat(c.getEnum("color", Color.class)).isNull();
    }

    @Test
    void getEnum_nullValue_returnsNull() {
        Map<String, Object> args = new HashMap<>();
        args.put("color", null);
        McpContext c = ctx(args);
        assertThat(c.getEnum("color", Color.class)).isNull();
    }

    @Test
    void getEnum_invalidName_throwsIllegalArgument() {
        McpContext c = ctx(Map.of("color", "PURPLE"));
        assertThatThrownBy(() -> c.getEnum("color", Color.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getEnum_usesRawArgsNotMappings() {
        // getEnum reads args directly, not via resolve(), so mapping does NOT apply
        Map<String, Object> mapping = Map.of("red", "RED");
        McpContext c = ctx(Map.of("color", "RED"), "color", mapping);
        assertThat(c.getEnum("color", Color.class)).isEqualTo(Color.RED);
    }

    // -----------------------------------------------------------------------
    // resolve() edge cases via get()
    // -----------------------------------------------------------------------

    @Test
    void get_noMappingForParam_returnsOriginalValue() {
        // mapping exists for a different param, not for "x"
        Map<String, Object> mapping = Map.of("a", "1");
        McpContext c = ctx(Map.of("x", "hello"), "other", mapping);
        assertThat(c.get("x")).isEqualTo("hello");
    }

    @Test
    void get_emptyMappings_returnsOriginalValue() {
        McpContext c = new McpContext(Map.of("x", "val"), Map.of());
        assertThat(c.get("x")).isEqualTo("val");
    }

    @Test
    void get_multipleMappings_correctParamResolved() {
        Map<String, Map<String, Object>> mappings = new LinkedHashMap<>();
        mappings.put("city", Map.of("北京", "010", "上海", "021"));
        mappings.put("zone", Map.of("A", 1, "B", 2));
        McpContext c = new McpContext(Map.of("city", "上海", "zone", "A"), mappings);
        assertThat(c.get("city")).isEqualTo("021");
        assertThat(c.get("zone")).isEqualTo(1);
    }
}

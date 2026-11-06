package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpContextTest {

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static McpContext ctx(Map<String, Object> args) {
        return new McpContext(args);
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


    // -----------------------------------------------------------------------
    // resolve() edge cases via get()
    // -----------------------------------------------------------------------



}

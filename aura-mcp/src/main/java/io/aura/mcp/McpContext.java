package io.aura.mcp;

import io.aura.contract.ScalarType;

import java.util.LinkedHashMap;
import java.util.Map;

public class McpContext {

    private final Map<String, Object> args;
    private final String userId;

    McpContext(Map<String, Object> args) {
        this(args, null);
    }

    McpContext(Map<String, Object> args, String userId) {
        this.args = args;
        this.userId = userId;
    }

    /** Returns the identity resolved by the transport, or null for anonymous calls. */
    public String userId() { return userId; }

    public Object get(String name) {
        return args.get(name);
    }

    public String getString(String name) {
        Object val = get(name);
        return val == null ? null : val.toString();
    }

    /**
     * A tool argument arrives as JSON, so a number may already be a {@link Number} or may be
     * the string a client sent. Both shapes go through {@link ScalarType#fromString(String,
     * Class)} with the same target type the HTTP binder would use, which is what keeps a
     * malformed value from meaning 400 on one transport and {@code 0} on the other.
     */
    public int getInt(String name) {
        Object val = get(name);
        if (val instanceof Number n) return n.intValue();
        return (Integer) ScalarType.fromString(text(val), int.class);
    }

    public long getLong(String name) {
        Object val = get(name);
        if (val instanceof Number n) return n.longValue();
        return (Long) ScalarType.fromString(text(val), long.class);
    }

    /** A missing flag is {@code false}, the same default an HTTP query parameter gets. */
    public boolean getBoolean(String name) {
        Object val = get(name);
        if (val instanceof Boolean b) return b;
        return (Boolean) ScalarType.fromString(text(val), boolean.class);
    }

    public double getDouble(String name) {
        Object val = get(name);
        if (val instanceof Number n) return n.doubleValue();
        return (Double) ScalarType.fromString(text(val), double.class);
    }

    private static String text(Object val) {
        return val == null ? null : val.toString();
    }

    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> E getEnum(String name, Class<E> type) {
        Object val = args.get(name);
        if (val == null) return null;
        return Enum.valueOf(type, val.toString());
    }
}

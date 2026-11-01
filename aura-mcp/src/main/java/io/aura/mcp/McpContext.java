package io.aura.mcp;

import io.aura.web.ParamResolver;

import java.util.LinkedHashMap;
import java.util.Map;

public class McpContext {

    private final Map<String, Object> args;
    private final Map<String, Map<String, Object>> mappings;
    private final String userId;

    McpContext(Map<String, Object> args, Map<String, Map<String, Object>> mappings) {
        this(args, mappings, null);
    }

    McpContext(Map<String, Object> args, Map<String, Map<String, Object>> mappings, String userId) {
        this.args = args;
        this.mappings = mappings;
        this.userId = userId;
    }

    /** Returns the identity resolved by the transport, or null for anonymous calls. */
    public String userId() { return userId; }

    public Object get(String name) {
        Object val = args.get(name);
        return resolve(name, val);
    }

    public String getString(String name) {
        Object val = get(name);
        return val == null ? null : val.toString();
    }

    public int getInt(String name) {
        Object val = get(name);
        if (val instanceof Number n) return n.intValue();
        return ParamResolver.parseInt(val == null ? null : val.toString());
    }

    public long getLong(String name) {
        Object val = get(name);
        if (val instanceof Number n) return n.longValue();
        return ParamResolver.parseLong(val == null ? null : val.toString());
    }

    /** A missing flag is {@code false}, the same default an HTTP query parameter gets. */
    public boolean getBoolean(String name) {
        Object val = get(name);
        if (val instanceof Boolean b) return b;
        return ParamResolver.parseBool(val == null ? null : val.toString());
    }

    public double getDouble(String name) {
        Object val = get(name);
        if (val instanceof Number n) return n.doubleValue();
        return ParamResolver.parseDouble(val == null ? null : val.toString());
    }

    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> E getEnum(String name, Class<E> type) {
        Object val = args.get(name);
        if (val == null) return null;
        return Enum.valueOf(type, val.toString());
    }

    private Object resolve(String name, Object val) {
        if (val == null) return null;
        var mapping = mappings.get(name);
        if (mapping != null && mapping.containsKey(val.toString())) {
            return mapping.get(val.toString());
        }
        return val;
    }
}

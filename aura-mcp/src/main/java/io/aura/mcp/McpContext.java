package io.aura.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

public class McpContext {

    private final Map<String, Object> args;
    private final Map<String, Map<String, Object>> mappings;

    McpContext(Map<String, Object> args, Map<String, Map<String, Object>> mappings) {
        this.args = args;
        this.mappings = mappings;
    }

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
        return val == null ? 0 : Integer.parseInt(val.toString());
    }

    public long getLong(String name) {
        Object val = get(name);
        if (val instanceof Number n) return n.longValue();
        return val == null ? 0L : Long.parseLong(val.toString());
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

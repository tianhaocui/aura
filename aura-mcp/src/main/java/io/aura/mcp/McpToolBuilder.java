package io.aura.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpToolBuilder {

    private final String name;
    private final String description;
    private final List<McpParam> params = new ArrayList<>();
    private final Map<String, Map<String, Object>> mappings = new LinkedHashMap<>();
    private McpHandler handler;
    private final McpRouter router;

    McpToolBuilder(McpRouter router, String name, String description) {
        this.router = router;
        this.name = name;
        this.description = description;
    }

    public McpToolBuilder param(String name, Class<?> type, String description) {
        if (type.isEnum()) {
            return addEnumParam(name, type, description);
        }
        params.add(new McpParam(name, McpUtil.jsonType(type.getSimpleName()), description, null));
        return this;
    }

    public McpToolBuilder param(String name, Class<?> type, String description, Map<String, ?> mapping) {
        List<McpEnumValue> enumValues = new ArrayList<>();
        Map<String, Object> resolveMap = new LinkedHashMap<>();
        for (var entry : mapping.entrySet()) {
            enumValues.add(new McpEnumValue(entry.getKey(), entry.getValue()));
            resolveMap.put(entry.getKey(), entry.getValue());
        }
        mappings.put(name, resolveMap);
        params.add(new McpParam(name, McpUtil.jsonType(type.getSimpleName()), description, enumValues));
        return this;
    }

    public McpToolBuilder handler(McpHandler handler) {
        this.handler = handler;
        router.register(build());
        return this;
    }

    McpTool build() {
        return new McpTool(name, description, List.copyOf(params), handler);
    }

    Map<String, Map<String, Object>> mappings() {
        return mappings;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private McpToolBuilder addEnumParam(String name, Class<?> enumType, String description) {
        Object[] constants = enumType.getEnumConstants();
        List<McpEnumValue> enumValues = new ArrayList<>();
        for (Object c : constants) {
            String enumName = ((Enum<?>) c).name();
            String label = extractLabel(c);
            enumValues.add(new McpEnumValue(label != null ? enumName + "(" + label + ")" : enumName, enumName));
        }
        params.add(new McpParam(name, "string", description, enumValues));
        return this;
    }

    private static String extractLabel(Object enumConstant) {
        // prefer label() method (interface or duck-typed)
        try {
            var method = enumConstant.getClass().getMethod("label");
            return (String) method.invoke(enumConstant);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            return null;
        }
        // fallback to label field
        try {
            var field = enumConstant.getClass().getDeclaredField("label");
            field.setAccessible(true);
            return (String) field.get(enumConstant);
        } catch (Exception e) {
            return null;
        }
    }
}

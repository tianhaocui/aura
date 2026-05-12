package io.aura.mcp;

import java.util.*;

public final class McpUtil {

    public static String buildToolName(String method, String path) {
        String name = path.replaceAll("^/", "")
                .replaceAll("\\{[a-zA-Z_]+}", "by_id")
                .replaceAll("/+", "_");
        if (name.isEmpty()) name = "root";
        return method.toLowerCase() + "_" + name;
    }

    public static String jsonType(String javaType) {
        if (javaType == null) return "string";
        return switch (javaType) {
            case "int", "long", "Integer", "Long" -> "integer";
            case "double", "float", "Double", "Float", "BigDecimal" -> "number";
            case "boolean", "Boolean" -> "boolean";
            default -> "string";
        };
    }

    public static Map<String, Object> buildInputSchema(List<ParamInfo> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ParamInfo p : params) {
            var prop = new LinkedHashMap<String, Object>();
            prop.put("type", jsonType(p.type()));
            if (p.description() != null) prop.put("description", p.description());
            properties.put(p.name(), prop);
            required.add(p.name());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    public record ParamInfo(String name, String type, String description) {}

    private McpUtil() {}
}

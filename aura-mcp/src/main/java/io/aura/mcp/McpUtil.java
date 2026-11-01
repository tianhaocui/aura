package io.aura.mcp;

import io.aura.contract.ApiParameter;

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
            case "string", "integer", "number", "boolean", "object", "array" -> javaType;
            case "int", "long", "Integer", "Long" -> "integer";
            case "double", "float", "Double", "Float", "BigDecimal" -> "number";
            case "Boolean" -> "boolean";
            default -> "string";
        };
    }

    /**
     * The one {@code tools/list} {@code inputSchema} builder. Every MCP entry point —
     * the declarative builder, a shared route contract, and the HTTP bridge — funnels
     * through here, so a parameter can never render differently per path.
     *
     * <p>A parameter's declared JSON Schema wins; {@link #jsonType} is only the fallback
     * for parameters that never described a shape. Enum labels are what the model emits,
     * so they appear both in {@code enum} and inline in the description.
     */
    public static Map<String, Object> inputSchema(List<McpParam> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (McpParam param : params) {
            ApiParameter contract = param.contract();
            var prop = new LinkedHashMap<String, Object>();
            if (contract.schema().isEmpty()) {
                prop.put("type", jsonType(contract.type()));
            } else {
                prop.putAll(contract.schema());
            }
            String description = contract.description();
            if (param.isEnum()) {
                List<String> labels = param.enumValues().stream().map(McpEnumValue::label).toList();
                prop.put("enum", labels);
                if (description != null) {
                    description = description + ": " + String.join(", ", labels);
                }
            }
            if (description != null) prop.put("description", description);
            properties.put(contract.name(), prop);
            if (contract.required()) required.add(contract.name());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private McpUtil() {}
}

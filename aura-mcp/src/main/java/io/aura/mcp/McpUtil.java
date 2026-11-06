package io.aura.mcp;

import io.aura.contract.ApiParameter;
import io.aura.contract.ScalarType;

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
        String scalar = ScalarType.jsonTypeName(javaType);
        if (scalar != null) return scalar;
        return isWireName(javaType) ? javaType : "string";
    }

    /** A name that already speaks JSON, as opposed to a Java type the table has to translate. */
    public static boolean isWireName(String typeName) {
        return switch (typeName) {
            case "string", "integer", "number", "boolean", "object", "array" -> true;
            default -> false;
        };
    }

    /**
     * Whether {@link #jsonType} can give this type a JSON type on its own. A type it cannot —
     * a record, an enum, anything the table has no entry for — only looks like a {@code string}
     * on the wire, which is the lie this answers.
     */
    public static boolean rendersWithoutSchema(String javaType) {
        return javaType != null
                && (ScalarType.jsonTypeName(javaType) != null || isWireName(javaType));
    }

    /**
     * The one {@code tools/list} {@code inputSchema} builder. Both MCP entry points — the route
     * contract and the HTTP bridge — funnel through here, so a parameter can never render
     * differently per path.
     *
     * <p>A parameter's declared JSON Schema wins and {@link #jsonType} is only the fallback for
     * parameters that never described a shape. That is how an enum publishes its accepted values:
     * the contract's schema carries them, exactly as OpenAPI's does, and this renderer adds no
     * vocabulary of its own.
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

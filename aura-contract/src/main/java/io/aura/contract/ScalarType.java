package io.aura.contract;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one table that says which Java types are JSON scalars and what they render as.
 *
 * <p>HTTP binding, {@code /__schema__}, OpenAPI, MCP {@code inputSchema} and the
 * compile-time route generator all need this answer, and 1.0 kept a private copy of it
 * in each of them. The copies drifted: a {@code BigDecimal} or {@code enum} parameter was
 * a query string to the reflective binder and a JSON body to the generated route, so the
 * same service published two contradictory contracts. Lookups accept both the binary name
 * a {@link Class} reports and the simple name reflection summaries use.
 */
public final class ScalarType {

    private static final Map<String, String> BY_BINARY_NAME = byBinaryName();
    private static final Map<String, String> BY_SIMPLE_NAME = bySimpleName(BY_BINARY_NAME);

    /** Returns {@code "string" | "integer" | "number" | "boolean"}, or null when not scalar. */
    public static String jsonTypeName(String javaTypeName) {
        if (javaTypeName == null) return null;
        String name = javaTypeName.trim();
        String json = BY_BINARY_NAME.get(name);
        return json != null ? json : BY_SIMPLE_NAME.get(name);
    }

    public static boolean isScalar(String javaTypeName) {
        return jsonTypeName(javaTypeName) != null;
    }

    private static Map<String, String> byBinaryName() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("boolean", "boolean");
        types.put("java.lang.Boolean", "boolean");
        types.put("char", "string");
        types.put("java.lang.Character", "string");
        types.put("java.lang.String", "string");
        types.put("byte", "integer");
        types.put("java.lang.Byte", "integer");
        types.put("short", "integer");
        types.put("java.lang.Short", "integer");
        types.put("int", "integer");
        types.put("java.lang.Integer", "integer");
        types.put("long", "integer");
        types.put("java.lang.Long", "integer");
        types.put("float", "number");
        types.put("java.lang.Float", "number");
        types.put("double", "number");
        types.put("java.lang.Double", "number");
        types.put("java.math.BigDecimal", "number");
        types.put("java.math.BigInteger", "number");
        return Map.copyOf(types);
    }

    private static Map<String, String> bySimpleName(Map<String, String> binaryNames) {
        Map<String, String> simple = new LinkedHashMap<>();
        binaryNames.forEach((binary, json) -> {
            int lastDot = binary.lastIndexOf('.');
            simple.put(lastDot < 0 ? binary : binary.substring(lastDot + 1), json);
        });
        return Map.copyOf(simple);
    }

    private ScalarType() { }
}

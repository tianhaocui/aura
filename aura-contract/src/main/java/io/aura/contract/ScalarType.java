package io.aura.contract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The one table that says which Java types are JSON scalars, what they render as, and how a
 * string binds into them.
 *
 * <p>HTTP binding, {@code /__schema__}, OpenAPI, MCP {@code inputSchema} and the
 * compile-time route generator all need these answers, and 1.0 kept a private copy of them
 * in each of them. The copies drifted: a {@code BigDecimal} or {@code enum} parameter was
 * a query string to the reflective binder and a JSON body to the generated route, so the
 * same service published two contradictory contracts. Lookups accept both the binary name
 * a {@link Class} reports and the simple name reflection summaries use.
 *
 * <p>Keeping the rendering and the binding in one map is the point. A type that says
 * {@code integer} here is a type {@link #fromString(String, Class)} parses as an integer;
 * adding a scalar means answering both questions in the same place, which is the only way
 * the two transports cannot disagree again.
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

    /**
     * The one string-to-value table, and the mirror of {@link #jsonTypeName(String)}: the type
     * that renders as {@code integer} is the type that binds from an integer-looking string.
     *
     * <p>HTTP query/path parameters and MCP tool arguments both come through here, so a value
     * that binds to {@code short} on a request binds to {@code short} when the same handler is
     * invoked with a map, and a malformed value means the same failure on either transport.
     * The failure is {@link NumberFormatException} -- an {@link IllegalArgumentException} --
     * because the web layer's 400 mapping catches that, and a tool caller that historically saw
     * a parse failure keeps seeing one.
     *
     * <p>A missing value binds to the type's own absence: {@code 0} or {@code false} for a
     * primitive, {@code null} for a boxed type. Required-ness is decided by the caller, which
     * knows whether the handler signature can receive that absence.
     */
    public static Object fromString(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == int.class) return parseInt(value);
        if (type == Integer.class) return parseIntBoxed(value);
        if (type == long.class) return parseLong(value);
        if (type == Long.class) return parseLongBoxed(value);
        if (type == boolean.class) return parseBool(value);
        if (type == Boolean.class) return parseBoolBoxed(value);
        if (type == double.class) return parseDouble(value);
        if (type == Double.class) return parseDoubleBoxed(value);
        if (type == float.class) return parseFloat(value);
        if (type == Float.class) return parseFloatBoxed(value);
        if (type == short.class) return parseShort(value);
        if (type == Short.class) return parseShortBoxed(value);
        if (type == byte.class) return parseByte(value);
        if (type == Byte.class) return parseByteBoxed(value);
        if (type == char.class) return parseChar(value);
        if (type == Character.class) return parseCharBoxed(value);
        if (type == BigDecimal.class) return parseDecimal(value, BigDecimal::new);
        if (type == BigInteger.class) return parseDecimal(value, BigInteger::new);
        if (type.isEnum()) return parseEnum(type, value);
        return value;
    }

    /**
     * A body-versus-request-line rule with the same source as {@link #isScalar(String)}:
     * anything this table can spell as a JSON scalar -- including an enum, which arrives as
     * its {@code name()} -- binds from the request line; everything else arrives as JSON.
     * Schema consumers call this too, so a parameter cannot be a body to the binder and a
     * query string to the contract.
     */
    public static boolean isBody(Class<?> type) {
        return !isScalar(type.getName()) && !type.isEnum();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parseEnum(Class<?> type, String value) {
        if (value == null || value.isEmpty()) return null;
        return Enum.valueOf((Class) type, value);
    }

    private static Object parseDecimal(String value, Function<String, Object> parser) {
        if (value == null) return null;
        try {
            return parser.apply(value);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Invalid number: " + value);
        }
    }

    private static int parseInt(String val) {
        if (val == null) return 0;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid integer: " + val); }
    }

    private static Integer parseIntBoxed(String val) {
        if (val == null) return null;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid integer: " + val); }
    }

    private static long parseLong(String val) {
        if (val == null) return 0L;
        try { return Long.parseLong(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid long: " + val); }
    }

    private static Long parseLongBoxed(String val) {
        if (val == null) return null;
        try { return Long.parseLong(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid long: " + val); }
    }

    private static boolean parseBool(String val) {
        return "true".equalsIgnoreCase(val);
    }

    private static Boolean parseBoolBoxed(String val) {
        if (val == null) return null;
        return "true".equalsIgnoreCase(val);
    }

    private static double parseDouble(String val) {
        if (val == null) return 0.0;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid number: " + val); }
    }

    private static Double parseDoubleBoxed(String val) {
        if (val == null) return null;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid number: " + val); }
    }

    private static float parseFloat(String val) {
        if (val == null) return 0.0f;
        try { return Float.parseFloat(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid number: " + val); }
    }

    private static Float parseFloatBoxed(String val) {
        if (val == null) return null;
        try { return Float.parseFloat(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid number: " + val); }
    }

    private static short parseShort(String val) {
        if (val == null) return 0;
        try { return Short.parseShort(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid short: " + val); }
    }

    private static Short parseShortBoxed(String val) {
        if (val == null) return null;
        try { return Short.parseShort(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid short: " + val); }
    }

    private static byte parseByte(String val) {
        if (val == null) return 0;
        try { return Byte.parseByte(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid byte: " + val); }
    }

    private static Byte parseByteBoxed(String val) {
        if (val == null) return null;
        try { return Byte.parseByte(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid byte: " + val); }
    }

    private static char parseChar(String val) {
        if (val == null || val.isEmpty()) return '\0';
        return val.charAt(0);
    }

    private static Character parseCharBoxed(String val) {
        if (val == null || val.isEmpty()) return null;
        return val.charAt(0);
    }

    private ScalarType() { }
}

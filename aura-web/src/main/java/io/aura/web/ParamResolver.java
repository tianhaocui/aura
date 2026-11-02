package io.aura.web;

import
io.aura.port.BaseContext;
import io.aura.BeanValidator;
import io.aura.contract.ScalarType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.reflect.Parameter;

public final class ParamResolver {

    /**
     * The one string→value binder: HTTP query/path parameters and MCP tool arguments
     * both go through these parse methods, so a malformed value cannot mean 400 on one
     * transport and a silent 0 on the other.
     *
     * <p>Failures throw {@link NumberFormatException}, which is an
     * {@link IllegalArgumentException}: the web layer's 400 mapping still catches it and
     * MCP callers that historically saw a parse failure keep seeing one.
     */

    @FunctionalInterface
    public interface Binder {
        Object resolve(BaseContext ctx) throws Exception;
    }

    public static Binder create(Parameter param) {
        Class<?> type = param.getType();
        String name = param.getName();

        if (type == Context.class || type == BaseContext.class) return ctx -> ctx;

        if (name.matches("arg\\d+")) {
            throw new IllegalStateException(
                "Missing -parameters compiler flag. Add <parameters>true</parameters> to maven-compiler-plugin. " +
                "Without it, all route parameters receive null/0.");
        }

        if (isBody(type)) return ctx -> {
            Object obj = ctx.body(parameterType(param));
            if (obj != null) BeanValidator.validate(obj);
            return obj;
        };

        return ctx -> {
            String raw = resolveString(ctx, name);
            if (raw == null && type.isPrimitive()) throw new RequiredParameterException(name);
            return fromString(raw, type);
        };
    }

    /**
     * The one required-ness rule: a parameter the handler signature cannot receive as null is
     * required, and one it can is not.
     *
     * <p>A primitive cannot hold null, so absence is a client error and the binder answers 400.
     * A path segment is required because the route only matched if it was there. A POJO, a
     * collection or a boxed scalar can be null, and a handler that says {@code if (items == null)}
     * meant it -- so the schema does not promise a rejection the server does not make.
     *
     * <p>{@code /__schema__}, OpenAPI and the MCP bridge read this through
     * {@link RouteCatalog#describe}; {@link #create(Parameter)} enforces exactly it, so a declared
     * required parameter is one the server actually refuses to see missing.
     */
    public static boolean isRequired(Class<?> type, String source) {
        return type.isPrimitive() || "path".equals(source);
    }

    /**
     * The one string-to-value table. HTTP binding and argument replay both come through
     * here, so a value that binds to {@code short} on a request also binds to
     * {@code short} when the same handler is invoked with a map.
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
     * The one body-versus-path/query rule. Everything the HTTP layer can spell as a
     * scalar — including enums, which arrive as their {@code name()} — is bound from the
     * request line; anything else arrives as JSON. Schema consumers call this too, so a
     * parameter can no longer be a body to the binder and a query string to the contract.
     */
    public static boolean isBody(Class<?> type) {
        return !ScalarType.isScalar(type.getName()) && !type.isEnum();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object parseEnum(Class<?> type, String value) {
        if (value == null || value.isEmpty()) return null;
        return Enum.valueOf((Class) type, value);
    }

    static Object parseDecimal(String value, java.util.function.Function<String, Object> parser) {
        if (value == null) return null;
        try {
            return parser.apply(value);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Invalid number: " + value);
        }
    }

    private static java.lang.reflect.Type parameterType(Parameter param) {
        return param.getParameterizedType();
    }

    public static String resolveString(BaseContext ctx, String name) {
        String val = ctx.path(name);
        return val != null ? val : ctx.query(name);
    }

    public static int parseInt(String val) {
        if (val == null) return 0;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid integer: " + val); }
    }

    public static Integer parseIntBoxed(String val) {
        if (val == null) return null;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid integer: " + val); }
    }

    public static long parseLong(String val) {
        if (val == null) return 0L;
        try { return Long.parseLong(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid long: " + val); }
    }

    public static Long parseLongBoxed(String val) {
        if (val == null) return null;
        try { return Long.parseLong(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid long: " + val); }
    }

    public static boolean parseBool(String val) {
        return "true".equalsIgnoreCase(val);
    }

    public static Boolean parseBoolBoxed(String val) {
        if (val == null) return null;
        return "true".equalsIgnoreCase(val);
    }

    public static double parseDouble(String val) {
        if (val == null) return 0.0;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid number: " + val); }
    }

    public static Double parseDoubleBoxed(String val) {
        if (val == null) return null;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid number: " + val); }
    }

    public static float parseFloat(String val) {
        if (val == null) return 0.0f;
        try { return Float.parseFloat(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid number: " + val); }
    }

    public static Float parseFloatBoxed(String val) {
        if (val == null) return null;
        try { return Float.parseFloat(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid number: " + val); }
    }

    public static short parseShort(String val) {
        if (val == null) return 0;
        try { return Short.parseShort(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid short: " + val); }
    }

    public static Short parseShortBoxed(String val) {
        if (val == null) return null;
        try { return Short.parseShort(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid short: " + val); }
    }

    public static byte parseByte(String val) {
        if (val == null) return 0;
        try { return Byte.parseByte(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid byte: " + val); }
    }

    public static Byte parseByteBoxed(String val) {
        if (val == null) return null;
        try { return Byte.parseByte(val); }
        catch (NumberFormatException e) { throw new NumberFormatException("Invalid byte: " + val); }
    }

    public static char parseChar(String val) {
        if (val == null || val.isEmpty()) return '\0';
        return val.charAt(0);
    }

    public static Character parseCharBoxed(String val) {
        if (val == null || val.isEmpty()) return null;
        return val.charAt(0);
    }

    private ParamResolver() {}
}

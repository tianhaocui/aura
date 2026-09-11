package io.aura.web;

import io.aura.BeanValidator;

import java.lang.reflect.Parameter;

public final class ParamResolver {

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

        if (type == String.class) return ctx -> resolveString(ctx, name);
        if (type == int.class) return ctx -> parseInt(resolveString(ctx, name));
        if (type == Integer.class) return ctx -> parseIntBoxed(resolveString(ctx, name));
        if (type == long.class) return ctx -> parseLong(resolveString(ctx, name));
        if (type == Long.class) return ctx -> parseLongBoxed(resolveString(ctx, name));
        if (type == boolean.class) return ctx -> parseBool(resolveString(ctx, name));
        if (type == Boolean.class) return ctx -> parseBoolBoxed(resolveString(ctx, name));
        if (type == double.class) return ctx -> parseDouble(resolveString(ctx, name));
        if (type == Double.class) return ctx -> parseDoubleBoxed(resolveString(ctx, name));
        if (type == float.class) return ctx -> parseFloat(resolveString(ctx, name));
        if (type == Float.class) return ctx -> parseFloatBoxed(resolveString(ctx, name));
        if (type == short.class) return ctx -> parseShort(resolveString(ctx, name));
        if (type == Short.class) return ctx -> parseShortBoxed(resolveString(ctx, name));
        if (type == byte.class) return ctx -> parseByte(resolveString(ctx, name));
        if (type == Byte.class) return ctx -> parseByteBoxed(resolveString(ctx, name));
        if (type == char.class) return ctx -> parseChar(resolveString(ctx, name));
        if (type == Character.class) return ctx -> parseCharBoxed(resolveString(ctx, name));

        if (type.isRecord() || TypeUtil.isPojo(type)) return ctx -> {
            Object obj = ctx.body(type);
            BeanValidator.validate(obj);
            return obj;
        };

        return ctx -> resolveString(ctx, name);
    }

    public static String resolveString(BaseContext ctx, String name) {
        String val = ctx.path(name);
        return val != null ? val : ctx.query(name);
    }

    public static int parseInt(String val) {
        if (val == null) return 0;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid integer: " + val); }
    }

    public static Integer parseIntBoxed(String val) {
        if (val == null) return null;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid integer: " + val); }
    }

    public static long parseLong(String val) {
        if (val == null) return 0L;
        try { return Long.parseLong(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid long: " + val); }
    }

    public static Long parseLongBoxed(String val) {
        if (val == null) return null;
        try { return Long.parseLong(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid long: " + val); }
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
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid number: " + val); }
    }

    public static Double parseDoubleBoxed(String val) {
        if (val == null) return null;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid number: " + val); }
    }

    public static float parseFloat(String val) {
        if (val == null) return 0.0f;
        try { return Float.parseFloat(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid number: " + val); }
    }

    public static Float parseFloatBoxed(String val) {
        if (val == null) return null;
        try { return Float.parseFloat(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid number: " + val); }
    }

    public static short parseShort(String val) {
        if (val == null) return 0;
        try { return Short.parseShort(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid short: " + val); }
    }

    public static Short parseShortBoxed(String val) {
        if (val == null) return null;
        try { return Short.parseShort(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid short: " + val); }
    }

    public static byte parseByte(String val) {
        if (val == null) return 0;
        try { return Byte.parseByte(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid byte: " + val); }
    }

    public static Byte parseByteBoxed(String val) {
        if (val == null) return null;
        try { return Byte.parseByte(val); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid byte: " + val); }
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

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

    private ParamResolver() {}
}

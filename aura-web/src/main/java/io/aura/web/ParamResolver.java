package io.aura.web;

import io.aura.port.BaseContext;
import io.aura.BeanValidator;
import io.aura.contract.ScalarType;

import java.lang.reflect.Parameter;

public final class ParamResolver {

    /**
     * The web-side half of the binder: it pulls a raw value out of a request and hands it to
     * {@link ScalarType#fromString(String, Class)}, where the string-to-value table lives. MCP
     * reads the same table from its own argument map, so a malformed value cannot mean 400 on
     * one transport and a silent 0 on the other.
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

        if (ScalarType.isBody(type)) return ctx -> {
            Object obj = ctx.body(parameterType(param));
            if (obj != null) BeanValidator.validate(obj);
            return obj;
        };

        return ctx -> {
            String raw = resolveString(ctx, name);
            if (raw == null && type.isPrimitive()) throw new RequiredParameterException(name);
            return ScalarType.fromString(raw, type);
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
     * Path first, then query -- the precedence the router matched the request with, so a route
     * template and a query string cannot disagree about which one a name refers to.
     */
    public static String resolveString(BaseContext ctx, String name) {
        String val = ctx.path(name);
        return val != null ? val : ctx.query(name);
    }

    private static java.lang.reflect.Type parameterType(Parameter param) {
        return param.getParameterizedType();
    }

    private ParamResolver() {}
}

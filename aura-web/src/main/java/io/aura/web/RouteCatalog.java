package io.aura.web;

import io.aura.port.BaseContext;
import io.aura.contract.ApiParameter;
import io.aura.contract.Route;
import io.aura.contract.ScalarType;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Completes a compiled route's declared facts so every schema adapter reads one record. */
public final class RouteCatalog {
    private RouteCatalog() { }

    public static Route describe(CompiledRoute route) {
        Route contract = route.contract();
        List<ApiParameter> parameters = new ArrayList<>(contract.params());
        if (parameters.isEmpty()) {
            if (route.handler() instanceof MethodRefHandler methodHandler) {
                Method method = methodHandler.resolvedMethod();
                for (Parameter parameter : method.getParameters()) {
                    Class<?> type = parameter.getType();
                    if (type == Context.class || type == BaseContext.class) continue;
                    // The same rule the binder uses, so the described location is the
                    // location the value is actually read from.
                    boolean body = ScalarType.isBody(type);
                    String source = body ? "body"
                            : route.paramNames().contains(parameter.getName()) ? "path" : "query";
                    // The binder enforces this same rule, so a declared required parameter is
                    // one the server actually refuses to see missing.
                    boolean required = ParamResolver.isRequired(type, source);
                    // An enum is scalar enough to bind from the request line but still needs
                    // its accepted values spelled out for the model.
                    Map<String, Object> schema = body || type.isEnum()
                            ? TypeSchema.describe(type) : Map.of();
                    parameters.add(new ApiParameter(parameter.getName(), type.getSimpleName(), source,
                            required, route.paramDescriptions().get(parameter.getName()), schema));
                }
            } else {
                for (String name : route.paramNames()) {
                    parameters.add(new ApiParameter(name, "String", "path", true,
                            route.paramDescriptions().get(name)));
                }
            }
        }
        return contract.withPath(route.rawPath())
                .withParams(parameters)
                .withResponseSchema(responseSchema(route, contract));
    }

    /**
     * The path placeholders a WebSocket endpoint publishes. A handshake carries no method
     * signature to read, but a schema that shows {@code {roomId}} without naming it leaves a
     * consumer guessing what to put in the URL.
     */
    public static Route describeWebSocket(Route route) {
        if (!route.params().isEmpty()) return route;
        List<ApiParameter> parameters = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}").matcher(route.path());
        while (matcher.find()) {
            parameters.add(new ApiParameter(matcher.group(1), "String", "path", true, null));
        }
        return route.withParams(parameters);
    }

    private static Map<String, Object> responseSchema(CompiledRoute route, Route contract) {
        if (!contract.responseSchema().isEmpty()) return contract.responseSchema();
        if (!(route.handler() instanceof MethodRefHandler methodHandler)) return Map.of();
        Method method = methodHandler.resolvedMethod();
        if (method.getReturnType() == void.class || method.getReturnType() == Void.class
                || method.getReturnType() == Context.class || method.getReturnType() == BaseContext.class) {
            return Map.of();
        }
        return TypeSchema.describe(method.getGenericReturnType());
    }
}

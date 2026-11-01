package io.aura.web;

import io.aura.contract.ApiParameter;
import io.aura.contract.ApiRoute;
import io.aura.contract.Route;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts compiled routes into the shared contract consumed by schema adapters. */
public final class ApiRouteCatalog {
    private ApiRouteCatalog() { }

    public static ApiRoute describe(CompiledRoute route, String example) {
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
                    boolean body = ParamResolver.isBody(type);
                    String source = body ? "body"
                            : route.paramNames().contains(parameter.getName()) ? "path" : "query";
                    boolean required = body || type.isPrimitive() || "path".equals(source);
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
        return new ApiRoute(route.method(), route.rawPath(), contract.description(), contract.returnType(),
                parameters, example, responseSchema(route, contract),
                contract.authenticated(), contract.permission());
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

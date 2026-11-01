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
                    if (parameter.getType() == Context.class || parameter.getType() == BaseContext.class) continue;
                    String source = parameter.getType().isRecord() || TypeUtil.isPojo(parameter.getType())
                            ? "body" : route.paramNames().contains(parameter.getName()) ? "path" : "query";
                    boolean required = parameter.getType().isPrimitive() || "path".equals(source);
                    parameters.add(new ApiParameter(parameter.getName(), parameter.getType().getSimpleName(), source,
                            required, route.paramDescriptions().get(parameter.getName()),
                            "body".equals(source) ? TypeSchema.describe(parameter.getType()) : Map.of()));
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

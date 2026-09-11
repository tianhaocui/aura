package io.aura.web;

import io.aura.contract.ApiParameter;
import io.aura.contract.ApiRoute;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts compiled routes into the shared contract consumed by schema adapters. */
public final class ApiRouteCatalog {
    private ApiRouteCatalog() { }

    public static ApiRoute describe(CompiledRoute route, String example) {
        List<ApiParameter> parameters = new ArrayList<>();
        Map<String, String> metadata = route.meta() == null ? Map.of() : route.meta().params();
        if (route.meta() != null && !route.meta().parameters().isEmpty()) {
            parameters.addAll(route.meta().parameters());
        } else if (route.handler() instanceof MethodRefHandler methodHandler) {
            Method method = methodHandler.resolvedMethod();
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getType() == Context.class || parameter.getType() == BaseContext.class) continue;
                String source = parameter.getType().isRecord() || TypeUtil.isPojo(parameter.getType())
                        ? "body" : route.paramNames().contains(parameter.getName()) ? "path" : "query";
                boolean required = parameter.getType().isPrimitive() || "path".equals(source);
                parameters.add(new ApiParameter(parameter.getName(), parameter.getType().getSimpleName(), source,
                        required, metadata.get(parameter.getName()),
                        "body".equals(source) ? TypeSchema.describe(parameter.getType()) : Map.of()));
            }
        } else {
            for (String name : route.paramNames()) {
                parameters.add(new ApiParameter(name, "String", "path", true, metadata.get(name)));
            }
        }
        Map<String, Object> responseSchema = responseSchema(route);
        return new ApiRoute(route.method(), route.rawPath(),
                route.meta() == null ? null : route.meta().description(),
                route.meta() == null ? null : route.meta().returnType(), parameters, example, responseSchema);
    }

    private static Map<String, Object> responseSchema(CompiledRoute route) {
        if (route.meta() != null && !route.meta().responseSchema().isEmpty()) {
            return route.meta().responseSchema();
        }
        if (!(route.handler() instanceof MethodRefHandler methodHandler)) return Map.of();
        Method method = methodHandler.resolvedMethod();
        if (method.getReturnType() == void.class || method.getReturnType() == Void.class
                || method.getReturnType() == Context.class || method.getReturnType() == BaseContext.class) {
            return Map.of();
        }
        return TypeSchema.describe(method.getGenericReturnType());
    }
}

package io.aura.web;

import io.aura.contract.ApiParameter;
import io.aura.contract.ApiRoute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free OpenAPI 3 document generator backed by the shared API contract. */
public final class OpenApiGenerator {
    private OpenApiGenerator() { }

    public static Map<String, Object> generate(String title, String version, List<ApiRoute> routes) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title == null || title.isBlank() ? "Aura API" : title);
        info.put("version", version == null || version.isBlank() ? "1.0.0" : version);

        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
        if (routes != null) for (ApiRoute route : routes) {
            Map<String, Object> operation = new LinkedHashMap<>();
            if (route.description() != null) operation.put("description", route.description());
            operation.put("operationId", operationId(route));
            if (route.authenticated()) operation.put("x-aura-authenticated", true);
            if (route.permission() != null) operation.put("x-aura-permission", route.permission());
            List<Map<String, Object>> parameters = new ArrayList<>();
            List<ApiParameter> body = new ArrayList<>();
            for (ApiParameter parameter : route.params()) {
                if ("body".equals(parameter.source())) body.add(parameter);
                else {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("name", parameter.name());
                    value.put("in", parameter.source());
                    value.put("required", parameter.required());
                    value.put("schema", Map.of("type", openApiType(parameter.type())));
                    if (parameter.description() != null) value.put("description", parameter.description());
                    parameters.add(value);
                }
            }
            if (!parameters.isEmpty()) operation.put("parameters", parameters);
            if (!body.isEmpty()) {
                ApiParameter parameter = body.get(0);
                Map<String, Object> bodySchema = parameter.schema().isEmpty()
                        ? Map.of("type", "object") : parameter.schema();
                operation.put("requestBody", Map.of("required", parameter.required(),
                        "content", Map.of("application/json", Map.of("schema", bodySchema))));
            }
            Map<String, Object> success = new LinkedHashMap<>();
            success.put("description", "Success");
            if (!route.responseSchema().isEmpty()) {
                success.put("content", Map.of("application/json",
                        Map.of("schema", route.responseSchema())));
            }
            operation.put("responses", Map.of("200", success));
            paths.computeIfAbsent(route.path(), ignored -> new LinkedHashMap<>())
                    .put(route.method().toLowerCase(java.util.Locale.ROOT), operation);
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("openapi", "3.0.3");
        document.put("info", info);
        document.put("paths", paths);
        return document;
    }

    private static String operationId(ApiRoute route) {
        return route.method().toLowerCase(java.util.Locale.ROOT) + "_" + route.path()
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String openApiType(String type) {
        if (type == null) return "string";
        return switch (type) {
            case "int", "Integer", "long", "Long" -> "integer";
            case "double", "Double", "float", "Float" -> "number";
            case "boolean", "Boolean" -> "boolean";
            default -> "string";
        };
    }
}

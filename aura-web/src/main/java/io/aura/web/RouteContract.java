package io.aura.web;

import io.aura.AuraWebRuntime;
import io.aura.contract.ApiParameter;
import io.aura.contract.ApiRoute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single source for the route descriptions exposed by Web adapters. */
final class RouteContract {
    private RouteContract() { }

    static List<ApiRoute> describe(List<CompiledRoute> routes, AuraWebRuntime app) {
        List<ApiRoute> result = new ArrayList<>();
        for (CompiledRoute route : routes) result.add(describe(route, app));
        return List.copyOf(result);
    }

    static ApiRoute describe(CompiledRoute route, AuraWebRuntime app) {
        ApiRoute description = ApiRouteCatalog.describe(route, null);
        return new ApiRoute(description.method(), description.path(), description.description(),
                description.returnType(), description.params(), buildCurlExample(route, description.params(), app));
    }

    static Map<String, Object> schema(AuraWebRuntime app, List<CompiledRoute> routes) {
        Map<String, Object> schema = new LinkedHashMap<>();
        String name = app.prop("app.name");
        schema.put("name", name == null ? "Aura App" : name);
        schema.put("routes", describe(routes, app));
        return schema;
    }

    static Map<String, Object> openApi(AuraWebRuntime app, List<CompiledRoute> routes) {
        String name = app.prop("app.name");
        return OpenApiGenerator.generate(name == null ? "Aura API" : name, "1.0.0", describe(routes, app));
    }

    private static String buildCurlExample(CompiledRoute route, List<ApiParameter> params, AuraWebRuntime app) {
        String base = "http://localhost:" + app.port();
        String path = route.rawPath();
        for (ApiParameter parameter : params) {
            if ("path".equals(parameter.source())) {
                path = path.replace("{" + parameter.name() + "}", exampleValue(parameter.type()));
            }
        }
        if ("GET".equals(route.method())) {
            List<String> queryParts = new ArrayList<>();
            for (ApiParameter parameter : params) {
                if ("query".equals(parameter.source())) {
                    queryParts.add(parameter.name() + "=" + exampleValue(parameter.type()));
                }
            }
            if (!queryParts.isEmpty()) path += "?" + String.join("&", queryParts);
        }
        StringBuilder curl = new StringBuilder("curl");
        if (!"GET".equals(route.method())) curl.append(" -X ").append(route.method());
        curl.append(" ").append(base).append(path);
        if ("POST".equals(route.method()) || "PUT".equals(route.method())) {
            for (ApiParameter parameter : params) {
                if ("body".equals(parameter.source())) {
                    curl.append(" -H 'Content-Type: application/json' -d '{}'");
                    break;
                }
            }
        }
        return curl.toString();
    }

    private static String exampleValue(String type) {
        if (type == null) return "value";
        return switch (type) {
            case "int", "Integer", "long", "Long" -> "1";
            case "boolean", "Boolean" -> "true";
            case "double", "Double" -> "1.0";
            default -> "example";
        };
    }
}

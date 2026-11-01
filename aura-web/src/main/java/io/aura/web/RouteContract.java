package io.aura.web;

import io.aura.AuraWebRuntime;
import io.aura.contract.ApiParameter;
import io.aura.contract.Route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders the {@code /__schema__} document from the route contract. */
final class RouteContract {
    private RouteContract() { }

    static List<Route> describe(List<CompiledRoute> routes) {
        List<Route> result = new ArrayList<>();
        for (CompiledRoute route : routes) result.add(RouteCatalog.describe(route));
        return List.copyOf(result);
    }

    static Map<String, Object> schema(AuraWebRuntime app, List<CompiledRoute> routes,
                                      List<Route> webSockets) {
        Map<String, Object> schema = new LinkedHashMap<>();
        String name = app.prop("app.name");
        schema.put("name", name == null ? "Aura App" : name);
        List<Map<String, Object>> views = new ArrayList<>();
        for (Route route : describe(routes)) views.add(view(route, app));
        // WebSocket endpoints are published because a consumer has to know they exist; they are
        // published as streaming routes so a request/response consumer declines them by itself.
        for (Route route : webSockets) views.add(view(route, app));
        schema.put("routes", views);
        return schema;
    }

    static Map<String, Object> openApi(AuraWebRuntime app, List<CompiledRoute> routes,
                                       List<Route> webSockets) {
        String name = app.prop("app.name");
        List<Route> all = new ArrayList<>(describe(routes));
        all.addAll(webSockets);
        // The whole endpoint set goes in: the generator decides what it can document, rather than
        // being handed a pre-filtered list that hides the decision.
        return OpenApiGenerator.generate(name == null ? "Aura API" : name, "1.0.0", all);
    }

    /**
     * Explicit field order instead of a second route record: the schema document is
     * a wire format, and absent metadata stays absent rather than becoming a
     * {@code null} the reading tools have to special-case.
     */
    private static Map<String, Object> view(Route route, AuraWebRuntime app) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("method", route.method());
        view.put("path", route.path());
        if (route.description() != null) view.put("description", route.description());
        if (route.returnType() != null) view.put("returnType", route.returnType());
        view.put("params", route.params());
        view.put("example", curlExample(route, app));
        view.put("responseSchema", route.responseSchema());
        view.put("authenticated", route.authenticated());
        if (route.permission() != null) view.put("permission", route.permission());
        // A plain route says nothing about streaming; a streaming one has to, because that
        // is the fact that makes it unreadable for a request/response consumer.
        if (route.streaming()) view.put("streaming", true);
        return view;
    }

    private static String curlExample(Route route, AuraWebRuntime app) {
        String base = "http://localhost:" + app.port();
        String path = route.path();
        for (ApiParameter parameter : route.params()) {
            if ("path".equals(parameter.source())) {
                path = path.replace("{" + parameter.name() + "}", exampleValue(parameter.type()));
            }
        }
        if (route.isWebSocket()) {
            // The only honest example: the handshake is a GET that has to ask to be upgraded.
            return "curl -i -N " + base + path
                    + " -H 'Connection: Upgrade' -H 'Upgrade: websocket'"
                    + " -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='";
        }
        if ("GET".equals(route.method())) {
            List<String> queryParts = new ArrayList<>();
            for (ApiParameter parameter : route.params()) {
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
            for (ApiParameter parameter : route.params()) {
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

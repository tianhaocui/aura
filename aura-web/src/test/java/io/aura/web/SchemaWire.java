package io.aura.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * Reads Aura's own endpoint documents the way a consumer does: parsed JSON and typed getters.
 *
 * <p>The documents are maps by nature, so a test that reaches into them has to choose how it
 * fails when a key moves. An unchecked cast to {@code List<Map<String,Object>>} fails quietly --
 * a renamed key yields a null that a later {@code containsEntry} still passes -- while
 * {@code document.toString().contains("...")} passes for text that has nothing to do with the
 * field it names. These getters miss the key, not the cast, and every lookup says which route or
 * parameter it wanted.
 *
 * <p>The projection is read from {@link TestClient#schema()} rather than fetched over the guard
 * chain, because admission for those endpoints is the chain's decision and is covered by
 * {@code GuardChainTest} and {@code DeploymentSecurityTest}; what is left to prove here is the
 * document's own content.
 */
final class SchemaWire {
    private SchemaWire() { }

    /** Any document Aura builds -- the schema projection or a generated OpenAPI tree -- as JSON. */
    static JSONObject document(java.util.Map<String, Object> raw) {
        return JSON.parseObject(JSON.toJSONString(raw));
    }

    static JSONObject schema(TestClient client) {
        return document(client.schema());
    }

    static JSONObject openApi(TestClient client) {
        return document(client.openApi());
    }

    /** The route's entry in the schema document. */
    static JSONObject route(TestClient client, String path) {
        JSONArray routes = schema(client).getJSONArray("routes");
        for (int i = 0; i < routes.size(); i++) {
            JSONObject route = routes.getJSONObject(i);
            if (path.equals(route.getString("path"))) return route;
        }
        throw new AssertionError(path + " never appeared in /__schema__: " + routes);
    }

    static JSONObject parameter(JSONObject route, String name) {
        return parameter(route.getJSONArray("params"), name);
    }

    /** One entry of a published parameter list, from either document -- the route's or OpenAPI's. */
    static JSONObject parameter(JSONArray declared, String name) {
        for (int i = 0; declared != null && i < declared.size(); i++) {
            JSONObject parameter = declared.getJSONObject(i);
            if (name.equals(parameter.getString("name"))) return parameter;
        }
        throw new AssertionError(name + " never appeared in " + declared);
    }

    /** The OpenAPI operation for a route, named in the failure when the document does not describe it. */
    static JSONObject operation(TestClient client, String path, String method) {
        return operation(openApi(client), path, method);
    }

    static JSONObject operation(JSONObject document, String path, String method) {
        JSONObject paths = document.getJSONObject("paths");
        if (paths == null) throw new AssertionError("the document has no paths: " + document.keySet());
        JSONObject pathItem = paths.getJSONObject(path);
        if (pathItem == null) {
            throw new AssertionError(path + " never appeared in the OpenAPI paths: " + paths.keySet());
        }
        JSONObject operation = pathItem.getJSONObject(method.toLowerCase(java.util.Locale.ROOT));
        if (operation == null) {
            throw new AssertionError(method + " " + path + " never appeared in the OpenAPI document: " + pathItem);
        }
        return operation;
    }

    /** The parameters OpenAPI lists for a route, which never include a request body. */
    static JSONArray queryParameters(TestClient client, String path, String method) {
        JSONArray listed = new JSONArray();
        JSONArray parameters = operation(client, path, method).getJSONArray("parameters");
        for (int i = 0; parameters != null && i < parameters.size(); i++) {
            JSONObject parameter = parameters.getJSONObject(i);
            String in = parameter.getString("in");
            if (!"body".equals(in)) listed.add(parameter);
        }
        return listed;
    }
}

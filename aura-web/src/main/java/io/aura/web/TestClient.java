package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;
import io.aura.AuraApp;
import io.aura.AuraRuntime;
import io.aura.AuraWebRuntime;
import io.aura.snapshot.Snapshot;
import io.aura.snapshot.SnapshotScope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestClient {

    private final AuraWebRuntime app;
    private final Router router;
    private final List<CompiledRoute> compiled;

    TestClient(AuraWebRuntime app, Router router) {
        this.app = app;
        this.router = router;
        this.compiled = UndertowStarter.compileRoutes(router, app);
        UndertowStarter.detectDuplicateRoutes(compiled);
    }

    public static TestClient of(Aura app) {
        return of((AuraWebRuntime) app);
    }

    private static TestClient of(AuraWebRuntime app) {
        Router router = RouteAssembler.assemble(app);
        return new TestClient(app, router);
    }

    /** Creates the in-memory client from the stable runtime seam. */
    public static TestClient of(AuraRuntime runtime) {
        if (runtime == null) throw new IllegalArgumentException("runtime must not be null");
        if (runtime instanceof AuraWebRuntime app) return of(app);
        throw new IllegalArgumentException("TestClient requires a runtime with an Aura Web adapter");
    }

    /** Standard 1.0 verification entry point for explicitly composed applications. */
    public static TestClient of(AuraApp app) {
        if (app == null) throw new IllegalArgumentException("app must not be null");
        return of(app.runtimeView());
    }

    public Request get(String path) { return new Request("GET", path); }
    public Request post(String path) { return new Request("POST", path); }
    public Request put(String path) { return new Request("PUT", path); }
    public Request delete(String path) { return new Request("DELETE", path); }
    public Request patch(String path) { return new Request("PATCH", path); }
    public Request head(String path) { return new Request("HEAD", path); }
    public Request options(String path) { return new Request("OPTIONS", path); }

    /** Returns the same route schema served by the real Web adapter. */
    public Map<String, Object> schema() {
        return RouteContract.schema(app, compiled);
    }

    /** Returns the same OpenAPI document served by the real Web adapter. */
    public Map<String, Object> openApi() {
        return RouteContract.openApi(app, compiled);
    }

    public class Request {
        private final String method;
        private String path;
        private final Map<String, String> headers = new HashMap<>();
        private String body;

        Request(String method, String path) {
            this.method = method;
            this.path = path;
        }

        public Request header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Request query(String name, String value) {
            String separator = path.contains("?") ? "&" : "?";
            path = path + separator + name + "=" + value;
            return this;
        }

        public Request body(Object obj) {
            this.body = obj instanceof String s ? s : JSON.toJSONString(obj);
            headers.putIfAbsent("Content-Type", "application/json");
            return this;
        }

        public Response execute() {
            String routePath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            Map<String, String> queryParams = parseQueryString(path);
            SnapshotScope snapshotScope = Snapshot.currentOrNull();
            boolean snapshotBound = false;
            if (snapshotScope == null && SnapshotRecorder.shouldCapture(app)) {
                snapshotScope = SnapshotScope.capture();
                Snapshot.bind(snapshotScope);
                snapshotBound = true;
            }
            final SnapshotScope activeSnapshotScope = snapshotScope;

            try {
                for (var route : compiled) {
                    if (!route.method().equals(method)) continue;
                    Map<String, String> params = route.match(routePath);
                    if (params == null) continue;

                    var mockCtx = new MockContext(params, queryParams, headers, body, app, routePath);
                    final Exception[] failure = new Exception[1];
                    RouteExecutor.execute(route, mockCtx, (e, c) -> {
                        failure[0] = e;
                        handleException(e, (MockContext) c);
                    });
                    if (failure[0] != null && activeSnapshotScope != null && activeSnapshotScope.mode() == SnapshotScope.Mode.CAPTURE) {
                        SnapshotRecorder.of(app).record(failure[0], route, mockCtx, method, routePath,
                                queryParams, headers, body, activeSnapshotScope);
                    }
                    return new Response(mockCtx.status == 0 ? 200 : mockCtx.status, mockCtx.responseBody, mockCtx.responseHeaders());
                }

                // HEAD auto-fallback: use GET handler, suppress body
                if ("HEAD".equals(method)) {
                    for (var route : compiled) {
                        if (!"GET".equals(route.method())) continue;
                        Map<String, String> params = route.match(routePath);
                        if (params == null) continue;
                        var mockCtx = new MockContext(params, queryParams, headers, body, app, routePath);
                        final Exception[] failure = new Exception[1];
                        RouteExecutor.execute(route, mockCtx, (e, c) -> {
                            failure[0] = e;
                            handleException(e, (MockContext) c);
                        });
                        if (failure[0] != null && activeSnapshotScope != null && activeSnapshotScope.mode() == SnapshotScope.Mode.CAPTURE) {
                            SnapshotRecorder.of(app).record(failure[0], route, mockCtx, method, routePath,
                                    queryParams, headers, body, activeSnapshotScope);
                        }
                        return new Response(mockCtx.status == 0 ? 200 : mockCtx.status, null, mockCtx.responseHeaders());
                    }
                }

                if (Aura.isDevMode()) {
                    String diagnostic = buildRouteDiagnostic(method, routePath);
                    return new Response(404, diagnostic, Map.of("Content-Type", "application/json"));
                }
                return new Response(404, "Not Found", Map.of());
            } finally {
                if (snapshotBound) Snapshot.clear();
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void handleException(Exception e, MockContext ctx) {
            ExceptionResolver.apply(app, e, ctx);
        }

        private static Map<String, String> parseQueryString(String path) {
            Map<String, String> params = new HashMap<>();
            int idx = path.indexOf('?');
            if (idx < 0) return params;
            String qs = path.substring(idx + 1);
            for (String pair : qs.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) params.put(kv[0], kv[1]);
                else if (kv.length == 1) params.put(kv[0], "");
            }
            return params;
        }

        public Response expect(int statusCode) {
            Response resp = execute();
            if (resp.status() != statusCode) {
                throw new AssertionError("Expected " + statusCode + " but got " + resp.status()
                        + " body: " + resp.body());
            }
            return resp;
        }

        private String buildRouteDiagnostic(String method, String path) {
            java.util.List<String> registered = new java.util.ArrayList<>();
            String hint = null;
            int bestDist = Integer.MAX_VALUE;
            for (var route : compiled) {
                String entry = route.method() + " " + route.rawPath();
                registered.add(entry);
                if (route.method().equalsIgnoreCase(method)) {
                    int dist = levenshtein(path, route.rawPath());
                    if (dist < bestDist && dist <= 3) {
                        bestDist = dist;
                        hint = entry;
                    }
                }
            }
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("error", "No route matched: " + method + " " + path);
            if (hint != null) body.put("hint", "Did you mean: " + hint + "?");
            body.put("registered", registered);
            return JSON.toJSONString(body);
        }

        private static int levenshtein(String a, String b) {
            int[] prev = new int[b.length() + 1];
            int[] curr = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) prev[j] = j;
            for (int i = 1; i <= a.length(); i++) {
                curr[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                }
                int[] tmp = prev; prev = curr; curr = tmp;
            }
            return prev[b.length()];
        }
    }

    public TestSession session() {
        return new TestSession(this);
    }

    public TestSession session(String authorization) {
        TestSession s = new TestSession(this);
        s.header("Authorization", authorization);
        return s;
    }

    public static class TestSession {
        private final TestClient client;
        private final Map<String, String> defaultHeaders = new HashMap<>();

        TestSession(TestClient client) {
            this.client = client;
        }

        public TestSession header(String name, String value) {
            defaultHeaders.put(name, value);
            return this;
        }

        public TestSession login(Response response) {
            String body = response.body();
            if (body != null) {
                Object token = com.alibaba.fastjson2.JSONPath.eval(
                        com.alibaba.fastjson2.JSON.parse(body), "$.token");
                if (token != null) {
                    defaultHeaders.put("Authorization", "Bearer " + token);
                }
            }
            return this;
        }

        public Request get(String path) { return wrap(client.get(path)); }
        public Request post(String path) { return wrap(client.post(path)); }
        public Request put(String path) { return wrap(client.put(path)); }
        public Request delete(String path) { return wrap(client.delete(path)); }
        public Request patch(String path) { return wrap(client.patch(path)); }
        public Request head(String path) { return wrap(client.head(path)); }
        public Request options(String path) { return wrap(client.options(path)); }

        private Request wrap(Request request) {
            for (var entry : defaultHeaders.entrySet()) {
                request.header(entry.getKey(), entry.getValue());
            }
            return request;
        }
    }

    public static class Response {
        private final int status;
        private final String body;
        private final Map<String, String> headers;

        Response(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers != null ? headers : Map.of();
        }

        public int status() { return status; }
        public String body() { return body; }

        public String header(String name) { return headers.get(name); }

        public <T> T json(Class<T> type) {
            return JSON.parseObject(body, type);
        }

        public <T> List<T> jsonList(Class<T> type) {
            return JSON.parseArray(body, type);
        }

        public Response expect(int statusCode) {
            if (status != statusCode) {
                throw new AssertionError("Expected " + statusCode + " but got " + status + " body: " + body);
            }
            return this;
        }

        public Response expectHeader(String name, String value) {
            String actual = headers.get(name);
            if (!java.util.Objects.equals(value, actual)) {
                throw new AssertionError("Expected header '" + name + "' to be '" + value + "' but got '" + actual + "'");
            }
            return this;
        }

        public Response bodyContains(String text) {
            if (body == null || !body.contains(text)) {
                throw new AssertionError("Body does not contain '" + text + "', got: " + body);
            }
            return this;
        }

        public Response expectJson(String jsonPath, Object expected) {
            Object actual = com.alibaba.fastjson2.JSONPath.eval(
                    com.alibaba.fastjson2.JSON.parse(body), jsonPath);
            if (!java.util.Objects.equals(expected, actual)) {
                throw new AssertionError("JSONPath " + jsonPath + ": expected " + expected + " but got " + actual);
            }
            return this;
        }
    }
}

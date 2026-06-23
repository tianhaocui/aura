package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestClient {

    private final Aura app;
    private final Router router;
    private final List<CompiledRoute> compiled;

    TestClient(Aura app, Router router) {
        this.app = app;
        this.router = router;
        this.compiled = UndertowStarter.compileRoutes(router, app);
    }

    public static TestClient of(Aura app) {
        Router router = new Router();
        // replay direct routes
        for (var entry : app.directRoutes()) {
            BaseHandler handler;
            if (entry.handler() instanceof BaseHandler h) {
                handler = h;
            } else {
                handler = new LambdaHandler(entry.handler());
            }
            switch (entry.method()) {
                case "GET" -> router.get(entry.path(), handler);
                case "POST" -> router.post(entry.path(), handler);
                case "PUT" -> router.put(entry.path(), handler);
                case "DELETE" -> router.delete(entry.path(), handler);
                case "PATCH" -> router.patch(entry.path(), handler);
                case "HEAD" -> router.head(entry.path(), handler);
                case "OPTIONS" -> router.options(entry.path(), handler);
            }
        }
        // replay routes config
        @SuppressWarnings("unchecked")
        var config = (java.util.function.Consumer<BaseRouter>) app.routeConfig();
        if (config != null) config.accept(router);
        // replay services
        for (Object service : app.services()) {
            ServiceRegistrar.register(service, router);
        }
        if (!app.scanPackages().isEmpty()) {
            PackageScanner.scan(app.scanPackages(), router);
        }
        return new TestClient(app, router);
    }

    public Request get(String path) { return new Request("GET", path); }
    public Request post(String path) { return new Request("POST", path); }
    public Request put(String path) { return new Request("PUT", path); }
    public Request delete(String path) { return new Request("DELETE", path); }
    public Request patch(String path) { return new Request("PATCH", path); }
    public Request head(String path) { return new Request("HEAD", path); }
    public Request options(String path) { return new Request("OPTIONS", path); }

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

            for (var route : compiled) {
                if (!route.method().equals(method)) continue;
                Map<String, String> params = route.match(routePath);
                if (params == null) continue;

                var mockCtx = new MockContext(params, queryParams, headers, body, app);
                try {
                    for (BaseHandler mw : route.beforeHandlers()) {
                        mw.handle(mockCtx);
                        if (mockCtx.isAborted()) break;
                    }
                    if (!mockCtx.isAborted()) {
                        route.handler().handle(mockCtx);
                    } else if (mockCtx.statusCode() == 200) {
                        mockCtx.status(403);
                    }
                } catch (Exception e) {
                    handleException(e, mockCtx);
                } finally {
                    for (BaseHandler h : route.afterHandlers()) {
                        try { h.handle(mockCtx); } catch (Exception ignored) {}
                    }
                }
                return new Response(mockCtx.status == 0 ? 200 : mockCtx.status, mockCtx.responseBody, mockCtx.responseHeaders());
            }

            // HEAD auto-fallback: use GET handler, suppress body
            if ("HEAD".equals(method)) {
                for (var route : compiled) {
                    if (!"GET".equals(route.method())) continue;
                    Map<String, String> params = route.match(routePath);
                    if (params == null) continue;
                    var mockCtx = new MockContext(params, queryParams, headers, body, app);
                    try {
                        for (BaseHandler mw : route.beforeHandlers()) {
                            mw.handle(mockCtx);
                            if (mockCtx.isAborted()) break;
                        }
                        if (!mockCtx.isAborted()) {
                            route.handler().handle(mockCtx);
                        }
                    } catch (Exception ignored) {}
                    return new Response(mockCtx.status == 0 ? 200 : mockCtx.status, null, mockCtx.responseHeaders());
                }
            }

            return new Response(404, "Not Found", Map.of());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void handleException(Exception e, MockContext ctx) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
            if (cause == null) cause = e;
            if (cause instanceof Error err) throw err;
            for (var entry : app.exceptionHandlers().entrySet()) {
                if (entry.getKey().isAssignableFrom(cause.getClass())) {
                    try {
                        ((BaseExceptionHandler) entry.getValue()).handle((Exception) cause, ctx);
                    } catch (Exception inner) {
                        if (ctx.status == 0) ctx.status = 500;
                    }
                    return;
                }
            }
            if (cause instanceof io.aura.Validate.ValidationException ve && !ve.errors().isEmpty()) {
                ctx.status = 400;
                ctx.responseBody = JSON.toJSONString(Map.of(
                        "error", "Validation failed",
                        "errors", ve.errors().stream()
                                .map(fe -> Map.of("field", fe.field(), "message", fe.message()))
                                .toList()));
                return;
            }
            if (cause instanceof IllegalArgumentException || cause instanceof io.aura.Validate.ValidationException) {
                ctx.status = 400;
                ctx.responseBody = JSON.toJSONString(Map.of("error",
                        cause.getMessage() != null ? cause.getMessage() : "Bad Request"));
                return;
            }
            if (ctx.status == 0) ctx.status = 500;
            ctx.responseBody = JSON.toJSONString(Map.of("error",
                    cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
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

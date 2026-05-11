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
        this.compiled = UndertowStarter.compileRoutes(router);
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

    public class Request {
        private final String method;
        private final String path;
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
                    }
                    route.handler().handle(mockCtx);
                } catch (Exception e) {
                    handleException(e, mockCtx);
                } finally {
                    for (BaseHandler h : route.afterHandlers()) {
                        try { h.handle(mockCtx); } catch (Exception ignored) {}
                    }
                }
                return new Response(mockCtx.status == 0 ? 200 : mockCtx.status, mockCtx.responseBody);
            }
            return new Response(404, "Not Found");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void handleException(Exception e, MockContext ctx) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
            if (cause == null) cause = e;
            if (cause instanceof Error err) throw err;
            for (var entry : router.exceptionHandlers.entrySet()) {
                if (entry.getKey().isAssignableFrom(cause.getClass())) {
                    try {
                        ((BaseExceptionHandler) entry.getValue()).handle((Exception) cause, ctx);
                    } catch (Exception inner) {
                        if (ctx.status == 0) ctx.status = 500;
                    }
                    return;
                }
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

    public record Response(int status, String body) {
        public <T> T json(Class<T> type) {
            return JSON.parseObject(body, type);
        }

        public Response expect(int statusCode) {
            if (status != statusCode) {
                throw new AssertionError("Expected " + statusCode + " but got " + status + " body: " + body);
            }
            return this;
        }

        public Response bodyContains(String text) {
            if (body == null || !body.contains(text)) {
                throw new AssertionError("Body does not contain '" + text + "', got: " + body);
            }
            return this;
        }
    }
}

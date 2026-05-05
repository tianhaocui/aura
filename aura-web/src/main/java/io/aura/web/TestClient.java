package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;

import java.util.HashMap;
import java.util.Map;

public class TestClient {

    private final Aura app;
    private final Router router;

    TestClient(Aura app, Router router) {
        this.app = app;
        this.router = router;
    }

    public static TestClient of(Aura app) {
        Router router = new Router();
        // replay direct routes
        for (var entry : app.directRoutes()) {
            Handler handler;
            if (entry.handler() instanceof Handler h) {
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
        var config = (java.util.function.Consumer<Router>) app.routeConfig();
        if (config != null) config.accept(router);
        // replay services
        for (Object service : app.services()) {
            ServiceRegistrar.register(service, router);
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
            var compiled = UndertowStarter.compileRoutes(router);
            for (var route : compiled) {
                if (!route.method().equals(method)) continue;
                Map<String, String> params = route.match(path);
                if (params == null) continue;

                var mockCtx = new MockContext(params, headers, body, app);
                try {
                    for (Handler mw : route.beforeHandlers()) {
                        mw.handle(mockCtx);
                    }
                    route.handler().handle(mockCtx);
                } catch (Exception e) {
                    if (mockCtx.status == 0) mockCtx.status = 500;
                    mockCtx.responseBody = JSON.toJSONString(Map.of("error", e.getMessage()));
                } finally {
                    for (Handler h : route.afterHandlers()) {
                        try { h.handle(mockCtx); } catch (Exception ignored) {}
                    }
                }
                return new Response(mockCtx.status == 0 ? 200 : mockCtx.status, mockCtx.responseBody);
            }
            return new Response(404, "Not Found");
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

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
    private final RateLimiter rateLimiter;

    TestClient(Aura app, Router router) {
        this.app = app;
        this.router = router;
        this.compiled = UndertowStarter.compileRoutes(router, app);
        if (app.rateLimitMax() > 0 && !"dev".equals(app.env())) {
            this.rateLimiter = new RateLimiter(app.rateLimitWindow());
        } else {
            // Check if any method has @RateLimit even without global config
            boolean hasMethodLimit = compiled.stream().anyMatch(cr ->
                    cr.handler() instanceof MethodRefHandler mh && mh.resolvedMethod() != null
                            && mh.resolvedMethod().isAnnotationPresent(io.aura.annotation.RateLimit.class));
            this.rateLimiter = hasMethodLimit ? new RateLimiter(java.time.Duration.ofSeconds(60)) : null;
        }
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
            PackageScanner.scan(app.scanPackages(), router, app);
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

            // Global rate limit check
            if (rateLimiter != null && app.rateLimitMax() > 0) {
                String clientIp = headers.getOrDefault("X-Forwarded-For", "127.0.0.1").split(",")[0].trim();
                if (!rateLimiter.allow(clientIp, app.rateLimitMax())) {
                    long retryAfter = app.rateLimitWindow().toSeconds();
                    return new Response(429,
                            "{\"error\":\"Rate limit exceeded\",\"retryAfter\":" + retryAfter + "}",
                            Map.of("Retry-After", String.valueOf(retryAfter)));
                }
            }

            for (var route : compiled) {
                if (!route.method().equals(method)) continue;
                Map<String, String> params = route.match(routePath);
                if (params == null) continue;

                // Per-method @RateLimit check
                if (rateLimiter != null && route.handler() instanceof MethodRefHandler mh && mh.resolvedMethod() != null) {
                    io.aura.annotation.RateLimit rl = mh.resolvedMethod().getAnnotation(io.aura.annotation.RateLimit.class);
                    if (rl != null) {
                        String clientIp = headers.getOrDefault("X-Forwarded-For", "127.0.0.1").split(",")[0].trim();
                        String key = clientIp + ":" + mh.resolvedMethod().getDeclaringClass().getSimpleName() + "." + mh.resolvedMethod().getName();
                        if (!rateLimiter.allow(key, rl.value())) {
                            return new Response(429,
                                    "{\"error\":\"Rate limit exceeded\",\"retryAfter\":" + rl.window() + "}",
                                    Map.of("Retry-After", String.valueOf(rl.window())));
                        }
                    }
                }

                var mockCtx = new MockContext(params, queryParams, headers, body, app, routePath);
                RouteExecutor.execute(route, mockCtx, (e, c) -> handleException(e, (MockContext) c));
                return new Response(mockCtx.status == 0 ? 200 : mockCtx.status, mockCtx.responseBody, mockCtx.responseHeaders());
            }

            // HEAD auto-fallback: use GET handler, suppress body
            if ("HEAD".equals(method)) {
                for (var route : compiled) {
                    if (!"GET".equals(route.method())) continue;
                    Map<String, String> params = route.match(routePath);
                    if (params == null) continue;
                    var mockCtx = new MockContext(params, queryParams, headers, body, app, routePath);
                    RouteExecutor.execute(route, mockCtx, (e, c) -> handleException(e, (MockContext) c));
                    return new Response(mockCtx.status == 0 ? 200 : mockCtx.status, null, mockCtx.responseHeaders());
                }
            }

            if (Aura.isDevMode()) {
                String diagnostic = buildRouteDiagnostic(method, routePath);
                return new Response(404, diagnostic, Map.of("Content-Type", "application/json"));
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
            if (cause instanceof io.aura.NotFoundException) {
                ctx.status = 404;
                ctx.responseBody = JSON.toJSONString(Map.of("error", cause.getMessage(), "code", "NOT_FOUND"));
                return;
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

package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;
import io.aura.AuraStarter;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class UndertowStarter implements AuraStarter {

    private static final Logger log = LoggerFactory.getLogger(UndertowStarter.class);
    private Undertow server;
    private GracefulShutdownHandler shutdownHandler;
    private ScheduledExecutorService timeoutScheduler;
    private Aura app;
    private Router router;
    private ResourceHandler staticHandler;
    private ClassPathResourceManager resourceManager;
    private volatile List<CompiledRoute> compiledRoutes;
    private volatile List<CompiledWsRoute> compiledWsRoutes;

    @Override
    @SuppressWarnings("unchecked")
    public void start(Aura app) {
        this.app = app;
        long t0 = System.currentTimeMillis();

        router = new Router();

        registerDirectRoutes(app, router);

        Consumer<?> config = app.routeConfig();
        if (config != null) {
            ((Consumer<BaseRouter>) config).accept(router);
        }

        for (Object service : app.services()) {
            ServiceRegistrar.register(service, router);
        }

        if (!app.scanPackages().isEmpty()) {
            PackageScanner.scan(app.scanPackages(), router, app);
        }

        compiledRoutes = compile(router, "", new ArrayList<>(app.beforeHandlers()), new ArrayList<>(app.afterHandlers()));
        compiledRoutes.sort(Comparator.comparingInt((CompiledRoute r) -> r.paramNames().size())
                .thenComparing(Comparator.comparingLong((CompiledRoute r) -> r.rawPath().chars().filter(c -> c == '/').count()).reversed()));
        detectDuplicateRoutes(compiledRoutes);

        if (!compiledRoutes.isEmpty()) {
            log.info("Routes:");
            for (CompiledRoute cr : compiledRoutes) {
                log.info("  {} {}", cr.method(), cr.rawPath());
            }
        }

        compiledWsRoutes = compileWsRoutes(router);

        String staticPath = app.staticFilesPath();
        if (staticPath != null) {
            String prefix = staticPath.startsWith("/") ? staticPath.substring(1) : staticPath;
            resourceManager = new ClassPathResourceManager(Thread.currentThread().getContextClassLoader(), prefix);
            staticHandler = new ResourceHandler(resourceManager)
                    .setCacheTime(86400);
        }

        shutdownHandler = new GracefulShutdownHandler(exchange -> {
            if (exchange.isInIoThread()) {
                exchange.dispatch(this::dispatch);
                return;
            }
            dispatch(exchange);
        });

        HttpHandler rootHandler = shutdownHandler;

        if (app.gzip()) {
            rootHandler = new EncodingHandler(rootHandler,
                    new ContentEncodingRepository().addEncodingHandler("gzip",
                            new GzipEncodingProvider(), 50,
                            ex -> {
                                String ct = ex.getResponseHeaders().getFirst(Headers.CONTENT_TYPE);
                                if (ct == null) return false;
                                if (ct.contains("text/event-stream")) return false;
                                if (!ct.contains("text") && !ct.contains("json") && !ct.contains("xml")) return false;
                                long len = ex.getResponseContentLength();
                                return len == -1 || len >= app.gzipMinSize();
                            }));
        }

        if (app.requestTimeout() > 0) {
            timeoutScheduler = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "aura-timeout");
                t.setDaemon(true);
                return t;
            });
        }

        var builder = Undertow.builder()
                .addHttpListener(app.port(), "0.0.0.0")
                .setWorkerThreads(app.workers())
                .setHandler(rootHandler);

        if (app.maxBodySize() > 0) {
            builder.setServerOption(io.undertow.UndertowOptions.MAX_ENTITY_SIZE, app.maxBodySize());
        }

        server = builder.build();

        try {
            server.start();
        } catch (RuntimeException e) {
            if (e.getCause() instanceof java.net.BindException) {
                throw new RuntimeException(
                    "Port " + app.port() + " is already in use. Set aura.port in config or AURA_PORT env var to use a different port.",
                    e.getCause());
            }
            throw e;
        }
        log.info("Aura started on port {} in {}ms", app.port(),
                System.currentTimeMillis() - t0);
    }

    @Override
    public void stop() {
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdownNow();
        }
        if (shutdownHandler != null) {
            shutdownHandler.shutdown();
            try {
                shutdownHandler.awaitShutdown(app.shutdownTimeout() * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (server != null) {
            server.stop();
            log.info("Aura stopped");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void reloadRoutes(io.aura.Aura app) {
        long t0 = System.currentTimeMillis();
        Router newRouter = new Router();

        registerDirectRoutes(app, newRouter);

        var config = (java.util.function.Consumer<BaseRouter>) app.routeConfig();
        if (config != null) config.accept(newRouter);

        for (Object service : app.services()) {
            ServiceRegistrar.register(service, newRouter);
        }

        if (!app.scanPackages().isEmpty()) {
            PackageScanner.scan(app.scanPackages(), newRouter, app);
        }

        var newCompiled = compile(newRouter, "",
                new java.util.ArrayList<>(app.beforeHandlers()), new java.util.ArrayList<>(app.afterHandlers()));
        newCompiled.sort(java.util.Comparator.comparingInt((CompiledRoute r) -> r.paramNames().size())
                .thenComparing(CompiledRoute::rawPath));
        detectDuplicateRoutes(newCompiled);

        this.compiledRoutes = newCompiled;
        this.compiledWsRoutes = compileWsRoutes(newRouter);
        this.router = newRouter;

        log.info("[aura-dev] routes reloaded in {}ms", System.currentTimeMillis() - t0);
    }

    private static void registerDirectRoutes(Aura app, Router router) {
        for (var entry : app.directRoutes()) {
            BaseHandler handler = entry.handler() instanceof BaseHandler h
                    ? h : new LambdaHandler(entry.handler());
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
    }

    private void dispatch(HttpServerExchange exchange) {
        String method = exchange.getRequestMethod().toString();
        String path = exchange.getRequestURI();

        // CORS
        String corsOrigin = app.corsOrigin();
        if (corsOrigin != null) {
            io.aura.CorsConfig corsConfig = app.corsConfig();
            String allowOrigin;
            String allowHeaders;
            boolean credentials = false;
            if (corsConfig != null) {
                String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
                allowOrigin = corsConfig.resolveOrigin(requestOrigin);
                allowHeaders = corsConfig.headers();
                credentials = corsConfig.credentials();
            } else {
                allowOrigin = corsOrigin;
                allowHeaders = "Content-Type, Authorization";
            }
            if (allowOrigin != null) {
                exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), allowOrigin);
                exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS");
                exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), allowHeaders);
                exchange.getResponseHeaders().put(new HttpString("Access-Control-Max-Age"), "86400");
                if (credentials) {
                    exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Credentials"), "true");
                }
            }
            if ("OPTIONS".equals(method)) {
                exchange.setStatusCode(204);
                exchange.endExchange();
                return;
            }
        }

        // built-in schema endpoint
        if ("GET".equals(method) && "/__schema__".equals(path)) {
            serveSchema(exchange);
            return;
        }

        // WebSocket upgrade
        if ("GET".equals(method) && isWebSocketUpgrade(exchange)) {
            if (!checkWsOrigin(exchange)) {
                exchange.setStatusCode(403);
                exchange.endExchange();
                return;
            }
            for (CompiledWsRoute wsRoute : compiledWsRoutes) {
                Map<String, String> params = wsRoute.match(path);
                if (params == null) continue;

                Context ctx = new Context(exchange, params, app, null);
                try {
                    for (BaseHandler mw : wsRoute.beforeHandlers()) {
                        mw.handle(ctx);
                        if (ctx.isAborted()) break;
                    }
                    if (ctx.isAborted()) {
                        if (exchange.getStatusCode() == 200) exchange.setStatusCode(403);
                        exchange.endExchange();
                        return;
                    }
                    wsRoute.upgrade(exchange, params);
                } catch (Exception e) {
                    exchange.setStatusCode(500);
                    exchange.endExchange();
                }
                return;
            }
        }

        for (CompiledRoute route : compiledRoutes) {
            if (!route.method().equals(method)) continue;
            Map<String, String> params = route.match(path);
            if (params == null) continue;

            String reqId = resolveRequestId(exchange);
            exchange.getResponseHeaders().put(new HttpString("X-Request-Id"), reqId);
            MDC.put("requestId", reqId);

            Context ctx = new Context(exchange, params, app, reqId);
            long start = System.currentTimeMillis();
            java.util.concurrent.ScheduledFuture<?> timeoutFuture = null;
            var responded = new AtomicBoolean(false);

            if (app.requestTimeout() > 0 && timeoutScheduler != null) {
                timeoutFuture = timeoutScheduler.schedule(() -> {
                    if (responded.compareAndSet(false, true) && !exchange.isResponseStarted()) {
                        exchange.setStatusCode(503);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                        exchange.getResponseSender().send("{\"error\":\"Request timeout\"}");
                    }
                }, app.requestTimeout(), TimeUnit.SECONDS);
            }

            RouteExecutor.execute(route, ctx, (e, c) -> {
                handleException(e, (Context) c);
                long elapsed = System.currentTimeMillis() - start;
                String clientIp = exchange.getSourceAddress() != null ? exchange.getSourceAddress().getHostString() : "-";
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
                if (cause == null) cause = e;
                log.error("[{}] {} {} {} {}: {} ({}ms, {})", reqId, method, path,
                        exchange.getStatusCode(), cause.getClass().getSimpleName(), cause.getMessage(), elapsed, clientIp);
            });
            responded.set(true);
            if (timeoutFuture != null) timeoutFuture.cancel(false);
            if (app.accessLog()) {
                long elapsed = System.currentTimeMillis() - start;
                if ("json".equals(app.accessLogFormat())) {
                    String ip = exchange.getSourceAddress() != null ? exchange.getSourceAddress().getHostString() : "-";
                    String safePath = path.replace("\\", "\\\\").replace("\"", "\\\"");
                    String safeReqId = reqId.replace("\\", "\\\\").replace("\"", "\\\"");
                    log.info("{{\"ts\":\"{}\",\"method\":\"{}\",\"path\":\"{}\",\"status\":{},\"elapsed\":{},\"reqId\":\"{}\",\"ip\":\"{}\"}}",
                            java.time.Instant.now(), method, safePath, exchange.getStatusCode(), elapsed, safeReqId, ip);
                } else {
                    log.info("{} {} → {} ({}ms)", method, path, exchange.getStatusCode(), elapsed);
                }
            }
            MDC.remove("requestId");
            return;
        }

        // HEAD auto-fallback: use GET handler but suppress body
        if ("HEAD".equals(method)) {
            for (CompiledRoute route : compiledRoutes) {
                if (!"GET".equals(route.method())) continue;
                Map<String, String> params = route.match(path);
                if (params == null) continue;
                Context ctx = new Context(exchange, params, app, null);
                RouteExecutor.execute(route, ctx, (e, c) -> handleException(e, (Context) c));
                exchange.endExchange();
                return;
            }
        }

        if (staticHandler != null) {
            try {
                io.undertow.server.handlers.resource.Resource res =
                        resourceManager.getResource(exchange.getRelativePath());
                if (res != null && !res.isDirectory()) {
                    staticHandler.handleRequest(exchange);
                    return;
                }
            } catch (Exception ignored) {}
            if (app.spa()) {
                try {
                    exchange.setRelativePath("/index.html");
                    staticHandler.handleRequest(exchange);
                    return;
                } catch (Exception ignored) {}
            }
        }
        exchange.setStatusCode(404);
        if (Aura.isDevMode()) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(buildRouteDiagnostic(method, path));
        } else {
            exchange.getResponseSender().send("Not Found");
        }
    }

    private void serveSchema(HttpServerExchange exchange) {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (CompiledRoute cr : compiledRoutes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("method", cr.method());
            entry.put("path", cr.rawPath());

            if (cr.meta() != null) {
                if (cr.meta().description() != null) {
                    entry.put("description", cr.meta().description());
                }
                if (cr.meta().returnType() != null) {
                    entry.put("returnType", cr.meta().returnType());
                }
            }

            // extract parameter info from MethodRefHandler if available
            List<Map<String, String>> params = buildParamSchema(cr);
            if (!params.isEmpty()) {
                entry.put("params", params);
            }

            entry.put("example", buildCurlExample(cr, params));

            routes.add(entry);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", app.prop("app.name") != null ? app.prop("app.name") : "Aura App");
        schema.put("routes", routes);

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(JSON.toJSONString(schema));
    }

    private String buildCurlExample(CompiledRoute cr, List<Map<String, String>> params) {
        String base = "http://localhost:" + app.port();
        String path = cr.rawPath();

        // replace path params with example values
        for (Map<String, String> p : params) {
            if ("path".equals(p.get("source"))) {
                String example = exampleValue(p.get("type"), p.get("name"));
                path = path.replace("{" + p.get("name") + "}", example);
            }
        }

        // build query string for query params (GET only)
        if ("GET".equals(cr.method())) {
            List<String> queryParts = new ArrayList<>();
            for (Map<String, String> p : params) {
                if ("query".equals(p.get("source"))) {
                    queryParts.add(p.get("name") + "=" + exampleValue(p.get("type"), p.get("name")));
                }
            }
            if (!queryParts.isEmpty()) path += "?" + String.join("&", queryParts);
        }

        StringBuilder curl = new StringBuilder("curl");
        if (!"GET".equals(cr.method())) {
            curl.append(" -X ").append(cr.method());
        }
        curl.append(" ").append(base).append(path);

        // add body for POST/PUT
        if ("POST".equals(cr.method()) || "PUT".equals(cr.method())) {
            for (Map<String, String> p : params) {
                if ("body".equals(p.get("source"))) {
                    curl.append(" -H 'Content-Type: application/json' -d '{}'");
                    break;
                }
            }
        }
        return curl.toString();
    }

    private static String exampleValue(String type, String name) {
        if (type == null) return "value";
        return switch (type) {
            case "int", "Integer", "long", "Long" -> "1";
            case "boolean", "Boolean" -> "true";
            case "double", "Double" -> "1.0";
            default -> "example";
        };
    }

    private List<Map<String, String>> buildParamSchema(CompiledRoute cr) {
        List<Map<String, String>> params = new ArrayList<>();
        Map<String, String> metaParams = cr.meta() != null ? cr.meta().params() : Map.of();

        if (cr.handler() instanceof MethodRefHandler mh) {
            Method m = mh.resolvedMethod();
            for (Parameter p : m.getParameters()) {
                if (p.getType() == Context.class) continue;
                Map<String, String> param = new LinkedHashMap<>();
                param.put("name", p.getName());
                param.put("type", p.getType().getSimpleName());

                // determine source
                if (p.getType().isRecord() || TypeUtil.isPojo(p.getType())) {
                    param.put("source", "body");
                } else if (cr.paramNames().contains(p.getName())) {
                    param.put("source", "path");
                } else {
                    param.put("source", "query");
                }

                String desc = metaParams.get(p.getName());
                if (desc != null) param.put("description", desc);

                params.add(param);
            }
        } else {
            // for lambda handlers, use path param names
            for (String name : cr.paramNames()) {
                Map<String, String> param = new LinkedHashMap<>();
                param.put("name", name);
                param.put("source", "path");
                String desc = metaParams.get(name);
                if (desc != null) param.put("description", desc);
                params.add(param);
            }
        }
        return params;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handleException(Exception e, Context ctx) {
        Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
        if (cause == null) cause = e;
        if (cause instanceof Error err) throw err;
        for (var entry : app.exceptionHandlers().entrySet()) {
            if (entry.getKey().isAssignableFrom(cause.getClass())) {
                try {
                    ((BaseExceptionHandler) entry.getValue()).handle((Exception) cause, ctx);
                } catch (Exception inner) {
                    log.error("Error in exception handler", inner);
                    ctx.status(500).json(java.util.Map.of("error", "Internal Server Error"));
                }
                return;
            }
        }
        if (cause instanceof io.aura.Validate.ValidationException ve && !ve.errors().isEmpty()) {
            ctx.status(400).json(java.util.Map.of(
                    "error", "Validation failed",
                    "errors", ve.errors().stream()
                            .map(fe -> java.util.Map.of("field", fe.field(), "message", fe.message()))
                            .toList()));
            return;
        }
        if (cause instanceof IllegalArgumentException || cause instanceof io.aura.Validate.ValidationException) {
            ctx.status(400).json(ApiError.of(
                    cause.getMessage() != null ? cause.getMessage() : "Bad Request",
                    "VALIDATION_ERROR"));
            return;
        }
        log.error("Unhandled exception", cause);
        String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        if ("dev".equals(app.env())) {
            java.io.StringWriter sw = new java.io.StringWriter();
            cause.printStackTrace(new java.io.PrintWriter(sw));
            ctx.status(500).json(java.util.Map.of("error", msg, "code", "INTERNAL_ERROR", "trace", sw.toString()));
        } else {
            ctx.status(500).json(ApiError.of(msg, "INTERNAL_ERROR"));
        }
    }

    static List<CompiledRoute> compileRoutes(BaseRouter router, Aura app) {
        List<CompiledRoute> routes = new UndertowStarter().compile(router, "",
                new ArrayList<>(app.beforeHandlers()), new ArrayList<>(app.afterHandlers()));
        routes.sort(Comparator.comparingInt((CompiledRoute r) -> r.paramNames().size())
                .thenComparing(Comparator.comparingLong((CompiledRoute r) -> r.rawPath().chars().filter(c -> c == '/').count()).reversed()));
        return routes;
    }

    static List<CompiledRoute> compileRoutes(BaseRouter router) {
        List<CompiledRoute> routes = new UndertowStarter().compile(router, "", new ArrayList<>(), new ArrayList<>());
        routes.sort(Comparator.comparingInt((CompiledRoute r) -> r.paramNames().size())
                .thenComparing(Comparator.comparingLong((CompiledRoute r) -> r.rawPath().chars().filter(c -> c == '/').count()).reversed()));
        return routes;
    }

    private List<CompiledRoute> compile(BaseRouter router, String prefix,
                                         List<BaseHandler> parentBefore, List<BaseHandler> parentAfter) {
        List<CompiledRoute> result = new ArrayList<>();

        List<BaseHandler> before = new ArrayList<>(parentBefore);
        before.addAll(router.beforeHandlers);

        List<BaseHandler> after = new ArrayList<>(parentAfter);
        after.addAll(router.afterHandlers);

        for (BaseRouteBuilder rb : router.routeBuilders) {
            var route = rb.route;
            var meta = new CompiledRoute.RouteMeta(
                    rb.description, rb.returnType, rb.paramDescriptions);
            result.add(CompiledRoute.compile(route.method(), prefix, route.path(),
                    before, route.handler(), after, meta));
        }

        for (BaseRouter.Group group : router.groups) {
            result.addAll(compile(group.router(), prefix + group.prefix(), before, after));
        }

        return result;
    }

    private void detectDuplicateRoutes(List<CompiledRoute> routes) {
        var seen = new java.util.HashSet<String>();
        for (CompiledRoute route : routes) {
            String key = route.method() + " " + route.rawPath();
            if (!seen.add(key)) {
                throw new IllegalStateException("Duplicate route: " + key);
            }
        }
    }

    private static boolean isWebSocketUpgrade(HttpServerExchange exchange) {
        String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
        return "websocket".equalsIgnoreCase(upgrade);
    }

    private boolean checkWsOrigin(HttpServerExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        io.aura.CorsConfig corsConfig = app.corsConfig();
        if (corsConfig != null) {
            return corsConfig.resolveOrigin(origin) != null;
        }
        String corsOrigin = app.corsOrigin();
        if (corsOrigin == null) return true;
        if ("*".equals(corsOrigin)) return true;
        return corsOrigin.equals(origin);
    }

    private List<CompiledWsRoute> compileWsRoutes(BaseRouter router) {
        return compileWsRoutes(router, "", new ArrayList<>());
    }

    private List<CompiledWsRoute> compileWsRoutes(BaseRouter router, String prefix,
                                                   List<BaseHandler> parentBefore) {
        List<CompiledWsRoute> result = new ArrayList<>();
        List<BaseHandler> before = new ArrayList<>(parentBefore);
        before.addAll(router.beforeHandlers);

        for (BaseRouter.WsRoute ws : router.wsRoutes) {
            String fullPath = prefix + ws.path();
            result.add(CompiledWsRoute.compile(fullPath, before, ws.handler()));
        }
        for (BaseRouter.Group group : router.groups) {
            result.addAll(compileWsRoutes(group.router(), prefix + group.prefix(), before));
        }
        return result;
    }

    private static String resolveRequestId(HttpServerExchange exchange) {
        String existing = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (existing != null && !existing.isBlank()) {
            String sanitized = existing.trim().replaceAll("[\\r\\n]", "");
            return sanitized.length() > 64 ? sanitized.substring(0, 64) : sanitized;
        }
        return generateShortId();
    }

    private static String generateShortId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong() | 0x1000000000000000L).substring(0, 12);
    }

    private String buildRouteDiagnostic(String method, String path) {
        List<String> registered = new ArrayList<>();
        String hint = null;
        int bestDist = Integer.MAX_VALUE;

        for (CompiledRoute cr : compiledRoutes) {
            String entry = cr.method() + " " + cr.rawPath();
            registered.add(entry);
            if (cr.method().equalsIgnoreCase(method)) {
                int dist = levenshtein(path, cr.rawPath());
                if (dist < bestDist && dist <= 3) {
                    bestDist = dist;
                    hint = entry;
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
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

package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;
import io.aura.AuraStarter;
import io.aura.snapshot.Snapshot;
import io.aura.snapshot.SnapshotScope;
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
                String source = "";
                if (cr.handler() instanceof MethodRefHandler mh && mh.resolvedMethod() != null) {
                    source = " — " + mh.resolvedMethod().getDeclaringClass().getSimpleName()
                            + "." + mh.resolvedMethod().getName();
                }
                log.info("  {} {}{}", cr.method(), cr.rawPath(), source);
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
        if (app != null) SnapshotRecorder.close(app);
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

        if (isCsrfRejected(exchange, method)) {
            exchange.setStatusCode(403);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send("{\"error\":\"CSRF validation failed\"}");
            return;
        }

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
                if (corsConfig.hasWildcardOrigin() && credentials) {
                    // A wildcard cannot be combined with credentialed CORS. Disable this
                    // response rather than reflecting an arbitrary browser Origin.
                    allowOrigin = null;
                    credentials = false;
                }
                if (requestOrigin != null && !"*".equals(allowOrigin)) {
                    exchange.addResponseCommitListener(ex -> ex.getResponseHeaders().add(Headers.VARY, "Origin"));
                }
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
            if (!schemaAllowed(exchange)) {
                exchange.setStatusCode(schemaTokenConfigured() ? 401 : 404);
                exchange.endExchange();
                return;
            }
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
            var timedOut = new AtomicBoolean(false);
            var timeoutActionDone = new java.util.concurrent.CountDownLatch(1);
            Object completionLock = new Object();
            SnapshotScope snapshotScope = Snapshot.currentOrNull();
            boolean snapshotBound = false;
            if (snapshotScope == null && SnapshotRecorder.shouldCapture(app)) {
                snapshotScope = SnapshotScope.capture();
                Snapshot.bind(snapshotScope);
                snapshotBound = true;
            }
            final SnapshotScope activeSnapshotScope = snapshotScope;
            final Thread handlerThread = Thread.currentThread();

            if (app.requestTimeout() > 0 && timeoutScheduler != null) {
                timeoutFuture = timeoutScheduler.schedule(() -> {
                    try {
                        boolean timeoutWon;
                        synchronized (completionLock) {
                            timeoutWon = responded.compareAndSet(false, true);
                            if (timeoutWon) timedOut.set(true);
                        }
                        if (timeoutWon) {
                            ctx.abort();
                            handlerThread.interrupt();
                            exchange.setPersistent(false);
                            if (!exchange.isResponseStarted()) {
                                exchange.setStatusCode(503);
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                                exchange.getResponseSender().send("{\"error\":\"Request timeout\"}");
                            }
                        }
                    } finally {
                        timeoutActionDone.countDown();
                    }
                }, app.requestTimeout(), TimeUnit.SECONDS);
            }

            try {
                RouteExecutor.execute(route, ctx, (e, c) -> {
                    handleException(e, (Context) c);
                    long elapsed = System.currentTimeMillis() - start;
                    if (activeSnapshotScope != null) {
                        SnapshotRecorder.of(app).record(e, route, (Context) c, exchange, activeSnapshotScope);
                    }
                    String clientIp = exchange.getSourceAddress() != null ? exchange.getSourceAddress().getHostString() : "-";
                    Throwable cause = ExceptionResolver.unwrap(e);
                    log.error("[{}] {} {} {} {}: {} ({}ms, {})", safeLog(reqId, 64), safeLog(method, 16),
                            safeLog(route.rawPath(), 512), exchange.getStatusCode(), cause.getClass().getSimpleName(),
                            productionExceptionMessage(cause), elapsed, safeLog(clientIp, 64), cause);
                });
                synchronized (completionLock) {
                    responded.compareAndSet(false, true);
                }
                if (app.accessLog()) {
                    long elapsed = System.currentTimeMillis() - start;
                    if ("json".equals(app.accessLogFormat())) {
                    String ip = safeLog(exchange.getSourceAddress() != null ? exchange.getSourceAddress().getHostString() : "-", 64);
                    String safePath = safeJsonLog(route.rawPath(), 512);
                    String safeReqId = safeJsonLog(reqId, 64);
                        log.info("{{\"ts\":\"{}\",\"method\":\"{}\",\"path\":\"{}\",\"status\":{},\"elapsed\":{},\"reqId\":\"{}\",\"ip\":\"{}\"}}",
                                java.time.Instant.now(), safeJsonLog(method, 16), safePath, exchange.getStatusCode(), elapsed, safeReqId, safeJsonLog(ip, 64));
                    } else {
                        log.info("{} {} → {} ({}ms)", safeLog(method, 16), safeLog(route.rawPath(), 512), exchange.getStatusCode(), elapsed);
                    }
                }
            } finally {
                if (timeoutFuture != null) timeoutFuture.cancel(false);
                if (timedOut.get()) {
                    awaitUninterruptibly(timeoutActionDone);
                    Thread.interrupted();
                }
                if (snapshotBound) Snapshot.clear();
                MDC.remove("requestId");
            }
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
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
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
        ExceptionResolver.apply(app, e, ctx);
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
        if (origin == null || origin.isBlank()) {
            // Non-browser clients commonly omit Origin. Keep that compatibility only
            // for development or an explicit opt-in; production browser handshakes
            // must carry an allowlisted Origin.
            return "dev".equalsIgnoreCase(app.env())
                    || Boolean.parseBoolean(property("aura.websocket.allow-missing-origin"));
        }
        String explicit = property("aura.websocket.origins");
        if (explicit != null && !explicit.isBlank()) {
            return java.util.Arrays.stream(explicit.split(","))
                    .map(String::trim).anyMatch(origin::equals);
        }
        io.aura.CorsConfig corsConfig = app.corsConfig();
        if (corsConfig != null) {
            if (corsConfig.hasWildcardOrigin()) return false;
            return corsConfig.resolveOrigin(origin) != null;
        }
        String corsOrigin = app.corsOrigin();
        if (corsOrigin == null) return "dev".equalsIgnoreCase(app.env());
        if ("*".equals(corsOrigin)) return false;
        return corsOrigin.equals(origin);
    }

    private boolean schemaAllowed(HttpServerExchange exchange) {
        boolean enabled = Boolean.parseBoolean(property("aura.schema.enabled"));
        if (!"dev".equalsIgnoreCase(app.env()) && !enabled) return false;
        String token = schemaToken();
        if (token == null || token.isBlank()) return "dev".equalsIgnoreCase(app.env());
        String supplied = exchange.getRequestHeaders().getFirst("X-Aura-Schema-Token");
        if (supplied == null) {
            String authorization = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                supplied = authorization.substring(7).trim();
            }
        }
        return java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                (supplied == null ? "" : supplied).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean schemaTokenConfigured() {
        return schemaToken() != null;
    }

    private String schemaToken() {
        String token = property("aura.schema.auth-token");
        if (token == null) token = property("aura.schema-token");
        return token;
    }

    private boolean isCsrfRejected(HttpServerExchange exchange, String method) {
        String enabled = property("aura.csrf.enabled");
        boolean enforce = enabled != null && Boolean.parseBoolean(enabled);
        if (!enforce || !isUnsafeMethod(method)) return false;
        String headerName = propertyOrDefault("aura.csrf.header", "X-CSRF-Token");
        String cookieName = propertyOrDefault("aura.csrf.cookie", "csrf_token");
        String header = exchange.getRequestHeaders().getFirst(headerName);
        var cookie = exchange.getRequestCookie(cookieName);
        if (header != null && !header.isBlank() && cookie != null && cookie.getValue() != null
                && !cookie.getValue().isBlank() && java.security.MessageDigest.isEqual(
                header.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                cookie.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8))) return false;
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            String referer = exchange.getRequestHeaders().getFirst("Referer");
            if (referer != null) {
                try { origin = java.net.URI.create(referer).getScheme() + "://" + java.net.URI.create(referer).getAuthority(); }
                catch (IllegalArgumentException ignored) { return true; }
            }
        }
        if (origin == null) return true;
        String expected = expectedPublicOrigin(exchange);
        return !expected.equalsIgnoreCase(origin);
    }

    private String expectedPublicOrigin(HttpServerExchange exchange) {
        String configured = property("aura.public-origin");
        if (configured != null && !configured.isBlank()) return configured.trim();
        String scheme = exchange.getRequestScheme();
        String host = exchange.getHostAndPort();
        String peer = exchange.getSourceAddress() == null || exchange.getSourceAddress().getAddress() == null
                ? null : exchange.getSourceAddress().getAddress().getHostAddress();
        if (Context.isTrustedProxy(app.prop("aura.trusted-proxies"), peer)) {
            String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
            String forwardedHost = exchange.getRequestHeaders().getFirst("X-Forwarded-Host");
            if (forwardedProto != null && forwardedProto.matches("(?i)https?")) scheme = forwardedProto.toLowerCase();
            if (forwardedHost != null && forwardedHost.matches("[A-Za-z0-9.-]+(?::[0-9]{1,5})?")) host = forwardedHost;
        }
        return scheme + "://" + host;
    }

    private static boolean isUnsafeMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method);
    }

    private String property(String key) {
        String env = System.getenv(key.replace('.', '_').replace('-', '_').toUpperCase());
        if (env != null) return env;
        String sys = System.getProperty(key);
        if (sys != null) return sys;
        return app.prop(key);
    }

    private String propertyOrDefault(String key, String fallback) {
        String value = property(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    static String safeLog(String value, int maxLength) {
        if (value == null) return "-";
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && result.length() < maxLength; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) result.append(' ');
            else result.append(c);
        }
        return result.toString();
    }

    static String safeJsonLog(String value, int maxLength) {
        return safeLog(value, maxLength).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void awaitUninterruptibly(java.util.concurrent.CountDownLatch latch) {
        boolean waiting = true;
        while (waiting) {
            try {
                latch.await();
                waiting = false;
            } catch (InterruptedException ignored) {
                // A timeout interrupts this same handler thread before signalling completion.
            }
        }
    }

    private String productionExceptionMessage(Throwable cause) {
        if ("dev".equalsIgnoreCase(app.env())) return safeLog(cause.getMessage(), 256);
        return "suppressed";
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

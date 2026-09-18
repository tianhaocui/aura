package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.AuraWebRuntime;
import io.aura.contract.CancellationToken;
import io.aura.AuraEvent;
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
import java.util.concurrent.atomic.AtomicInteger;

public class UndertowStarter implements AuraStarter {

    private static final Logger log = LoggerFactory.getLogger(UndertowStarter.class);
    private Undertow server;
    private GracefulShutdownHandler shutdownHandler;
    private ScheduledExecutorService timeoutScheduler;
    private AuraWebRuntime app;
    private Router router;
    private ResourceHandler staticHandler;
    private ClassPathResourceManager resourceManager;
    private volatile List<CompiledRoute> compiledRoutes;
    private volatile List<CompiledWsRoute> compiledWsRoutes;
    private WebSocketConnectionRegistry webSocketConnections;
    private FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter();
    private final AtomicInteger inFlight = new AtomicInteger();

    @Override
    @SuppressWarnings("unchecked")
    public void start(AuraWebRuntime app) {
        this.app = app;
        rateLimiter = new FixedWindowRateLimiter(app.rateLimitMaxKeys());
        long t0 = System.currentTimeMillis();

        router = RouteAssembler.assemble(app);

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

        webSocketConnections = new WebSocketConnectionRegistry(
                app.maxWebSocketConnections(), app.webSocketIdleTimeout());
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

        var builder = Undertow.builder().setWorkerThreads(app.workers()).setHandler(rootHandler);
        if (app.config().tlsEnabled()) {
            try {
                builder.addHttpsListener(app.port(), "0.0.0.0", app.tlsContext());
            } catch (Exception e) {
                throw new IllegalArgumentException("TLS configuration is invalid; HTTPS listener was not started", e);
            }
        } else {
            builder.addHttpListener(app.port(), "0.0.0.0");
        }

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
        if (webSocketConnections != null) {
            long closeTimeout = Math.min(1_000L,
                    Math.max(100L, app.shutdownTimeout() * 1_000L));
            webSocketConnections.shutdown(closeTimeout);
        }
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
    public void reloadRoutes(AuraWebRuntime app) {
        long t0 = System.currentTimeMillis();
        Router newRouter = RouteAssembler.assemble(app);

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

    private void dispatch(HttpServerExchange exchange) {
        String method = exchange.getRequestMethod().toString();
        String path = exchange.getRequestURI();

        if (!hostAllowed(exchange)) { app.emit(AuraEvent.of("request.rejected.host", java.util.Map.of("method", method))); reject(exchange, 421, "Misdirected Request"); return; }
        if (!admit(exchange, method, path)) return;
        if (app.admissionPolicy() == null && app.concurrencyLimit() > 0) {
            exchange.addExchangeCompleteListener((completed, next) -> { inFlight.decrementAndGet(); next.proceed(); });
        }
        if (app.securityHeaders()) {
            exchange.getResponseHeaders().put(new HttpString("X-Content-Type-Options"), "nosniff");
            exchange.getResponseHeaders().put(new HttpString("X-Frame-Options"), "DENY");
            exchange.getResponseHeaders().put(new HttpString("Referrer-Policy"), "no-referrer");
            if (exchange.isSecure()) exchange.getResponseHeaders().put(new HttpString("Strict-Transport-Security"), "max-age=31536000; includeSubDomains");
        }

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

        if ("GET".equals(method) && "/openapi.json".equals(path)) {
            if (!app.openApiEnabled() && !app.devMode()) {
                exchange.setStatusCode(404);
                exchange.endExchange();
                return;
            }
            serveOpenApi(exchange);
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

            CancellationToken cancellationToken = new CancellationToken();
            exchange.addExchangeCompleteListener((completed, next) -> { cancellationToken.cancel(); next.proceed(); });
            if (exchange.getConnection() != null) exchange.getConnection().addCloseListener(connection -> cancellationToken.cancel());
            Context ctx = new Context(exchange, params, app, reqId, cancellationToken);
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

            if (app.requestTimeout() > 0 && timeoutScheduler != null
                    && (route.meta() == null || !route.meta().streaming())) {
                timeoutFuture = timeoutScheduler.schedule(() -> {
                    try {
                        boolean timeoutWon;
                        synchronized (completionLock) {
                            timeoutWon = responded.compareAndSet(false, true);
                            if (timeoutWon) timedOut.set(true);
                        }
                        if (timeoutWon) {
                            app.emit(AuraEvent.of("request.timeout", java.util.Map.of("method", method, "path", route.rawPath())));
                            cancellationToken.cancel();
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
                    app.emit(AuraEvent.of("request.error", java.util.Map.of("method", method, "path", route.rawPath(), "error", ExceptionResolver.unwrap(e).getClass().getName())));
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
                app.emit(AuraEvent.of("request.completed", java.util.Map.of("method", method, "path", route.rawPath(), "status", exchange.getStatusCode(), "elapsedMs", System.currentTimeMillis() - start)));
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
        if (app.devMode()) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(buildRouteDiagnostic(method, path));
        } else {
            exchange.getResponseSender().send("Not Found");
        }
    }

    private void serveSchema(HttpServerExchange exchange) {
        Map<String, Object> schema = RouteContract.schema(app, compiledRoutes);

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
        exchange.getResponseSender().send(JSON.toJSONString(schema));
    }

    private void serveOpenApi(HttpServerExchange exchange) {
        Map<String, Object> document = RouteContract.openApi(app, compiledRoutes);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
        exchange.getResponseSender().send(JSON.toJSONString(document));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handleException(Exception e, Context ctx) {
        ExceptionResolver.apply(app, e, ctx);
    }

    static List<CompiledRoute> compileRoutes(BaseRouter router, AuraWebRuntime app) {
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
                    rb.description, rb.returnType, rb.paramDescriptions, rb.parameters(), rb.responseSchema,
                    rb.streaming, rb.authenticated, rb.permission);
            result.add(CompiledRoute.compile(route.method(), prefix, route.path(),
                    before, route.handler(), after, meta));
        }

        for (BaseRouter.Group group : router.groups) {
            result.addAll(compile(group.router(), prefix + group.prefix(), before, after));
        }

        return result;
    }

    static void detectDuplicateRoutes(List<CompiledRoute> routes) {
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
        boolean enforce = enabled != null ? Boolean.parseBoolean(enabled)
                : (app.publicOrigin() != null && !"dev".equalsIgnoreCase(app.env()));
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
        String configured = app.publicOrigin();
        if (configured == null) configured = property("aura.public-origin");
        if (configured != null && !configured.isBlank()) return configured.trim();
        String scheme = exchange.getRequestScheme();
        String host = exchange.getHostAndPort();
        String peer = exchange.getSourceAddress() == null || exchange.getSourceAddress().getAddress() == null
                ? null : exchange.getSourceAddress().getAddress().getHostAddress();
        if (Context.isTrustedProxy(String.join(",", app.trustedProxies()), peer)) {
            String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
            String forwardedHost = exchange.getRequestHeaders().getFirst("X-Forwarded-Host");
            if (forwardedProto != null && forwardedProto.matches("(?i)https?")) scheme = forwardedProto.toLowerCase();
            if (forwardedHost != null && forwardedHost.matches("[A-Za-z0-9.-]+(?::[0-9]{1,5})?")) host = forwardedHost;
        }
        return scheme + "://" + host;
    }

    private boolean hostAllowed(HttpServerExchange exchange) {
        String host = exchange.getHostName();
        if (host == null) return false;
        if (!app.allowedHosts().isEmpty()) return app.allowedHosts().contains(host.toLowerCase());
        if (app.publicOrigin() == null) return true;
        try { return host.equalsIgnoreCase(java.net.URI.create(app.publicOrigin()).getHost()); }
        catch (IllegalArgumentException e) { return false; }
    }

    private boolean admit(HttpServerExchange exchange, String method, String path) {
        io.aura.web.AdmissionPolicy custom = app.admissionPolicy();
        if (custom != null) {
            String peer = peerIp(exchange);
            String clientIp = Context.resolveClientIp(
                    String.join(",", app.trustedProxies()), peer,
                    exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            io.aura.web.AdmissionDecision decision;
            try {
                decision = custom.admit(new io.aura.web.AdmissionRequest(
                        method, path, matchedRoutePattern(method, path), clientIp,
                        exchange.getRequestHeaders().getFirst("X-API-Key")));
                if (decision == null) throw new IllegalStateException("Admission policy returned null");
            } catch (RuntimeException failure) {
                log.error("Admission policy failed closed for {} {}", method, path, failure);
                rejectAdmission(exchange, 503, "AURA_ADMISSION_POLICY_ERROR", 0);
                return false;
            }
            if (!decision.allowed()) {
                app.emit(AuraEvent.of("request.rejected.admission", java.util.Map.of(
                        "method", method, "path", path, "status", decision.status(),
                        "code", decision.code())));
                rejectAdmission(exchange, decision.status(), decision.code(),
                        decision.retryAfterSeconds());
                decision.close();
                return false;
            }
            exchange.addExchangeCompleteListener((completed, next) -> {
                try { decision.close(); } finally { next.proceed(); }
            });
            return true;
        }

        int limit = app.concurrencyLimit();
        if (limit > 0 && inFlight.incrementAndGet() > limit) {
            app.emit(AuraEvent.of("request.rejected.concurrency", java.util.Map.of("limit", limit)));
            inFlight.decrementAndGet(); reject(exchange, 503, "Service Unavailable"); return false;
        }
        int rate = app.rateLimitPerSecond();
        if (rate > 0) {
            String peer = peerIp(exchange);
            String forwardedFor = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            String key = Context.resolveClientIp(
                    String.join(",", app.trustedProxies()), peer, forwardedFor);
            if (key == null || key.isBlank()) key = "unknown";
            long now = System.currentTimeMillis() / 1000;
            if (!rateLimiter.tryAcquire(key, rate, now)) {
                app.emit(AuraEvent.of("request.rejected.rate", java.util.Map.of("ratePerSecond", rate, "key", key)));
                if (limit > 0) inFlight.decrementAndGet();
                exchange.getResponseHeaders().put(new HttpString("Retry-After"), "1");
                reject(exchange, 429, "Too Many Requests"); return false;
            }
        }
        return true;
    }

    private String matchedRoutePattern(String method, String path) {
        for (CompiledRoute route : compiledRoutes) {
            if ((route.method().equals(method)
                    || "HEAD".equals(method) && "GET".equals(route.method()))
                    && route.match(path) != null) return route.rawPath();
        }
        if ("GET".equals(method)) {
            for (CompiledWsRoute route : compiledWsRoutes) {
                if (route.match(path) != null) return route.rawPath();
            }
        }
        return path;
    }

    private static String peerIp(HttpServerExchange exchange) {
        return exchange.getSourceAddress() == null
                || exchange.getSourceAddress().getAddress() == null
                ? null : exchange.getSourceAddress().getAddress().getHostAddress();
    }

    private static void rejectAdmission(HttpServerExchange exchange, int status,
                                        String code, int retryAfterSeconds) {
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        if (retryAfterSeconds > 0) {
            exchange.getResponseHeaders().put(new HttpString("Retry-After"),
                    Integer.toString(retryAfterSeconds));
        }
        exchange.getResponseSender().send(JSON.toJSONString(java.util.Map.of(
                "error", "Request admission rejected", "code", code)));
    }

    private static void reject(HttpServerExchange exchange, int status, String message) {
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send("{\"error\":\"" + message + "\"}");
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
        return compileWsRoutes(router, "", new ArrayList<>(app.beforeHandlers()));
    }

    private List<CompiledWsRoute> compileWsRoutes(BaseRouter router, String prefix,
                                                   List<BaseHandler> parentBefore) {
        List<CompiledWsRoute> result = new ArrayList<>();
        List<BaseHandler> before = new ArrayList<>(parentBefore);
        before.addAll(router.beforeHandlers);

        for (BaseRouter.WsRoute ws : router.wsRoutes) {
            String fullPath = prefix + ws.path();
            result.add(CompiledWsRoute.compile(
                    fullPath, before, ws.handler(), app.maxWebSocketMessageSize(),
                    webSocketConnections));
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

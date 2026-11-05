package io.aura.web;

import io.aura.port.BaseHandler;
import io.aura.port.BaseRouteBuilder;
import io.aura.port.BaseRouter;
import com.alibaba.fastjson2.JSON;
import io.aura.AuraWebRuntime;
import io.aura.contract.CancellationToken;
import io.aura.contract.Route;
import io.aura.AuraEvent;
import io.aura.AuraStarter;
import io.aura.snapshot.Snapshot;
import io.aura.snapshot.SnapshotScope;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ByteRangeHandler;
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
import java.util.concurrent.ConcurrentHashMap;

public class UndertowStarter implements AuraStarter {

    private static final Logger log = LoggerFactory.getLogger(UndertowStarter.class);
    /**
     * The route order every dispatch uses: fewest placeholders first, so /users/new is preferred
     * over /users/{id}, and the deeper literal path wins when the counts tie. A dev reload has to
     * sort by the same key or the reloaded server answers requests the booted one would not.
     */
    private static final Comparator<CompiledRoute> ROUTE_PRECEDENCE =
            Comparator.comparingInt((CompiledRoute r) -> r.paramNames().size())
                    .thenComparing(Comparator.comparingLong(
                            (CompiledRoute r) -> r.rawPath().chars().filter(c -> c == '/').count())
                            .reversed());
    private volatile Undertow server;
    private GracefulShutdownHandler shutdownHandler;
    private ScheduledExecutorService timeoutScheduler;
    private AuraWebRuntime app;
    private Router router;
    private ResourceHandler staticHandler;
    private ClassPathResourceManager resourceManager;
    private volatile List<CompiledRoute> compiledRoutes;
    private volatile List<CompiledWsRoute> compiledWsRoutes;
    private WebSocketConnectionRegistry webSocketConnections;
    private RequestGuards guards;
    private final CancellationToken adapterShutdownCancellation = new CancellationToken();
    private final ConcurrentHashMap<io.undertow.server.ServerConnection,
            java.util.Set<CancellationToken>> connectionCancellations = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public void start(AuraWebRuntime app) {
        this.app = app;
        guards = RequestGuards.standard(app, this::matchedRoutePattern);
        long t0 = System.currentTimeMillis();

        router = RouteAssembler.assemble(app);

        compiledRoutes = compile(router, "", new ArrayList<>(app.beforeHandlers()), new ArrayList<>(app.afterHandlers()));
        compiledRoutes.sort(ROUTE_PRECEDENCE);
        detectDuplicateRoutes(compiledRoutes);

        if (!compiledRoutes.isEmpty()) {
            log.info("Routes:");
            for (CompiledRoute cr : compiledRoutes) {
                String source = "";
                if (cr.handler() instanceof LambdaHandler lh) {
                    source = " — " + lh.samMethod().getDeclaringClass().getSimpleName()
                            + "." + lh.samMethod().getName();
                }
                log.info("  {} {}{}", cr.method(), cr.rawPath(), source);
            }
        }

        webSocketConnections = new WebSocketConnectionRegistry(
                app.maxWebSocketConnections(), app.webSocketIdleTimeout());
        compiledWsRoutes = compileWsRoutes(router);
        detectDuplicateWsRoutes(compiledWsRoutes);

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
                                // Content-Range describes the identity representation; a gzipped
                                // partial response would not match the byte offsets the client asked for.
                                if (ex.getRequestHeaders().contains(Headers.RANGE)) return false;
                                long len = ex.getResponseContentLength();
                                return len == -1 || len >= app.gzipMinSize();
                            }));
        }

        // 206/416 for GET and HEAD once the response declares its full length; chunked
        // streams cannot seek and keep answering 200 with Accept-Ranges: none.
        rootHandler = new ByteRangeHandler(rootHandler, true);

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
        int bound = boundPort();
        log.info("Aura started on port {} in {}ms", bound > 0 ? bound : app.port(),
                System.currentTimeMillis() - t0);
    }

    @Override
    public int boundPort() {
        Undertow current = server;
        if (current == null) return -1;
        List<Undertow.ListenerInfo> listeners = current.getListenerInfo();
        if (listeners.isEmpty()) return -1;
        if (listeners.get(0).getAddress() instanceof java.net.InetSocketAddress address) {
            return address.getPort();
        }
        return -1;
    }

    @Override
    public void stop() {
        adapterShutdownCancellation.cancel();
        long shutdownMillis = Math.max(0L, app.shutdownTimeout() * 1_000L);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownMillis);
        if (shutdownHandler != null) shutdownHandler.shutdown();
        if (webSocketConnections != null) {
            long closeTimeout = Math.min(1_000L, remainingMillis(deadlineNanos));
            webSocketConnections.shutdown(closeTimeout);
        }
        if (shutdownHandler != null) {
            try {
                shutdownHandler.awaitShutdown(remainingMillis(deadlineNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdownNow();
        }
        connectionCancellations.values().forEach(tokens ->
                tokens.forEach(CancellationToken::cancel));
        Undertow current = server;
        server = null;
        if (current != null) {
            current.stop();
            log.info("Aura stopped");
        }
        connectionCancellations.clear();
        if (app != null) SnapshotRecorder.close(app);
    }

    private static long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return 0L;
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void reloadRoutes(AuraWebRuntime app) {
        long t0 = System.currentTimeMillis();
        Router newRouter = RouteAssembler.assemble(app);

        var newCompiled = compile(newRouter, "",
                new java.util.ArrayList<>(app.beforeHandlers()), new java.util.ArrayList<>(app.afterHandlers()));
        newCompiled.sort(ROUTE_PRECEDENCE);
        detectDuplicateRoutes(newCompiled);

        this.compiledRoutes = newCompiled;
        this.compiledWsRoutes = compileWsRoutes(newRouter);
        detectDuplicateWsRoutes(this.compiledWsRoutes);
        this.router = newRouter;

        log.info("[aura-dev] routes reloaded in {}ms", System.currentTimeMillis() - t0);
    }

    private void dispatch(HttpServerExchange exchange) {
        String method = exchange.getRequestMethod().toString();
        String rawPath = exchange.getRequestURI();
        // The built-in endpoints and diagnostics read the decoded form; route matching takes the
        // raw form so it decodes exactly once. A path that cannot be decoded still goes through
        // matching, where it is refused, and therefore falls through to the ordinary 404.
        String path = CompiledRoute.decodePath(rawPath);
        if (path == null) path = rawPath;

        GuardOutcome guardOutcome = guards.evaluate(new HttpGuardedRequest(exchange));
        if (!guardOutcome.proceed()) {
            exchange.setStatusCode(guardOutcome.status());
            if (guardOutcome.body() == null) {
                exchange.endExchange();
            } else {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                exchange.getResponseSender().send(guardOutcome.body());
            }
            return;
        }

        // built-in schema endpoint; who may read it is decided in the shared guard chain
        if ("GET".equals(method) && "/__schema__".equals(path)) {
            serveSchema(exchange);
            return;
        }

        if ("GET".equals(method) && "/openapi.json".equals(path)) {
            serveOpenApi(exchange);
            return;
        }

        // WebSocket upgrade
        if ("GET".equals(method) && isWebSocketUpgrade(exchange)) {
            for (CompiledWsRoute wsRoute : compiledWsRoutes) {
                Map<String, String> params = wsRoute.match(rawPath);
                if (params == null) continue;

                Context ctx = new Context(exchange, params, app, null,
                        requestCancellation(exchange));
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
            Map<String, String> params = route.match(rawPath);
            if (params == null) continue;

            String reqId = resolveRequestId(exchange);
            exchange.getResponseHeaders().put(new HttpString("X-Request-Id"), reqId);
            MDC.put("requestId", reqId);

            CancellationToken cancellationToken = requestCancellation(exchange);
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
                    && !route.contract().streaming()) {
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
                Map<String, String> params = route.match(rawPath);
                if (params == null) continue;
                Context ctx = new Context(exchange, params, app, null,
                        requestCancellation(exchange));
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

    private CancellationToken requestCancellation(HttpServerExchange exchange) {
        CancellationToken runtimeShutdown = app.shutdownToken();
        CancellationToken token = runtimeShutdown == null
                ? CancellationToken.linked(adapterShutdownCancellation)
                : CancellationToken.linked(runtimeShutdown, adapterShutdownCancellation);
        registerConnectionCancellation(exchange, token);
        exchange.addExchangeCompleteListener((completed, next) -> {
            unregisterConnectionCancellation(completed, token);
            token.cancel();
            next.proceed();
        });
        return token;
    }

    private void registerConnectionCancellation(HttpServerExchange exchange,
                                                CancellationToken token) {
        io.undertow.server.ServerConnection connection = exchange.getConnection();
        if (connection == null) return;
        java.util.Set<CancellationToken> created = ConcurrentHashMap.newKeySet();
        java.util.Set<CancellationToken> tokens = connectionCancellations.putIfAbsent(connection, created);
        if (tokens == null) {
            tokens = created;
            connection.addCloseListener(closed -> {
                java.util.Set<CancellationToken> active = connectionCancellations.remove(closed);
                if (active != null) active.forEach(CancellationToken::cancel);
            });
        }
        tokens.add(token);
        if (!connection.isOpen()) {
            java.util.Set<CancellationToken> active = connectionCancellations.remove(connection);
            if (active != null) active.forEach(CancellationToken::cancel);
            else token.cancel();
        }
    }

    private void unregisterConnectionCancellation(HttpServerExchange exchange,
                                                  CancellationToken token) {
        io.undertow.server.ServerConnection connection = exchange.getConnection();
        if (connection == null) return;
        java.util.Set<CancellationToken> tokens = connectionCancellations.get(connection);
        if (tokens == null) return;
        tokens.remove(token);
    }

    private void serveSchema(HttpServerExchange exchange) {
        Map<String, Object> schema = RouteContract.schema(app, compiledRoutes, webSocketContracts());

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
        exchange.getResponseSender().send(JSON.toJSONString(schema));
    }

    private void serveOpenApi(HttpServerExchange exchange) {
        Map<String, Object> document = RouteContract.openApi(app, compiledRoutes, webSocketContracts());
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
        exchange.getResponseSender().send(JSON.toJSONString(document));
    }

    private List<Route> webSocketContracts() {
        return compiledWsRoutes.stream().map(CompiledWsRoute::contract).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handleException(Exception e, Context ctx) {
        ExceptionResolver.apply(app, e, ctx);
    }

    static List<CompiledRoute> compileRoutes(BaseRouter router, AuraWebRuntime app) {
        List<CompiledRoute> routes = new UndertowStarter().compile(router, "",
                new ArrayList<>(app.beforeHandlers()), new ArrayList<>(app.afterHandlers()));
        routes.sort(ROUTE_PRECEDENCE);
        return routes;
    }

    static List<CompiledRoute> compileRoutes(BaseRouter router) {
        List<CompiledRoute> routes = new UndertowStarter().compile(router, "", new ArrayList<>(), new ArrayList<>());
        routes.sort(ROUTE_PRECEDENCE);
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
            result.add(CompiledRoute.compile(prefix, route, before,
                    (BaseHandler) route.handler(), after, rb.paramDescriptions));
        }

        for (BaseRouter.Group group : router.groups) {
            result.addAll(compile(group.router(), prefix + group.prefix(), before, after));
        }

        return result;
    }

        /**
     * Two templates that differ only in what the placeholder is called match exactly the same
     * requests, so which one answers is decided by the sort above rather than by what the author
     * declared. The name is the only thing that differs, so the comparison drops it.
     */
    /**
     * WebSocket endpoints are all GET upgrades, so two templates that differ only in the name of
     * a placeholder answer the same handshake. The HTTP table already refuses that shape; the
     * WebSocket table has to refuse it at the same point or the winner is decided by sort order.
     */
    static void detectDuplicateWsRoutes(List<CompiledWsRoute> routes) {
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (CompiledWsRoute route : routes) {
            String path = route.rawPath();
            String shape = path.replaceAll("\\{[a-zA-Z_][a-zA-Z0-9_]*}", "{}");
            String previous = seen.putIfAbsent(shape, path);
            if (previous == null) continue;
            throw new IllegalStateException("Duplicate WebSocket route: " + path + " and "
                    + previous + " match the same requests");
        }
    }

    static void detectDuplicateRoutes(List<CompiledRoute> routes) {
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (CompiledRoute route : routes) {
            String shape = route.method() + " "
                    + route.rawPath().replaceAll("\\{[a-zA-Z_][a-zA-Z0-9_]*}", "{}");
            String previous = seen.putIfAbsent(shape, route.rawPath());
            if (previous == null) continue;
            if (previous.equals(route.rawPath())) {
                throw new IllegalStateException(
                        "Duplicate route: " + route.method() + " " + route.rawPath());
            }
            throw new IllegalStateException("Duplicate route: " + route.rawPath() + " and "
                    + previous + " match the same requests");
        }
    }

    private static boolean isWebSocketUpgrade(HttpServerExchange exchange) {
        String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
        return "websocket".equalsIgnoreCase(upgrade);
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
        return compileWsRoutes(app, router, webSocketConnections);
    }

    /**
     * The WebSocket half of the route table, built from the same contracts as HTTP so that the
     * dispatching server and the in-memory client describe one endpoint set.
     */
    static List<CompiledWsRoute> compileWsRoutes(AuraWebRuntime app, BaseRouter router,
                                                 WebSocketConnectionRegistry connections) {
        return compileWsRoutes(app, router, "", new ArrayList<>(app.beforeHandlers()), connections);
    }

    private static List<CompiledWsRoute> compileWsRoutes(AuraWebRuntime app, BaseRouter router,
                                                         String prefix, List<BaseHandler> parentBefore,
                                                         WebSocketConnectionRegistry connections) {
        List<CompiledWsRoute> result = new ArrayList<>();
        List<BaseHandler> before = new ArrayList<>(parentBefore);
        before.addAll(router.beforeHandlers);

        for (BaseRouter.WsRoute ws : router.wsRoutes) {
            Route contract = ws.route().withPath(prefix + ws.path());
            List<BaseHandler> handshake = new ArrayList<>(before);
            // The handshake is an HTTP request, so it stands in the same auth door as the routes
            // it upgrades: declared with the endpoint, checked before the protocol switches.
            handshake.add(RouteAssembler.secure(app, contract, request -> { }));
            result.add(CompiledWsRoute.compile(contract, handshake,
                    app.maxWebSocketMessageSize(), connections));
        }
        for (BaseRouter.Group group : router.groups) {
            result.addAll(compileWsRoutes(app, group.router(), prefix + group.prefix(), before,
                    connections));
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

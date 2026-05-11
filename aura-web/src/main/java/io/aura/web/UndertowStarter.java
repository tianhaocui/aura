package io.aura.web;

import com.alibaba.fastjson2.JSON;
import io.aura.Aura;
import io.aura.AuraStarter;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class UndertowStarter implements AuraStarter {

    private static final Logger log = LoggerFactory.getLogger(UndertowStarter.class);
    private Undertow server;
    private GracefulShutdownHandler shutdownHandler;
    private Aura app;
    private Router router;
    private ResourceHandler staticHandler;
    private ClassPathResourceManager resourceManager;
    private List<CompiledRoute> compiledRoutes;

    @Override
    @SuppressWarnings("unchecked")
    public void start(Aura app) {
        this.app = app;
        long t0 = System.currentTimeMillis();

        router = new Router();

        // direct routes from app.get/post/put/delete
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

        Consumer<?> config = app.routeConfig();
        if (config != null) {
            ((Consumer<BaseRouter>) config).accept(router);
        }

        for (Object service : app.services()) {
            ServiceRegistrar.register(service, router);
        }

        if (!app.scanPackages().isEmpty()) {
            PackageScanner.scan(app.scanPackages(), router);
        }

        compiledRoutes = compile(router, "", new ArrayList<>(), new ArrayList<>());
        compiledRoutes.sort(Comparator.comparingInt(r -> r.paramNames().size()));

        String staticPath = app.staticFilesPath();
        if (staticPath != null) {
            String prefix = staticPath.startsWith("/") ? staticPath.substring(1) : staticPath;
            resourceManager = new ClassPathResourceManager(Thread.currentThread().getContextClassLoader(), prefix);
            staticHandler = new ResourceHandler(resourceManager);
        }

        shutdownHandler = new GracefulShutdownHandler(exchange -> {
            if (exchange.isInIoThread()) {
                exchange.dispatch(ex -> dispatch(ex));
                return;
            }
            dispatch(exchange);
        });

        var builder = Undertow.builder()
                .addHttpListener(app.port(), "0.0.0.0")
                .setWorkerThreads(app.workers())
                .setHandler(shutdownHandler);

        if (app.maxBodySize() > 0) {
            builder.setServerOption(io.undertow.UndertowOptions.MAX_ENTITY_SIZE, app.maxBodySize());
        }

        server = builder.build();

        server.start();
        log.info("Aura started on port {} in {}ms", app.port(),
                System.currentTimeMillis() - t0);
    }

    @Override
    public void stop() {
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

    private void dispatch(HttpServerExchange exchange) {
        String method = exchange.getRequestMethod().toString();
        String path = exchange.getRequestURI();

        // CORS
        String corsOrigin = app.corsOrigin();
        if (corsOrigin != null) {
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Origin"), corsOrigin);
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), "Content-Type, Authorization");
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

        for (CompiledRoute route : compiledRoutes) {
            if (!route.method().equals(method)) continue;
            Map<String, String> params = route.match(path);
            if (params == null) continue;

            Context ctx = new Context(exchange, params, app);
            long start = System.currentTimeMillis();
            try {
                for (BaseHandler mw : route.beforeHandlers()) {
                    mw.handle(ctx);
                }
                route.handler().handle(ctx);
            } catch (Exception e) {
                handleException(e, ctx);
            } finally {
                runAfterHandlers(route.afterHandlers(), ctx);
                log.info("{} {} {} {}ms", method, path, exchange.getStatusCode(), System.currentTimeMillis() - start);
            }
            return;
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
        exchange.getResponseSender().send("Not Found");
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

    private void runAfterHandlers(List<BaseHandler> afterHandlers, Context ctx) {
        for (BaseHandler h : afterHandlers) {
            try {
                h.handle(ctx);
            } catch (Exception e) {
                log.error("Error in after handler", e);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handleException(Exception e, Context ctx) {
        Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
        if (cause == null) cause = e;
        if (cause instanceof Error err) throw err;
        for (var entry : router.exceptionHandlers.entrySet()) {
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
        if (cause instanceof IllegalArgumentException || cause instanceof io.aura.Validate.ValidationException) {
            ctx.status(400).json(java.util.Map.of("error", cause.getMessage() != null ? cause.getMessage() : "Bad Request"));
            return;
        }
        log.error("Unhandled exception", cause);
        String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        if ("dev".equals(app.env())) {
            java.io.StringWriter sw = new java.io.StringWriter();
            cause.printStackTrace(new java.io.PrintWriter(sw));
            ctx.status(500).json(java.util.Map.of("error", msg, "trace", sw.toString()));
        } else {
            ctx.status(500).json(java.util.Map.of("error", msg));
        }
    }

    static List<CompiledRoute> compileRoutes(BaseRouter router) {
        return new UndertowStarter().compile(router, "", new ArrayList<>(), new ArrayList<>());
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
}

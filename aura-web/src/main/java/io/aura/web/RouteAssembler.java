package io.aura.web;

import io.aura.AuraWebRuntime;
import io.aura.contract.Route;

/** Builds the adapter router from the runtime's complete route contribution set. */
final class RouteAssembler {
    private RouteAssembler() { }

    static Router assemble(AuraWebRuntime app) {
        app.prepareModules();
        Router router = new Router();
        for (Route entry : app.directRoutes()) {
            if (entry.isWebSocket()) registerWebSocket(router, entry);
            else registerDirectRoute(app, router, entry);
        }

        for (Object service : app.services()) {
            ServiceRegistrar.register(service, router);
        }
        return router;
    }

    /**
     * The contributed handler is the module's configuration callback; assembling turns it into
     * the concrete endpoint once, so a later handshake never re-runs module code.
     */
    @SuppressWarnings("unchecked")
    private static void registerWebSocket(Router router, Route entry) {
        WsHandler handler;
        if (entry.handler() instanceof WsHandler configured) {
            handler = configured;
        } else if (entry.handler() instanceof java.util.function.Consumer<?> configure) {
            handler = new WsHandler();
            ((java.util.function.Consumer<WsHandler>) configure).accept(handler);
        } else {
            throw new IllegalArgumentException(
                    "WebSocket route handler must configure a WsHandler: " + entry.path());
        }
        router.ws(RouteCatalog.describeWebSocket(entry).withHandler(handler));
    }

    private static void registerDirectRoute(AuraWebRuntime app, Router router, Route entry) {
        BaseHandler handler = entry.handler() instanceof BaseHandler h
                ? h : new LambdaHandler(entry.handler());
        handler = secure(app, entry, handler);
        BaseRouteBuilder builder = switch (entry.method()) {
            case "GET" -> router.get(entry.path(), handler);
            case "POST" -> router.post(entry.path(), handler);
            case "PUT" -> router.put(entry.path(), handler);
            case "DELETE" -> router.delete(entry.path(), handler);
            case "PATCH" -> router.patch(entry.path(), handler);
            case "HEAD" -> router.head(entry.path(), handler);
            case "OPTIONS" -> router.options(entry.path(), handler);
            default -> throw new IllegalArgumentException("Unsupported route method: " + entry.method());
        };
        // One assignment instead of copying seven fields in lockstep: the declared Route already
        // carries every fact, only the dispatch handler changes to the auth-wrapped one.
        builder.route = entry.withHandler(handler);
    }

    /**
     * The one auth door, used for HTTP handlers and for the WebSocket handshake: an endpoint
     * that requires a user requires one before it upgrades, not after.
     */
    static BaseHandler secure(AuraWebRuntime app, Route entry,
                              BaseHandler next) {
        String permission = entry.permission();
        if (!entry.authenticated()) return next;
        return request -> {
            String userId = app.authFunction() == null ? null : app.authFunction().apply(request);
            if (userId == null || userId.isBlank()) {
                request.status(401).json(java.util.Map.of(
                        "error", "Authentication required",
                        "code", "AURA_AUTHENTICATION_REQUIRED"));
                request.abort();
                return;
            }
            request.set("_userId", userId);
            if (permission != null && (app.permissionChecker() == null
                    || !app.permissionChecker().test(userId, permission))) {
                request.status(403).json(java.util.Map.of(
                        "error", "Permission denied",
                        "code", "AURA_PERMISSION_DENIED",
                        "permission", permission));
                request.abort();
                return;
            }
            next.handle(request);
        };
    }
}

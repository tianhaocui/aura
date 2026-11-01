package io.aura.web;

import io.aura.AuraWebRuntime;
import io.aura.contract.Route;

/** Builds the adapter router from the runtime's complete route contribution set. */
final class RouteAssembler {
    private RouteAssembler() { }

    static Router assemble(AuraWebRuntime app) {
        app.prepareModules();
        Router router = new Router();
        registerDirectRoutes(app, router);
        for (var contribution : app.webSocketContributions()) {
            router.ws(contribution.path(), contribution.configure());
        }

        for (Object service : app.services()) {
            ServiceRegistrar.register(service, router);
        }
        return router;
    }

    private static void registerDirectRoutes(AuraWebRuntime app, Router router) {
        for (Route entry : app.directRoutes()) {
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
    }

    private static BaseHandler secure(AuraWebRuntime app, Route entry,
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

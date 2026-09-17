package io.aura.web;

import io.aura.AuraWebRuntime;
import io.aura.contract.ApiParameter;

import java.util.Collection;
import java.util.function.Consumer;

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

        @SuppressWarnings("unchecked")
        Consumer<BaseRouter> configuredRoutes = (Consumer<BaseRouter>) app.routeConfig();
        if (configuredRoutes != null) configuredRoutes.accept(router);

        for (Object service : app.services()) {
            ServiceRegistrar.register(service, router);
        }
        if (!app.scanPackages().isEmpty()) {
            PackageScanner.scan(app.scanPackages(), router, app);
        }
        return router;
    }

    private static void registerDirectRoutes(AuraWebRuntime app, Router router) {
        for (var entry : app.directRoutes()) {
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
            if (entry.description() != null) builder.describe(entry.description());
            if (Boolean.TRUE.equals(entry.metadata().get("streaming"))) builder.streaming();
            builder.authenticated = Boolean.TRUE.equals(entry.metadata().get(
                    io.aura.contract.RouteContribution.AUTHENTICATED));
            Object permission = entry.metadata().get(io.aura.contract.RouteContribution.PERMISSION);
            builder.permission = permission == null ? null : permission.toString();
            Object returnType = entry.metadata().get("returnType");
            if (returnType != null) builder.returnType = returnType.toString();
            builder.responseSchema = entry.responseSchema();
            Object parameters = entry.metadata().get("parameters");
            if (parameters instanceof Collection<?> values) {
                for (Object value : values) {
                    if (value instanceof ApiParameter parameter) {
                        builder.param(parameter);
                    }
                }
            }
        }
    }

    private static BaseHandler secure(AuraWebRuntime app, io.aura.RouteEntry entry,
                                      BaseHandler next) {
        Object permissionValue = entry.metadata().get(io.aura.contract.RouteContribution.PERMISSION);
        boolean authenticated = Boolean.TRUE.equals(
                entry.metadata().get(io.aura.contract.RouteContribution.AUTHENTICATED))
                || permissionValue != null;
        if (!authenticated) return next;
        String permission = permissionValue == null ? null : permissionValue.toString();
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

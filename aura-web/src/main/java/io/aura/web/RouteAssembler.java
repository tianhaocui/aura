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
            Object returnType = entry.metadata().get("returnType");
            if (returnType != null) builder.returnType = returnType.toString();
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
}

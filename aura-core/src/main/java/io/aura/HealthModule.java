package io.aura;

import io.aura.contract.HealthStatus;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import io.aura.web.BaseContext;
import io.aura.web.BaseHandler;

/**
 * Optional health endpoints for explicitly composed applications.
 *
 * <p>The module keeps the endpoint contract in one place while the runtime
 * supplies the actual liveness, readiness, and dependency status.</p>
 */
public final class HealthModule implements AuraModule {
    private static final String STATUS = "status";

    private HealthModule() { }

    public static HealthModule create() { return new HealthModule(); }

    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("health", "1.0.0")
                .provides("health.liveness", "health.readiness");
    }

    @Override public void install(ModuleContext context) {
        context.route(new RouteContribution("GET", "/health/live", (BaseHandler) HealthModule::live));
        context.route(new RouteContribution("GET", "/health/ready", (BaseHandler) HealthModule::ready));
        context.route(new RouteContribution("GET", "/health", (BaseHandler) HealthModule::aggregate));
    }

    private static void live(BaseContext context) {
        AuraWebRuntime runtime = runtime(context);
        boolean live = runtime != null && !runtime.isShuttingDown();
        respond(context, live, live ? 200 : 503);
    }

    private static void ready(BaseContext context) {
        AuraWebRuntime runtime = runtime(context);
        boolean ready = runtime != null && runtime.isRunning()
                && runtime.healthStatus() == HealthStatus.UP;
        respond(context, ready, ready ? 200 : 503);
    }

    private static void aggregate(BaseContext context) {
        AuraWebRuntime runtime = runtime(context);
        boolean up = runtime != null && !runtime.isShuttingDown()
                && runtime.healthStatus() == HealthStatus.UP;
        respond(context, up, up ? 200 : 503);
    }

    private static AuraWebRuntime runtime(BaseContext context) {
        return context.get("_app", AuraWebRuntime.class);
    }

    private static void respond(BaseContext context, boolean up, int status) {
        context.status(status).json(java.util.Map.of(STATUS, up ? "UP" : "DOWN"));
    }
}

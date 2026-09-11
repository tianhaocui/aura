package io.aura.web;

/** Shared response policy for reflective and generated route handlers. */
public final class RouteResponse {
    private RouteResponse() { }

    /** Applies the same null, committed-response, and result-wrapper rules to a route result. */
    public static void write(BaseContext context, Object result) {
        if (context == null || context.isResponseStarted()) return;
        io.aura.AuraWebRuntime app = context.app();
        if (app != null && app.resultWrapper() != null) {
            context.json(app.resultWrapper().apply(result));
            return;
        }
        if (result != null) context.json(result);
    }
}

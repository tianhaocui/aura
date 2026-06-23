package io.aura.web;

import java.util.List;

class RouteExecutor {

    @FunctionalInterface
    interface ExceptionHandler {
        void handle(Exception e, BaseContext ctx);
    }

    static void execute(CompiledRoute route, BaseContext ctx, ExceptionHandler onError) {
        try {
            for (BaseHandler mw : route.beforeHandlers()) {
                mw.handle(ctx);
                if (ctx.isAborted()) break;
            }
            if (!ctx.isAborted()) {
                route.handler().handle(ctx);
            } else if (ctx.statusCode() == 200) {
                ctx.status(403);
            }
        } catch (Exception e) {
            onError.handle(e, ctx);
        } finally {
            for (BaseHandler h : route.afterHandlers()) {
                try { h.handle(ctx); } catch (Exception ignored) {}
            }
        }
    }
}

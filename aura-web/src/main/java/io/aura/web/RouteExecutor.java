package io.aura.web;

import io.aura.port.BaseContext;
import io.aura.port.BaseHandler;
import java.util.List;

class RouteExecutor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RouteExecutor.class);

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
                try {
                    h.handle(ctx);
                } catch (Exception error) {
                    // The response may already be committed, so logging is the only signal left.
                    log.warn("After-handler failed for {} {}", route.method(), route.rawPath(), error);
                }
            }
        }
    }
}

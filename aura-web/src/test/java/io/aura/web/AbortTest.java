package io.aura.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AbortTest {

    @Test
    void abort_defaults403_whenNoStatusSet() {
        Router router = new Router();
        router.before(ctx -> ctx.abort());
        router.get("/protected", ctx -> ctx.text("should not reach"));

        var client = TestApplications.client(router);
        var resp = client.get("/protected").execute();
        assertThat(resp.status()).isEqualTo(403);
    }

    @Test
    void abort_preservesExplicitStatus() {
        Router router = new Router();
        router.before(ctx -> { ctx.status(401); ctx.abort(); });
        router.get("/protected", ctx -> ctx.text("should not reach"));

        var client = TestApplications.client(router);
        var resp = client.get("/protected").execute();
        assertThat(resp.status()).isEqualTo(401);
    }

    @Test
    void abort_preventsHandlerExecution() {
        Router router = new Router();
        java.util.concurrent.atomic.AtomicBoolean handlerCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        router.before(ctx -> ctx.abort());
        router.get("/test", ctx -> handlerCalled.set(true));

        var client = TestApplications.client(router);
        client.get("/test").execute();
        assertThat(handlerCalled.get()).isFalse();
    }

    @Test
    void abort_inSecondMiddleware_preventsHandler() {
        Router router = new Router();
        java.util.concurrent.atomic.AtomicInteger middlewareCount = new java.util.concurrent.atomic.AtomicInteger(0);
        router.before(ctx -> middlewareCount.incrementAndGet());
        router.before(ctx -> { middlewareCount.incrementAndGet(); ctx.abort(); });
        router.before(ctx -> middlewareCount.incrementAndGet());
        router.get("/test", ctx -> ctx.text("ok"));

        var client = TestApplications.client(router);
        var resp = client.get("/test").execute();
        assertThat(resp.status()).isEqualTo(403);
        assertThat(middlewareCount.get()).isEqualTo(2);
    }
}

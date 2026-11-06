package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppMiddlewareTest {

    @Test
    void beforeHandlers_runInRegistrationOrder() {
        List<String> log = new ArrayList<>();
        Router router = new Router();
        router.before(ctx -> log.add("before-1"));
        router.before(ctx -> log.add("before-2"));
        router.get("/test", ctx -> {
            log.add("handler");
            ctx.text("ok");
        });
        var client = TestApplications.client(router);

        client.get("/test").expect(200);
        assertThat(log).containsExactly("before-1", "before-2", "handler");
    }

    @Test
    void afterHandlers_runInRegistrationOrder() {
        List<String> log = new ArrayList<>();
        Router router = new Router();
        router.after(ctx -> log.add("after-1"));
        router.after(ctx -> log.add("after-2"));
        router.get("/test", ctx -> {
            log.add("handler");
            ctx.text("ok");
        });
        var client = TestApplications.client(router);

        client.get("/test").expect(200);
        assertThat(log).containsExactly("handler", "after-1", "after-2");
    }

    @Test
    void beforeAndAfter_withMultipleHandlers() {
        List<String> log = new ArrayList<>();
        Router router = new Router();
        router.before(ctx -> log.add("b1"));
        router.before(ctx -> log.add("b2"));
        router.after(ctx -> log.add("a1"));
        router.after(ctx -> log.add("a2"));
        router.get("/test", ctx -> {
            log.add("handler");
            ctx.text("ok");
        });
        var client = TestApplications.client(router);

        client.get("/test").expect(200);
        assertThat(log).containsExactly("b1", "b2", "handler", "a1", "a2");
    }

    @Test
    void beforeHandler_canAbortRequest() {
        Router router = new Router();
        router.before(ctx -> {
            ctx.status(403).text("forbidden");
            ctx.abort();
        });
        router.get("/secret", ctx -> ctx.text("secret"));
        var client = TestApplications.client(router);

        var resp = client.get("/secret").execute();
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.body()).isEqualTo("forbidden");
    }
}

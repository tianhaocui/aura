package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppMiddlewareTest {

    @Test
    void appBefore_runsBeforeRouterBefore() {
        List<String> log = new ArrayList<>();
        var app = Aura.create()
            .before(ctx -> log.add("app-before"))
            .routes((BaseRouter r) -> {
                r.before(ctx -> log.add("router-before"));
                r.get("/test", ctx -> {
                    log.add("handler");
                    ctx.text("ok");
                });
            });
        var client = TestClient.of(app);

        client.get("/test").expect(200);
        assertThat(log).containsExactly("app-before", "router-before", "handler");
    }

    @Test
    void appAfter_runsAfterRouterAfter() {
        List<String> log = new ArrayList<>();
        var app = Aura.create()
            .after(ctx -> log.add("app-after"))
            .routes((BaseRouter r) -> {
                r.after(ctx -> log.add("router-after"));
                r.get("/test", ctx -> {
                    log.add("handler");
                    ctx.text("ok");
                });
            });
        var client = TestClient.of(app);

        client.get("/test").expect(200);
        assertThat(log).containsExactly("handler", "app-after", "router-after");
    }

    @Test
    void appBeforeAndAfter_withMultipleHandlers() {
        List<String> log = new ArrayList<>();
        var app = Aura.create()
            .before(ctx -> log.add("b1"))
            .before(ctx -> log.add("b2"))
            .after(ctx -> log.add("a1"))
            .after(ctx -> log.add("a2"))
            .routes((BaseRouter r) -> r.get("/test", ctx -> {
                log.add("handler");
                ctx.text("ok");
            }));
        var client = TestClient.of(app);

        client.get("/test").expect(200);
        assertThat(log).containsExactly("b1", "b2", "handler", "a1", "a2");
    }

    @Test
    void appBefore_canAbortRequest() {
        var app = Aura.create()
            .before(ctx -> {
                ctx.status(403).text("forbidden");
                ctx.abort();
            })
            .routes((BaseRouter r) -> r.get("/secret", ctx -> ctx.text("secret")));
        var client = TestClient.of(app);

        var resp = client.get("/secret").execute();
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.body()).isEqualTo("forbidden");
    }
}

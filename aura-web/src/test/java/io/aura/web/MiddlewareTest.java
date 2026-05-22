package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MiddlewareTest {

    private final List<String> log = new ArrayList<>();
    private TestClient client;

    @BeforeEach
    void setup() {
        log.clear();
        Aura app = Aura.create()
            .routes((BaseRouter r) -> {
                r.before(ctx -> log.add("global-before"));
                r.after(ctx -> log.add("global-after"));

                r.get("/hello", ctx -> {
                    log.add("handler");
                    ctx.text("hi");
                });

                r.group("/api", api -> {
                    api.before(ctx -> log.add("api-before"));
                    api.after(ctx -> log.add("api-after"));

                    api.get("/test", ctx -> {
                        log.add("api-handler");
                        ctx.text("api ok");
                    });
                });

                r.exception(RuntimeException.class, (e, ctx) -> {
                    ctx.status(400).text("error: " + e.getMessage());
                });

                r.get("/fail", ctx -> {
                    throw new RuntimeException("boom");
                });
            });
        client = TestClient.of(app);
    }

    @Test
    void globalMiddleware_executesInOrder() {
        var resp = client.get("/hello").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("hi");
        assertThat(log).containsExactly("global-before", "handler", "global-after");
    }

    @Test
    void groupMiddleware_executesWithGlobal() {
        var resp = client.get("/api/test").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("api ok");
        assertThat(log).containsExactly("global-before", "api-before", "api-handler", "global-after", "api-after");
    }

    @Test
    void exceptionHandler_catches() {
        var resp = client.get("/fail").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).isEqualTo("error: boom");
    }

    @Test
    void notFound_returns404() {
        var resp = client.get("/notfound").execute();
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body()).isEqualTo("Not Found");
    }
}

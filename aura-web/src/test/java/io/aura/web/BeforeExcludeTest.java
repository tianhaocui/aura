package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BeforeExcludeTest {

    @Test
    void exclude_exactPath_bypassesMiddleware() {
        List<String> log = new ArrayList<>();
        var app = Aura.create()
            .before(ctx -> log.add("auth"))
            .exclude("/health")
            .routes((BaseRouter r) -> {
                r.get("/health", ctx -> ctx.text("ok"));
                r.get("/api/data", ctx -> ctx.text("data"));
            });
        var client = TestClient.of(app);

        client.get("/health").expect(200);
        assertThat(log).isEmpty();

        client.get("/api/data").expect(200);
        assertThat(log).containsExactly("auth");
    }

    @Test
    void exclude_wildcardPath_bypassesMiddleware() {
        List<String> log = new ArrayList<>();
        var app = Aura.create()
            .before(ctx -> log.add("auth"))
            .exclude("/health*")
            .routes((BaseRouter r) -> {
                r.get("/health", ctx -> ctx.text("ok"));
                r.get("/health/deep", ctx -> ctx.text("deep"));
                r.get("/api/test", ctx -> ctx.text("test"));
            });
        var client = TestClient.of(app);

        client.get("/health").expect(200);
        assertThat(log).isEmpty();

        client.get("/health/deep").expect(200);
        assertThat(log).isEmpty();

        client.get("/api/test").expect(200);
        assertThat(log).containsExactly("auth");
    }

    @Test
    void exclude_multipleExcludes() {
        List<String> log = new ArrayList<>();
        var app = Aura.create()
            .before(ctx -> log.add("auth"))
            .exclude("/health", "/ping", "/api/adapter/*")
            .routes((BaseRouter r) -> {
                r.get("/health", ctx -> ctx.text("ok"));
                r.get("/ping", ctx -> ctx.text("pong"));
                r.get("/api/adapter/health", ctx -> ctx.text("adapter"));
                r.get("/api/users", ctx -> ctx.text("users"));
            });
        var client = TestClient.of(app);

        client.get("/health").expect(200);
        client.get("/ping").expect(200);
        client.get("/api/adapter/health").expect(200);
        assertThat(log).isEmpty();

        client.get("/api/users").expect(200);
        assertThat(log).containsExactly("auth");
    }

    @Test
    void before_chainingStillWorks() {
        List<String> log = new ArrayList<>();
        var app = Aura.create()
            .before(ctx -> log.add("b1"))
            .before(ctx -> log.add("b2"))
            .routes((BaseRouter r) -> r.get("/test", ctx -> ctx.text("ok")));
        var client = TestClient.of(app);

        client.get("/test").expect(200);
        assertThat(log).containsExactly("b1", "b2");
    }

    @Test
    void exclude_doesNotAffectOtherBeforeHandlers() {
        List<String> log = new ArrayList<>();
        var app = Aura.create()
            .before(ctx -> log.add("logging"))
            .before(ctx -> log.add("auth"))
            .exclude("/health")
            .routes((BaseRouter r) -> {
                r.get("/health", ctx -> ctx.text("ok"));
                r.get("/api", ctx -> ctx.text("api"));
            });
        var client = TestClient.of(app);

        // /health: logging runs, auth is excluded
        client.get("/health").expect(200);
        assertThat(log).containsExactly("logging");

        log.clear();
        // /api: both run
        client.get("/api").expect(200);
        assertThat(log).containsExactly("logging", "auth");
    }
}

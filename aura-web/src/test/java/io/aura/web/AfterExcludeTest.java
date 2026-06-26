package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AfterExcludeTest {

    @Test
    void afterExclude_skipsExcludedPath() {
        List<String> log = new ArrayList<>();
        Aura app = Aura.create();
        app.after(ctx -> log.add("after:" + ctx.url())).exclude("/health");
        app.get("/health", (BaseHandler) ctx -> ctx.text("ok"));
        app.get("/data", (BaseHandler) ctx -> ctx.text("data"));

        var client = TestClient.of(app);
        client.get("/health").execute();
        client.get("/data").execute();

        assertThat(log).containsExactly("after:/data");
    }

    @Test
    void afterExclude_wildcardWorks() {
        List<String> log = new ArrayList<>();
        Aura app = Aura.create();
        app.after(ctx -> log.add("after")).exclude("/api/internal/*");
        app.get("/api/internal/ping", (BaseHandler) ctx -> ctx.text("pong"));
        app.get("/api/public/items", (BaseHandler) ctx -> ctx.text("items"));

        var client = TestClient.of(app);
        client.get("/api/internal/ping").execute();
        client.get("/api/public/items").execute();

        assertThat(log).containsExactly("after");
    }

    @Test
    void afterWithoutExclude_runsOnAllPaths() {
        List<String> log = new ArrayList<>();
        Aura app = Aura.create();
        app.after(ctx -> log.add("after"));
        app.get("/a", (BaseHandler) ctx -> ctx.text("a"));
        app.get("/b", (BaseHandler) ctx -> ctx.text("b"));

        var client = TestClient.of(app);
        client.get("/a").execute();
        client.get("/b").execute();

        assertThat(log).hasSize(2);
    }

    @Test
    void afterExclude_chainingWorks() {
        List<String> log = new ArrayList<>();
        Aura app = Aura.create();
        app.after(ctx -> log.add("after")).exclude("/health").and()
           .get("/health", (BaseHandler) ctx -> ctx.text("ok"))
           .get("/api", (BaseHandler) ctx -> ctx.text("api"));

        var client = TestClient.of(app);
        client.get("/health").execute();
        client.get("/api").execute();

        assertThat(log).containsExactly("after");
    }
}

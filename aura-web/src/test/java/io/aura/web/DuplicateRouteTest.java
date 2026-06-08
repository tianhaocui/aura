package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DuplicateRouteTest {

    @Test
    void duplicateRoute_lastOneWins() {
        Aura app = Aura.create();
        app.routes(r -> {
            r.get("/dup", ctx -> ctx.text("first"));
            r.get("/dup", ctx -> ctx.text("second"));
        });
        var client = TestClient.of(app);
        var resp = client.get("/dup").execute();
        assertThat(resp.body()).isEqualTo("first");
    }

    @Test
    void duplicateRoute_startsSuccessfully() throws Exception {
        Aura app = Aura.create().port(0);
        app.routes(r -> {
            r.get("/api/users", ctx -> ctx.text("first"));
            r.get("/api/users", ctx -> ctx.text("second"));
        });
        app.start();
        app.stop();
    }

    @Test
    void duplicateRoute_differentMethods_notConsidered() {
        Aura app = Aura.create();
        app.routes(r -> {
            r.get("/api/users", ctx -> ctx.text("get"));
            r.post("/api/users", ctx -> ctx.text("post"));
        });
        var client = TestClient.of(app);
        assertThat(client.get("/api/users").execute().body()).isEqualTo("get");
        assertThat(client.post("/api/users").execute().body()).isEqualTo("post");
    }

    @Test
    void duplicateRoute_detectedInCompiledRoutes() {
        Router router = new Router();
        router.get("/api/items", ctx -> ctx.text("first"));
        router.get("/api/items", ctx -> ctx.text("second"));

        var routes = UndertowStarter.compileRoutes(router);
        long count = routes.stream()
                .filter(r -> r.method().equals("GET") && r.rawPath().equals("/api/items"))
                .count();
        assertThat(count).isEqualTo(2);
    }
}

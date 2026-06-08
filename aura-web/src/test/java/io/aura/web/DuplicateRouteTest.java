package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DuplicateRouteTest {

    @Test
    void duplicateRoute_throwsOnStart() {
        Aura app = Aura.create().port(0);
        app.routes(r -> {
            r.get("/api/users", ctx -> ctx.text("first"));
            r.get("/api/users", ctx -> ctx.text("second"));
        });
        assertThatThrownBy(app::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate route: GET /api/users");
    }

    @Test
    void differentMethods_samePath_noDuplicate() throws Exception {
        Aura app = Aura.create().port(0);
        app.routes(r -> {
            r.get("/api/users", ctx -> ctx.text("get"));
            r.post("/api/users", ctx -> ctx.text("post"));
        });
        app.start();
        app.stop();
    }

    @Test
    void differentPaths_sameMethod_noDuplicate() throws Exception {
        Aura app = Aura.create().port(0);
        app.routes(r -> {
            r.get("/api/users", ctx -> ctx.text("users"));
            r.get("/api/items", ctx -> ctx.text("items"));
        });
        app.start();
        app.stop();
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

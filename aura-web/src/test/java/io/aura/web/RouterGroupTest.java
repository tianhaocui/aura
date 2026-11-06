package io.aura.web;

import io.aura.port.BaseHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RouterGroupTest {

    @Test
    void group_supportsExplicitRoutes() {
        Router router = new Router();

        router.group("/api", api -> {
            api.get("/user/{id}", ctx -> ctx.text("user"));
            api.get("/user", ctx -> ctx.text("all"));
        });

        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);

        assertThat(routes).hasSize(2);
        assertThat(routes).extracting(CompiledRoute::rawPath)
                .containsExactlyInAnyOrder("/api/user/{id}", "/api/user");
    }

    @Test
    void group_inheritsBeforeHandlers() {
        Router router = new Router();
        BaseHandler auth = ctx -> ctx.set("authed", true);

        router.group("/api", api -> {
            api.before(auth);
            api.get("/test", ctx -> {});
        });

        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes.get(0).beforeHandlers()).contains(auth);
    }

    @Test
    void nestedGroup_combinesPrefixes() {
        Router router = new Router();

        router.group("/api", api -> {
            api.group("/v1", v1 -> {
                v1.get("/users", ctx -> {});
            });
        });

        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes.get(0).rawPath()).isEqualTo("/api/v1/users");
    }
}

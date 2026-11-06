package io.aura.web;

import io.aura.port.BaseRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GroupServiceRoutingTest {

    @Test
    void group_supportsExplicitRoutes() {
        Router router = new Router();

        router.group("/api", api -> {
            api.get("/user/{id}", ctx -> ctx.text("user"));
            api.get("/user", ctx -> ctx.json(List.of("a", "b")));
        });

        assertThat(router.groups).hasSize(1);
        BaseRouter sub = router.groups.get(0).router();
        assertThat(sub).isInstanceOf(Router.class);
        assertThat(sub.routeBuilders).hasSize(2);
    }

    @Test
    void group_inheritsBeforeHandlers() {
        Router router = new Router();

        router.group("/api", api -> {
            api.before(ctx -> {});
            api.get("/user/{id}", ctx -> ctx.text("user"));
        });

        BaseRouter sub = router.groups.get(0).router();
        assertThat(sub.beforeHandlers).hasSize(1);
        assertThat(sub.routeBuilders).hasSize(1);
    }

    @Test
    void group_compilesWithExplicitRoutes() {
        Router router = new Router();

        router.group("/api", api -> {
            api.get("/user/{id}", ctx -> ctx.text("user"));
        });

        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).rawPath()).isEqualTo("/api/user/{id}");
        assertThat(routes.get(0).handler()).isNotNull();
    }
}

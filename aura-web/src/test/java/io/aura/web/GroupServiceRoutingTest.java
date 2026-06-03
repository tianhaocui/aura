package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GroupServiceRoutingTest {

    static class UserService {
        public String get(int id) { return "user-" + id; }
        public List<String> list() { return List.of("a", "b"); }
    }

    @Test
    void group_supportsServiceMethodRouting() {
        Router router = new Router();
        UserService svc = new UserService();

        router.group("/api", api -> {
            api.get("/user/{id}", svc, "get");
            api.get("/user", svc, "list");
        });

        assertThat(router.groups).hasSize(1);
        BaseRouter sub = router.groups.get(0).router();
        assertThat(sub).isInstanceOf(Router.class);
        assertThat(sub.routeBuilders).hasSize(2);
    }

    @Test
    void group_inheritsBeforeHandlers() {
        Router router = new Router();
        UserService svc = new UserService();

        router.group("/api", api -> {
            api.before(ctx -> {});
            api.get("/user/{id}", svc, "get");
        });

        BaseRouter sub = router.groups.get(0).router();
        assertThat(sub.beforeHandlers).hasSize(1);
        assertThat(sub.routeBuilders).hasSize(1);
    }

    @Test
    void group_compilesWithServiceMethodRoutes() {
        Router router = new Router();
        UserService svc = new UserService();

        router.group("/api", api -> {
            api.get("/user/{id}", svc, "get");
        });

        List<CompiledRoute> routes = UndertowStarter.compileRoutes(router);
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).rawPath()).isEqualTo("/api/user/{id}");
        assertThat(routes.get(0).handler()).isInstanceOf(MethodRefHandler.class);
    }
}

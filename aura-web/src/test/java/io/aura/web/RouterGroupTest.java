package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RouterGroupTest {

    public static class FakeService {
        public String get(int id) { return "user-" + id; }
        public String list() { return "all"; }
    }

    @Test
    void group_supportsServiceMethodRouting() {
        Router router = new Router();
        FakeService service = new FakeService();

        router.group("/api", api -> {
            api.get("/user/{id}", service, "get");
            api.get("/user", service, "list");
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

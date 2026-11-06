package io.aura.web;

import io.aura.port.BaseHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class RouterTest {

    @Test
    void get_registersGetRoute() {
        Router router = new Router();
        router.get("/hello", ctx -> {});
        assertThat(router.routeBuilders).hasSize(1);
        assertThat(router.routeBuilders.get(0).route.method()).isEqualTo("GET");
        assertThat(router.routeBuilders.get(0).route.path()).isEqualTo("/hello");
    }

    @Test
    void post_registersPostRoute() {
        Router router = new Router();
        router.post("/items", ctx -> {});
        assertThat(router.routeBuilders).hasSize(1);
        assertThat(router.routeBuilders.get(0).route.method()).isEqualTo("POST");
    }

    @Test
    void put_registersPutRoute() {
        Router router = new Router();
        router.put("/items/{id}", ctx -> {});
        assertThat(router.routeBuilders).hasSize(1);
        assertThat(router.routeBuilders.get(0).route.method()).isEqualTo("PUT");
    }

    @Test
    void delete_registersDeleteRoute() {
        Router router = new Router();
        router.delete("/items/{id}", ctx -> {});
        assertThat(router.routeBuilders).hasSize(1);
        assertThat(router.routeBuilders.get(0).route.method()).isEqualTo("DELETE");
    }

    @Test
    void before_addsToBeforeHandlers() {
        Router router = new Router();
        BaseHandler h = ctx -> {};
        router.before(h);
        assertThat(router.beforeHandlers).containsExactly(h);
    }

    @Test
    void after_addsToAfterHandlers() {
        Router router = new Router();
        BaseHandler h = ctx -> {};
        router.after(h);
        assertThat(router.afterHandlers).containsExactly(h);
    }

    @Test
    void group_createsNestedRouterWithPrefix() {
        Router router = new Router();
        router.group("/api", sub -> sub.get("/users", ctx -> {}));
        assertThat(router.groups).hasSize(1);
        assertThat(router.groups.get(0).prefix()).isEqualTo("/api");
        assertThat(router.groups.get(0).router().routeBuilders).hasSize(1);
        assertThat(router.groups.get(0).router().routeBuilders.get(0).route.path()).isEqualTo("/users");
    }
}

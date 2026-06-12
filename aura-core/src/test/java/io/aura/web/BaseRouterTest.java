package io.aura.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BaseRouterTest {

    @Test
    void get_registersRoute() {
        BaseRouter router = new BaseRouter();
        router.get("/hello", ctx -> {});
        assertThat(router.routeBuilders).hasSize(1);
        assertThat(router.routeBuilders.get(0).route.method()).isEqualTo("GET");
        assertThat(router.routeBuilders.get(0).route.path()).isEqualTo("/hello");
    }

    @Test
    void post_registersRoute() {
        BaseRouter router = new BaseRouter();
        router.post("/items", ctx -> {});
        assertThat(router.routeBuilders).hasSize(1);
        assertThat(router.routeBuilders.get(0).route.method()).isEqualTo("POST");
    }

    @Test
    void put_registersRoute() {
        BaseRouter router = new BaseRouter();
        router.put("/items/{id}", ctx -> {});
        assertThat(router.routeBuilders).hasSize(1);
        assertThat(router.routeBuilders.get(0).route.method()).isEqualTo("PUT");
    }

    @Test
    void delete_registersRoute() {
        BaseRouter router = new BaseRouter();
        router.delete("/items/{id}", ctx -> {});
        assertThat(router.routeBuilders).hasSize(1);
        assertThat(router.routeBuilders.get(0).route.method()).isEqualTo("DELETE");
    }

    @Test
    void before_addsHandler() {
        BaseRouter router = new BaseRouter();
        BaseHandler h = ctx -> {};
        router.before(h);
        assertThat(router.beforeHandlers).containsExactly(h);
    }

    @Test
    void after_addsHandler() {
        BaseRouter router = new BaseRouter();
        BaseHandler h = ctx -> {};
        router.after(h);
        assertThat(router.afterHandlers).containsExactly(h);
    }

    @Test
    void group_createsSubRouter() {
        BaseRouter router = new BaseRouter();
        router.group("/api", sub -> {
            sub.get("/test", ctx -> {});
        });
        assertThat(router.groups).hasSize(1);
        assertThat(router.groups.get(0).prefix()).isEqualTo("/api");
        assertThat(router.groups.get(0).router().routeBuilders).hasSize(1);
    }

    @Test
    void crud_throwsUnsupported() {
        BaseRouter router = new BaseRouter();
        assertThatThrownBy(() -> router.crud("/item", new Object()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void crud_withMethods_throwsUnsupported() {
        BaseRouter router = new BaseRouter();
        assertThatThrownBy(() -> router.crud("/item", new Object(), "get", "list"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void serviceMethodRouting_get_throwsUnsupported() {
        BaseRouter router = new BaseRouter();
        assertThatThrownBy(() -> router.get("/path", new Object(), "method"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void serviceMethodRouting_post_throwsUnsupported() {
        BaseRouter router = new BaseRouter();
        assertThatThrownBy(() -> router.post("/path", new Object(), "method"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void serviceMethodRouting_put_throwsUnsupported() {
        BaseRouter router = new BaseRouter();
        assertThatThrownBy(() -> router.put("/path", new Object(), "method"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void serviceMethodRouting_delete_throwsUnsupported() {
        BaseRouter router = new BaseRouter();
        assertThatThrownBy(() -> router.delete("/path", new Object(), "method"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void addRoute_returnsBuilder_withDescribe() {
        BaseRouter router = new BaseRouter();
        BaseRouteBuilder rb = router.addRoute("GET", "/test", ctx -> {});
        rb.describe("A test route").param("id", "The ID");
        assertThat(rb.description).isEqualTo("A test route");
        assertThat(rb.paramDescriptions).containsEntry("id", "The ID");
    }

    @Test
    void uploadedFile_accessors() {
        UploadedFile f = new UploadedFile("photo.png", new byte[1024], "image/png");
        assertThat(f.name()).isEqualTo("photo.png");
        assertThat(f.size()).isEqualTo(1024);
        assertThat(f.contentType()).isEqualTo("image/png");
        assertThat(f.data()).hasSize(1024);
    }

    @Test
    void uploadedFile_nullData_sizeIsZero() {
        UploadedFile f = new UploadedFile("empty.txt", null, "text/plain");
        assertThat(f.size()).isEqualTo(0);
    }
}
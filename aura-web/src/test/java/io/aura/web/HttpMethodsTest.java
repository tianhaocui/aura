package io.aura.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMethodsTest {

    @Test
    void patch_routeWorks() {
        Router router = new Router();
        router.patch("/items/{id}", ctx -> ctx.text("patched " + ctx.path("id")));
        var client = TestApplications.client(router);

        var resp = client.patch("/items/42").expect(200);
        assertThat(resp.body()).isEqualTo("patched 42");
    }

    @Test
    void patch_inRouterBlock() {
        Router router = new Router();
        router.patch("/user/{id}", ctx -> ctx.text("updated"));
        var client = TestApplications.client(router);

        client.patch("/user/1").expect(200).bodyContains("updated");
    }

    @Test
    void head_autoFallbackFromGet() {
        Router router = new Router();
        router.get("/file", ctx -> ctx.text("content"));
        var client = TestApplications.client(router);

        var resp = client.head("/file").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isNull();
    }

    @Test
    void head_explicitRouteOverridesAutoFallback() {
        Router router = new Router();
        router.get("/file", ctx -> ctx.text("content"));
        router.head("/file", ctx -> ctx.header("X-Size", "123").status(200));
        var client = TestApplications.client(router);

        var resp = client.head("/file").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.header("X-Size")).isEqualTo("123");
    }

    @Test
    void options_routeWorks() {
        Router router = new Router();
        router.options("/api", ctx -> {
            ctx.header("Allow", "GET, POST, PATCH");
            ctx.status(204);
        });
        var client = TestApplications.client(router);

        var resp = client.options("/api").execute();
        assertThat(resp.status()).isEqualTo(204);
        assertThat(resp.header("Allow")).isEqualTo("GET, POST, PATCH");
    }

    @Test
    void options_appLevel() {
        Router router = new Router();
        router.options("/info", ctx -> ctx.status(204));
        var client = TestApplications.client(router);

        client.options("/info").expect(204);
    }

    @Test
    void head_fallback_preservesErrorStatus() {
        Router router = new Router();
        router.get("/fail", ctx -> { throw new RuntimeException("boom"); });
        var client = TestApplications.client(router);

        var resp = client.head("/fail").execute();
        assertThat(resp.status()).isEqualTo(500);
        assertThat(resp.body()).isNull();
    }

    @Test
    void head_fallback_runsAfterHandlers() {
        Router router = new Router();
        router.after(ctx -> ctx.header("X-After", "ran"));
        router.get("/tracked", ctx -> ctx.text("ok"));
        var client = TestApplications.client(router);

        var resp = client.head("/tracked").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.header("X-After")).isEqualTo("ran");
    }
}

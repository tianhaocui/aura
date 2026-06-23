package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMethodsTest {

    @Test
    void patch_routeWorks() {
        var app = Aura.create()
            .patch("/items/{id}", (BaseHandler) ctx -> {
                ctx.text("patched " + ctx.path("id"));
            });
        var client = TestClient.of(app);

        var resp = client.patch("/items/42").expect(200);
        assertThat(resp.body()).isEqualTo("patched 42");
    }

    @Test
    void patch_inRouterBlock() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.patch("/user/{id}", ctx -> ctx.text("updated")));
        var client = TestClient.of(app);

        client.patch("/user/1").expect(200).bodyContains("updated");
    }

    @Test
    void head_autoFallbackFromGet() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.get("/file", ctx -> ctx.text("content")));
        var client = TestClient.of(app);

        var resp = client.head("/file").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isNull();
    }

    @Test
    void head_explicitRouteOverridesAutoFallback() {
        var app = Aura.create()
            .routes((BaseRouter r) -> {
                r.get("/file", ctx -> ctx.text("content"));
                r.head("/file", ctx -> ctx.header("X-Size", "123").status(200));
            });
        var client = TestClient.of(app);

        var resp = client.head("/file").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.header("X-Size")).isEqualTo("123");
    }

    @Test
    void options_routeWorks() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.options("/api", ctx -> {
                ctx.header("Allow", "GET, POST, PATCH");
                ctx.status(204);
            }));
        var client = TestClient.of(app);

        var resp = client.options("/api").execute();
        assertThat(resp.status()).isEqualTo(204);
        assertThat(resp.header("Allow")).isEqualTo("GET, POST, PATCH");
    }

    @Test
    void options_appLevel() {
        var app = Aura.create()
            .options("/info", (BaseHandler) ctx -> ctx.status(204));
        var client = TestClient.of(app);

        client.options("/info").expect(204);
    }
}

package io.aura.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredParamsTest {

    @Test
    void queryRequired_returnsValue() {
        var client = client("/search", ctx -> ctx.text(ctx.queryRequired("q")));

        var resp = client.get("/search?q=hello").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("hello");
    }

    @Test
    void queryRequired_throws_whenMissing() {
        var client = client("/search", ctx -> ctx.text(ctx.queryRequired("q")));

        var resp = client.get("/search").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("Missing required query parameter: q");
    }

    @Test
    void queryRequired_throws_whenBlank() {
        var client = client("/search", ctx -> ctx.text(ctx.queryRequired("q")));

        var resp = client.get("/search?q=").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("Missing required query parameter: q");
    }

    @Test
    void pathInt_returnsValue() {
        var client = client("/users/{id}", ctx -> ctx.json(ctx.pathInt("id")));

        var resp = client.get("/users/42").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("42");
    }

    @Test
    void pathInt_throws_whenNotNumber() {
        var client = client("/users/{id}", ctx -> ctx.json(ctx.pathInt("id")));

        var resp = client.get("/users/abc").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("must be an integer");
    }

    @Test
    void pathLong_returnsValue() {
        var client = client("/orders/{id}", ctx -> ctx.json(ctx.pathLong("id")));

        var resp = client.get("/orders/9999999999").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("9999999999");
    }

    @Test
    void pathLong_throws_whenNotNumber() {
        var client = client("/orders/{id}", ctx -> ctx.json(ctx.pathLong("id")));

        var resp = client.get("/orders/xyz").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("must be a long");
    }

    private static TestClient client(String path, BaseHandler handler) {
        Router router = new Router();
        router.get(path, handler);
        return TestApplications.client(router);
    }
}

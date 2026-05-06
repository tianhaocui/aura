package io.aura.web;

import io.aura.Aura;
import io.aura.Validate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TestClientTest {

    private static TestClient clientWithRoutes() {
        Aura app = Aura.create();
        Router router = new Router();
        router.get("/hello", ctx -> ctx.text("world"));
        router.post("/echo", ctx -> ctx.text(ctx.body(String.class)));
        router.get("/greet", ctx -> ctx.text("hi " + ctx.query("name")));
        router.exception(IllegalArgumentException.class, (e, ctx) -> {
            ctx.status(400);
            ctx.text("bad: " + e.getMessage());
        });
        router.get("/boom", ctx -> { throw new IllegalArgumentException("oops"); });
        return new TestClient(app, router);
    }

    @Test
    void get_returns200WithCorrectBody() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/hello").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("world");
    }

    @Test
    void post_withBody_works() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.post("/echo").body("\"hello\"").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("hello");
    }

    @Test
    void unknownPath_returns404() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/not-found").execute();
        assertThat(resp.status()).isEqualTo(404);
    }

    @Test
    void queryParams_parsedCorrectly() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/greet?name=Alice").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("hi Alice");
    }

    @Test
    void exceptionHandler_invoked() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/boom").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("oops");
    }

    @Test
    void expect_throwsAssertionErrorOnMismatch() {
        TestClient client = clientWithRoutes();
        assertThatThrownBy(() -> client.get("/hello").expect(404))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected 404");
    }

    @Test
    void bodyContains_throwsOnMissingText() {
        TestClient client = clientWithRoutes();
        TestClient.Response resp = client.get("/hello").execute();
        assertThatThrownBy(() -> resp.bodyContains("missing-text"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("missing-text");
    }

    @Test
    void expect_onResponse_passesWhenStatusMatches() {
        TestClient client = clientWithRoutes();
        assertThatCode(() -> client.get("/hello").execute().expect(200))
                .doesNotThrowAnyException();
    }

    @Test
    void validationException_returns400() {
        Aura app = Aura.create();
        Router router = new Router();
        router.get("/validate", ctx -> {
            Validate.notBlank("", "name is required");
        });
        TestClient client = new TestClient(app, router);
        TestClient.Response resp = client.get("/validate").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("name is required");
    }

    @Test
    void illegalArgumentException_returns400() {
        Aura app = Aura.create();
        Router router = new Router();
        router.get("/bad-param", ctx -> {
            throw new IllegalArgumentException("Invalid integer: abc");
        });
        TestClient client = new TestClient(app, router);
        TestClient.Response resp = client.get("/bad-param").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("Invalid integer");
    }
}

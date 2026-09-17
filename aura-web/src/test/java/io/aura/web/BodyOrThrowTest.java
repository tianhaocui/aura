package io.aura.web;

import io.aura.annotation.NotBlank;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodyOrThrowTest {

    @Test
    void returnsBody_whenValid() {
        Router router = new Router();
        router.post("/users", ctx -> {
            var req = ctx.bodyOrThrow(CreateReq.class);
            ctx.json(req);
        });
        var client = TestApplications.client(router);

        var resp = client.post("/users").body("{\"name\":\"Alice\"}").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("Alice");
    }

    @Test
    void throws_whenBodyNull() {
        Router router = new Router();
        router.post("/users", ctx -> {
            ctx.bodyOrThrow(CreateReq.class);
        });
        var client = TestApplications.client(router);

        var resp = client.post("/users").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("Request body is required");
    }

    @Test
    void throws_whenValidationFails() {
        Router router = new Router();
        router.post("/users", ctx -> {
            ctx.bodyOrThrow(ValidatedReq.class);
        });
        var client = TestApplications.client(router);

        var resp = client.post("/users").body("{\"name\":\"\"}").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("name");
    }

    @Test
    void structuredErrors_containsFieldAndMessage() {
        Router router = new Router();
        router.post("/users", ctx -> {
            ctx.bodyOrThrow(ValidatedReq.class);
        });
        var client = TestApplications.client(router);

        var resp = client.post("/users").body("{\"name\":\"\"}").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("\"error\"");
        assertThat(resp.body()).contains("\"errors\"");
        assertThat(resp.body()).contains("\"code\":\"VALIDATION_ERROR\"");
        assertThat(resp.body()).contains("\"field\"");
        assertThat(resp.body()).contains("\"message\"");
        assertThat(resp.body()).contains("\"reason\"");
        assertThat(resp.body()).contains("\"hint\"");
    }

    @Test
    void passesValidation_whenValid() {
        Router router = new Router();
        router.post("/users", ctx -> {
            var req = ctx.bodyOrThrow(ValidatedReq.class);
            ctx.text(req.name());
        });
        var client = TestApplications.client(router);

        var resp = client.post("/users").body("{\"name\":\"Bob\"}").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("Bob");
    }

    public record CreateReq(String name) {}
    public record ValidatedReq(@NotBlank String name) {}
}

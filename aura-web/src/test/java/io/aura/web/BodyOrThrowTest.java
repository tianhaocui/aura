package io.aura.web;

import io.aura.Aura;
import io.aura.Validatable;
import io.aura.annotation.NotBlank;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodyOrThrowTest {

    @Test
    void returnsBody_whenValid() {
        var app = Aura.create();
        app.routes(r -> r.post("/users", ctx -> {
            var req = ctx.bodyOrThrow(CreateReq.class);
            ctx.json(req);
        }));
        var client = TestClient.of(app);

        var resp = client.post("/users").body("{\"name\":\"Alice\"}").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("Alice");
    }

    @Test
    void throws_whenBodyNull() {
        var app = Aura.create();
        app.routes(r -> r.post("/users", ctx -> {
            ctx.bodyOrThrow(CreateReq.class);
        }));
        var client = TestClient.of(app);

        var resp = client.post("/users").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("Request body is required");
    }

    @Test
    void throws_whenValidationFails() {
        var app = Aura.create();
        app.routes(r -> r.post("/users", ctx -> {
            ctx.bodyOrThrow(ValidatedReq.class);
        }));
        var client = TestClient.of(app);

        var resp = client.post("/users").body("{\"name\":\"\"}").execute();
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).contains("name");
    }

    @Test
    void passesValidation_whenValid() {
        var app = Aura.create();
        app.routes(r -> r.post("/users", ctx -> {
            var req = ctx.bodyOrThrow(ValidatedReq.class);
            ctx.text(req.name());
        }));
        var client = TestClient.of(app);

        var resp = client.post("/users").body("{\"name\":\"Bob\"}").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("Bob");
    }

    public record CreateReq(String name) {}
    public record ValidatedReq(@NotBlank String name) {}
}

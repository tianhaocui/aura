package io.aura.web;

import io.aura.Aura;
import io.aura.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class NotFoundExceptionTest {

    @Test
    void notFoundException_returns404() {
        Aura app = Aura.create();
        app.get("/user", (BaseHandler) ctx -> {
            throw new NotFoundException("User not found: id=99");
        });

        var client = TestClient.of(app);
        var resp = client.get("/user").execute();
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body()).contains("User not found: id=99");
        assertThat(resp.body()).contains("NOT_FOUND");
    }

    @Test
    void notFoundException_customHandlerOverrides() {
        Aura app = Aura.create();
        app.exception(NotFoundException.class, (e, ctx) ->
                ctx.status(404).json(Map.of("code", 404, "msg", e.getMessage())));
        app.get("/item", (BaseHandler) ctx -> {
            throw new NotFoundException("Item 5 does not exist");
        });

        var client = TestClient.of(app);
        var resp = client.get("/item").execute();
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body()).contains("\"code\":404");
        assertThat(resp.body()).contains("Item 5 does not exist");
    }

    @Test
    void illegalArgException_still400() {
        Aura app = Aura.create();
        app.get("/bad", (Supplier<Object>) () -> { throw new IllegalArgumentException("bad input"); });

        var client = TestClient.of(app);
        var resp = client.get("/bad").execute();
        assertThat(resp.status()).isEqualTo(400);
    }
}

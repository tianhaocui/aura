package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class HealthEndpointTest {

    @Test
    void health_returnsUp() {
        var app = Aura.create().health();
        var client = TestClient.of(app);

        var resp = client.get("/health").execute();
        assertThat(resp.status()).isEqualTo(200);
        resp.expectJson("$.status", "UP");
    }

    @Test
    void health_returns503_afterShutdown() {
        var app = Aura.create().health();
        var client = TestClient.of(app);

        // Verify UP first
        client.get("/health").expect(200);

        // Simulate shutdown flag (stop() would normally do this but also shuts down starter)
        // Use reflection to set shuttingDown since stop() requires starter
        try {
            var field = Aura.class.getDeclaredField("shuttingDown");
            field.setAccessible(true);
            field.set(app, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        var resp = client.get("/health").execute();
        assertThat(resp.status()).isEqualTo(503);
        resp.expectJson("$.status", "DOWN");
    }

    @Test
    void isShuttingDown_initiallyFalse() {
        var app = Aura.create();
        assertThat(app.isShuttingDown()).isFalse();
    }
}

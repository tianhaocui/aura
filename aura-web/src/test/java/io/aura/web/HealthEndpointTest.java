package io.aura.web;

import io.aura.AuraApp;
import io.aura.HealthModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class HealthEndpointTest {

    @Test
    void health_returnsUp() {
        var app = AuraApp.create().use(HealthModule.create());
        var client = TestClient.of(app);

        var resp = client.get("/health").execute();
        assertThat(resp.status()).isEqualTo(200);
        resp.expectJson("$.status", "UP");
    }

    @Test
    void health_returns503_afterShutdown() {
        var app = AuraApp.create().use(HealthModule.create());
        var client = TestClient.of(app);

        // Verify UP first
        client.get("/health").expect(200);

        app.stop();

        var resp = client.get("/health").execute();
        assertThat(resp.status()).isEqualTo(503);
        resp.expectJson("$.status", "DOWN");
    }

    @Test
    void isShuttingDown_initiallyFalse() {
        var runtime = TestApplications.runtime();
        assertThat(runtime.isShuttingDown()).isFalse();
    }
}

package io.aura.web;

import io.aura.Aura;
import io.aura.annotation.Get;
import io.aura.annotation.Path;
import io.aura.annotation.RateLimit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class RateLimitTest {

    @Test
    void globalRateLimit_blocks4thRequest() {
        Aura app = Aura.create().env("prod").rateLimit(3, Duration.ofMinutes(1));
        app.get("/api", (Supplier<String>) () -> "ok");

        var client = TestClient.of(app);
        assertThat(client.get("/api").execute().status()).isEqualTo(200);
        assertThat(client.get("/api").execute().status()).isEqualTo(200);
        assertThat(client.get("/api").execute().status()).isEqualTo(200);

        var resp = client.get("/api").execute();
        assertThat(resp.status()).isEqualTo(429);
        assertThat(resp.body()).contains("Rate limit exceeded");
        assertThat(resp.body()).contains("\"retryAfter\"");
    }

    @Test
    void globalRateLimit_devModeDisabled() {
        Aura app = Aura.create().env("dev").rateLimit(2, Duration.ofMinutes(1));
        app.get("/api", (Supplier<String>) () -> "ok");

        var client = TestClient.of(app);
        assertThat(client.get("/api").execute().status()).isEqualTo(200);
        assertThat(client.get("/api").execute().status()).isEqualTo(200);
        assertThat(client.get("/api").execute().status()).isEqualTo(200);
    }

    @Path("/api")
    public static class LimitedController {
        @Get("/login")
        @RateLimit(2)
        public String login() { return "token"; }

        @Get("/data")
        public String data() { return "data"; }
    }

    @Test
    void methodRateLimit_blocks3rdRequest() {
        Aura app = Aura.create().env("prod");
        app.service(new LimitedController());

        var client = TestClient.of(app);
        assertThat(client.get("/api/login").execute().status()).isEqualTo(200);
        assertThat(client.get("/api/login").execute().status()).isEqualTo(200);

        var resp = client.get("/api/login").execute();
        assertThat(resp.status()).isEqualTo(429);
        assertThat(resp.body()).contains("Rate limit exceeded");

        // Other endpoints not affected
        assertThat(client.get("/api/data").execute().status()).isEqualTo(200);
    }

    @Path("/v2")
    public static class CustomWindowController {
        @Get("/heavy")
        @RateLimit(value = 1, window = 30)
        public String heavy() { return "result"; }
    }

    @Test
    void methodRateLimit_customWindow() {
        Aura app = Aura.create().env("prod");
        app.service(new CustomWindowController());

        var client = TestClient.of(app);
        assertThat(client.get("/v2/heavy").execute().status()).isEqualTo(200);

        var resp = client.get("/v2/heavy").execute();
        assertThat(resp.status()).isEqualTo(429);
        assertThat(resp.body()).contains("\"retryAfter\":30");
    }
}

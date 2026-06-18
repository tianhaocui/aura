package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CtxIpTest {

    @Test
    void ip_returnsSourceAddress() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));
        var client = TestClient.of(app);

        var resp = client.get("/ip").execute();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isNotBlank();
    }

    @Test
    void ip_prefersXForwardedFor() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));
        var client = TestClient.of(app);

        var resp = client.get("/ip").header("X-Forwarded-For", "203.0.113.50, 70.41.3.18").execute();
        assertThat(resp.body()).isEqualTo("203.0.113.50");
    }

    @Test
    void ip_xffSingleValue() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));
        var client = TestClient.of(app);

        var resp = client.get("/ip").header("X-Forwarded-For", "192.168.1.1").execute();
        assertThat(resp.body()).isEqualTo("192.168.1.1");
    }
}

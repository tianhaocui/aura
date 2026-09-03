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
    void ip_ignoresXForwardedForFromUntrustedPeer() {
        var app = Aura.create()
            .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));
        var client = TestClient.of(app);

        var resp = client.get("/ip").header("X-Forwarded-For", "203.0.113.50, 70.41.3.18").execute();
        assertThat(resp.body()).isEqualTo("127.0.0.1");
    }

    @Test
    void ip_usesXffOnlyWhenPeerIsTrusted() {
        var app = Aura.create().set("aura.trusted-proxies", "127.0.0.1")
            .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));
        var client = TestClient.of(app);

        var resp = client.get("/ip").header("X-Forwarded-For", "192.168.1.1").execute();
        assertThat(resp.body()).isEqualTo("192.168.1.1");
    }

    @Test
    void ip_usesNginxAppendedClientInsteadOfAttackerSpoof() {
        var app = Aura.create().set("aura.trusted-proxies", "127.0.0.1")
                .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));

        var resp = TestClient.of(app).get("/ip")
                .header("X-Forwarded-For", "198.51.100.77, 203.0.113.25")
                .execute();

        assertThat(resp.body()).isEqualTo("203.0.113.25");
    }

    @Test
    void ip_stripsMultipleTrustedProxyHopsFromRight() {
        var app = Aura.create().set("aura.trusted-proxies", "127.0.0.1, 10.0.0.0/8")
                .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));

        var resp = TestClient.of(app).get("/ip")
                .header("X-Forwarded-For", "203.0.113.25, 10.1.2.3, 10.4.5.6")
                .execute();

        assertThat(resp.body()).isEqualTo("203.0.113.25");
    }

    @Test
    void ip_supportsArbitraryTrustedProxyCidr() {
        var app = Aura.create().set("aura.trusted-proxies", "127.0.0.0/24")
                .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));

        var resp = TestClient.of(app).get("/ip")
                .header("X-Forwarded-For", "203.0.113.25")
                .execute();

        assertThat(resp.body()).isEqualTo("203.0.113.25");
    }

    @Test
    void ip_rejectsMalformedForwardedAddress() {
        var app = Aura.create().set("aura.trusted-proxies", "127.0.0.0/8")
                .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));

        var resp = TestClient.of(app).get("/ip")
                .header("X-Forwarded-For", "attacker.example")
                .execute();

        assertThat(resp.body()).isEqualTo("127.0.0.1");
    }

    @Test
    void ip_rejectsMalformedHopInTrustedPartOfChain() {
        var app = Aura.create().set("aura.trusted-proxies", "127.0.0.0/8, 10.0.0.0/8")
                .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));

        var resp = TestClient.of(app).get("/ip")
                .header("X-Forwarded-For", "203.0.113.25, 999.1.2.3, 10.1.2.3")
                .execute();

        assertThat(resp.body()).isEqualTo("127.0.0.1");
    }

    @Test
    void ip_fallsBackToPeerWhenEveryForwardedHopIsTrusted() {
        var app = Aura.create().set("aura.trusted-proxies", "127.0.0.0/8, 10.0.0.0/8")
                .routes((BaseRouter r) -> r.get("/ip", ctx -> ctx.text(ctx.ip())));

        var resp = TestClient.of(app).get("/ip")
                .header("X-Forwarded-For", "10.1.2.3, 10.4.5.6")
                .execute();

        assertThat(resp.body()).isEqualTo("127.0.0.1");
    }

    @Test
    void mockContextUsesSameForwardedChainResolverAsContext() {
        String forwardedFor = "198.51.100.77, 203.0.113.25";
        var app = Aura.create().set("aura.trusted-proxies", "127.0.0.1");
        var ctx = new MockContext(
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of("X-Forwarded-For", forwardedFor),
                null,
                app);

        assertThat(ctx.ip()).isEqualTo(Context.resolveClientIp(
                app.prop("aura.trusted-proxies"), "127.0.0.1", forwardedFor));
        assertThat(ctx.ip()).isEqualTo("203.0.113.25");
    }
}

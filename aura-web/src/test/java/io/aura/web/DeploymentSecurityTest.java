package io.aura.web;

import io.aura.port.BaseHandler;
import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentSecurityTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void defaultSecurityHeadersAreAppliedAndCanBeExplicitlyDisabled() throws Exception {
        AuraApp secure = app(new AuraConfig().port(0), "secure-headers");
        try {
            secure.start();
            HttpResponse<String> response = get(secure.boundPort());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
            assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        } finally {
            secure.stop();
        }

        AuraApp disabled = app(new AuraConfig().port(0).securityHeaders(false), "disabled-headers");
        try {
            disabled.start();
            HttpResponse<String> response = get(disabled.boundPort());
            assertThat(response.headers().firstValue("X-Content-Type-Options")).isEmpty();
            assertThat(response.headers().firstValue("X-Frame-Options")).isEmpty();
        } finally {
            disabled.stop();
        }
    }

    @Test
    void boundPortReportsTheEphemeralListenerAndIsUnboundAfterStop() throws Exception {
        AuraApp app = app(new AuraConfig().port(0), "bound-port");
        assertThat(app.boundPort()).isEqualTo(-1);
        try {
            app.start();
            assertThat(app.boundPort()).isPositive();
            assertThat(get(app.boundPort()).statusCode()).isEqualTo(200);
        } finally {
            app.stop();
        }
        assertThat(app.boundPort()).isEqualTo(-1);
    }

    @Test
    void hostAllowlistRejectsUnexpectedHost() throws Exception {
        AuraApp app = app(new AuraConfig().port(0).allowedHosts("api.example"), "host-allowlist");
        try {
            app.start();
            assertThat(get(app.boundPort()).statusCode()).isEqualTo(421);
        } finally {
            app.stop();
        }
    }

    @Test
    void schemaTokenGateIsOneDecisionForBothTransports() throws Exception {
        AuraApp app = app(new AuraConfig().port(0)
                .property("aura.schema.auth-token", "s3cret"), "schema-token");
        TestClient inMemory = TestClient.of(app);
        try {
            app.start();
            int port = app.boundPort();
            assertThat(schema(port, null).statusCode()).isEqualTo(401);
            assertThat(schema(port, "wrong").statusCode()).isEqualTo(401);
            HttpResponse<String> allowed = schema(port, "s3cret");
            assertThat(allowed.statusCode()).isEqualTo(200);
            assertThat(allowed.body()).contains("/ok");

            assertThat(inMemory.get("/__schema__").execute().status()).isEqualTo(401);
            assertThat(inMemory.get("/__schema__").header("X-Aura-Schema-Token", "wrong")
                    .execute().status()).isEqualTo(401);
            assertThat(inMemory.get("/__schema__").header("X-Aura-Schema-Token", "s3cret")
                    .execute().status()).isEqualTo(200);
        } finally {
            app.stop();
        }
    }

    @Test
    void csrfUsesTheConfiguredOriginNotTheForwardedHost() throws Exception {
        AuraApp app = postApp(new AuraConfig().port(0).env("prod")
                .publicOrigin("http://127.0.0.1")
                .trustedProxies("127.0.0.1"), "configured-origin");
        try {
            app.start();
            int port = app.boundPort();
            assertThat(csrfPost(port, "http://127.0.0.1", "api.example").statusCode()).isEqualTo(200);
            // A trusted proxy explains forwarding, not which origin the operator intended.
            assertThat(csrfPost(port, "https://evil.example", "evil.example").statusCode()).isEqualTo(403);
        } finally {
            app.stop();
        }
    }

    @Test
    void rateLimitUsesValidatedClientIpBehindTrustedProxy() throws Exception {
        AuraApp trusted = app(new AuraConfig().port(0)
                .rateLimitPerSecond(1).trustedProxies("127.0.0.1"), "trusted-rate-limit");
        try {
            trusted.start();
            int trustedPort = trusted.boundPort();
            List<Integer> statuses = new ArrayList<>();
            for (int i = 1; i <= 10; i++) statuses.add(get(trustedPort, "203.0.113." + i).statusCode());
            assertThat(statuses).containsOnly(200);
        } finally {
            trusted.stop();
        }

        AuraApp untrusted = app(new AuraConfig().port(0).rateLimitPerSecond(1),
                "untrusted-rate-limit");
        try {
            untrusted.start();
            int untrustedPort = untrusted.boundPort();
            List<Integer> statuses = new ArrayList<>();
            for (int i = 1; i <= 10; i++) statuses.add(get(untrustedPort, "203.0.113." + i).statusCode());
            assertThat(statuses).contains(429);
        } finally {
            untrusted.stop();
        }
    }

    @Test
    void moduleAdmissionPolicyUsesRouteTemplatesAndReplacesDefaultControl() throws Exception {
        StandardAdmissionPolicy policy = StandardAdmissionPolicy.builder()
                .routeRate("/orders/{id}", 1)
                .perApiKey(100, 16)
                .build();
        AuraConfig config = new AuraConfig().port(0).rateLimitPerSecond(1);
        AuraApp app = AuraApp.create(config).use(new io.aura.AuraModule() {
            @Override public io.aura.contract.ModuleMetadata descriptor() {
                return io.aura.contract.ModuleMetadata.of("admission", "1.0.0");
            }

            @Override public void install(io.aura.ModuleContext context) {
                context.admissionPolicy(policy);
                context.route(Route.of("GET", "/orders/{id}",
                        (BaseHandler) request -> request.text(request.path("id"))));
                context.route(Route.of("GET", "/health",
                        (BaseHandler) request -> request.text("ok")));
            }
        });
        try {
            app.start();
            int port = app.boundPort();
            HttpResponse<String> first = getPath(port, "/orders/1", "key-1");
            HttpResponse<String> second = getPath(port, "/orders/2", "key-2");
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(second.statusCode()).isEqualTo(429);
            assertThat(second.body()).contains(StandardAdmissionPolicy.RATE_LIMITED);

            // The custom policy replaces AuraConfig's default peer-IP limiter.
            for (int i = 0; i < 5; i++) {
                assertThat(getPath(port, "/health", "health-" + i).statusCode()).isEqualTo(200);
            }
            assertThat(policy.rateRejections()).isEqualTo(1);
        } finally {
            app.stop();
        }
    }

    private AuraApp app(AuraConfig config, String id) {
        return TestApplications.app(config, id,
                Route.of("GET", "/ok", (BaseHandler) request -> request.text("ok")));
    }

    private AuraApp postApp(AuraConfig config, String id) {
        return TestApplications.app(config, id,
                Route.of("POST", "/orders", (BaseHandler) request -> request.text("ok")));
    }

    private HttpResponse<String> get(int port) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ok"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String forwardedFor) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ok"))
                        .header("X-Forwarded-For", forwardedFor)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> csrfPost(int port, String origin, String forwardedHost) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders"))
                        .header("Origin", origin)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", forwardedHost)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getPath(int port, String path, String apiKey) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("X-API-Key", apiKey)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> schema(int port, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__schema__")).GET();
        if (token != null) request.header("X-Aura-Schema-Token", token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

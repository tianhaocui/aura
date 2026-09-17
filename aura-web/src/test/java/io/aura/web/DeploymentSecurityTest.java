package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
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
        int securePort = availablePort();
        AuraApp secure = app(new AuraConfig().port(securePort), "secure-headers");
        try {
            secure.start();
            HttpResponse<String> response = get(securePort);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
            assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        } finally {
            secure.stop();
        }

        int disabledPort = availablePort();
        AuraApp disabled = app(new AuraConfig().port(disabledPort).securityHeaders(false), "disabled-headers");
        try {
            disabled.start();
            HttpResponse<String> response = get(disabledPort);
            assertThat(response.headers().firstValue("X-Content-Type-Options")).isEmpty();
            assertThat(response.headers().firstValue("X-Frame-Options")).isEmpty();
        } finally {
            disabled.stop();
        }
    }

    @Test
    void hostAllowlistRejectsUnexpectedHost() throws Exception {
        int port = availablePort();
        AuraApp app = app(new AuraConfig().port(port).allowedHosts("api.example"), "host-allowlist");
        try {
            app.start();
            assertThat(get(port).statusCode()).isEqualTo(421);
        } finally {
            app.stop();
        }
    }

    @Test
    void forwardedOriginIsUsedOnlyForExplicitlyTrustedPeer() throws Exception {
        int trustedPort = availablePort();
        AuraApp trusted = postApp(new AuraConfig().port(trustedPort).env("prod")
                .trustedProxies("127.0.0.1").property("aura.csrf.enabled", "true"),
                "trusted-forwarded-origin");
        try {
            trusted.start();
            assertThat(forwardedPost(trustedPort).statusCode()).isEqualTo(200);
        } finally {
            trusted.stop();
        }

        int untrustedPort = availablePort();
        AuraApp untrusted = postApp(new AuraConfig().port(untrustedPort).env("prod")
                        .property("aura.csrf.enabled", "true"),
                "untrusted-forwarded-origin");
        try {
            untrusted.start();
            assertThat(forwardedPost(untrustedPort).statusCode()).isEqualTo(403);
        } finally {
            untrusted.stop();
        }
    }

    @Test
    void rateLimitUsesValidatedClientIpBehindTrustedProxy() throws Exception {
        int trustedPort = availablePort();
        AuraApp trusted = app(new AuraConfig().port(trustedPort)
                .rateLimitPerSecond(1).trustedProxies("127.0.0.1"), "trusted-rate-limit");
        try {
            trusted.start();
            List<Integer> statuses = new ArrayList<>();
            for (int i = 1; i <= 10; i++) statuses.add(get(trustedPort, "203.0.113." + i).statusCode());
            assertThat(statuses).containsOnly(200);
        } finally {
            trusted.stop();
        }

        int untrustedPort = availablePort();
        AuraApp untrusted = app(new AuraConfig().port(untrustedPort).rateLimitPerSecond(1),
                "untrusted-rate-limit");
        try {
            untrusted.start();
            List<Integer> statuses = new ArrayList<>();
            for (int i = 1; i <= 10; i++) statuses.add(get(untrustedPort, "203.0.113." + i).statusCode());
            assertThat(statuses).contains(429);
        } finally {
            untrusted.stop();
        }
    }

    private AuraApp app(AuraConfig config, String id) {
        return TestApplications.app(config, id,
                new RouteContribution("GET", "/ok", (BaseHandler) request -> request.text("ok")));
    }

    private AuraApp postApp(AuraConfig config, String id) {
        return TestApplications.app(config, id,
                new RouteContribution("POST", "/orders", (BaseHandler) request -> request.text("ok")));
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

    private HttpResponse<String> forwardedPost(int port) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders"))
                        .header("Origin", "https://api.example")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "api.example")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

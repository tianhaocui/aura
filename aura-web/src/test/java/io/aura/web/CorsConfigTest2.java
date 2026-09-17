package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.*;

class CorsConfigTest2 {

    @Test
    void corsConfig_multipleOrigins_matchesRequest() {
        AuraConfig config = new AuraConfig().cors(c -> c
                .origins("https://app.com", "https://admin.app.com")
                .headers("Content-Type", "Authorization", "X-Request-Id")
                .credentials(true));

        assertThat(config.corsConfig()).isNotNull();
        assertThat(config.corsConfig().origins()).containsExactly("https://app.com", "https://admin.app.com");
        assertThat(config.corsConfig().headers()).isEqualTo("Content-Type, Authorization, X-Request-Id");
        assertThat(config.corsConfig().credentials()).isTrue();
    }

    @Test
    void corsConfig_resolveOrigin_matchesKnown() {
        AuraConfig config = new AuraConfig().cors(c -> c.origins("https://a.com", "https://b.com"));
        assertThat(config.corsConfig().resolveOrigin("https://a.com")).isEqualTo("https://a.com");
        assertThat(config.corsConfig().resolveOrigin("https://b.com")).isEqualTo("https://b.com");
        assertThat(config.corsConfig().resolveOrigin("https://evil.com")).isNull();
    }

    @Test
    void corsConfig_wildcard_withoutCredentials() {
        AuraConfig config = new AuraConfig().cors(c -> c.origins("*"));
        assertThat(config.corsConfig().resolveOrigin("https://any.com")).isEqualTo("*");
    }

    @Test
    void corsConfig_wildcard_withCredentials_failsFast() {
        assertThatThrownBy(() -> new AuraConfig().cors(c -> c.origins("*").credentials(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit origins");
    }

    @Test
    void credentialedCorsEmitsHeadersOnlyForExplicitlyAllowedOrigin() throws Exception {
        int port = freePort();
        AuraConfig config = new AuraConfig().port(port)
                .cors(c -> c.origins("https://app.example.com").credentials(true));
        AuraApp app = TestApplications.app(config, "cors-config",
                new RouteContribution("GET", "/data", (BaseHandler) ctx -> ctx.text("ok")));
        app.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest allowed = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/data"))
                    .header("Origin", "https://app.example.com")
                    .build();
            var allowedResponse = client.send(allowed, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(allowedResponse.headers().firstValue("Access-Control-Allow-Origin"))
                    .contains("https://app.example.com");
            assertThat(allowedResponse.headers().firstValue("Access-Control-Allow-Credentials"))
                    .contains("true");
            assertThat(allowedResponse.headers().allValues("Vary")).contains("Origin");

            HttpRequest rejected = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/data"))
                    .header("Origin", "https://evil.example.com")
                    .build();
            var rejectedResponse = client.send(rejected, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(rejectedResponse.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(rejectedResponse.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        } finally {
            app.stop();
        }
    }

    @Test
    void corsTrue_stillWorks() {
        AuraConfig config = new AuraConfig().cors(true);
        assertThat(config.corsOrigin()).isEqualTo("*");
        assertThat(config.corsConfig()).isNull();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

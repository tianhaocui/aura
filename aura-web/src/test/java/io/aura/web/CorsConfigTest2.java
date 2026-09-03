package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.*;

class CorsConfigTest2 {

    @Test
    void corsConfig_multipleOrigins_matchesRequest() {
        Aura app = Aura.create().cors(c -> c
                .origins("https://app.com", "https://admin.app.com")
                .headers("Content-Type", "Authorization", "X-Request-Id")
                .credentials(true));

        assertThat(app.corsConfig()).isNotNull();
        assertThat(app.corsConfig().origins()).containsExactly("https://app.com", "https://admin.app.com");
        assertThat(app.corsConfig().headers()).isEqualTo("Content-Type, Authorization, X-Request-Id");
        assertThat(app.corsConfig().credentials()).isTrue();
    }

    @Test
    void corsConfig_resolveOrigin_matchesKnown() {
        Aura app = Aura.create().cors(c -> c.origins("https://a.com", "https://b.com"));
        assertThat(app.corsConfig().resolveOrigin("https://a.com")).isEqualTo("https://a.com");
        assertThat(app.corsConfig().resolveOrigin("https://b.com")).isEqualTo("https://b.com");
        assertThat(app.corsConfig().resolveOrigin("https://evil.com")).isNull();
    }

    @Test
    void corsConfig_wildcard_withoutCredentials() {
        Aura app = Aura.create().cors(c -> c.origins("*"));
        assertThat(app.corsConfig().resolveOrigin("https://any.com")).isEqualTo("*");
    }

    @Test
    void corsConfig_wildcard_withCredentials_failsFast() {
        assertThatThrownBy(() -> Aura.create().cors(c -> c.origins("*").credentials(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit origins");
    }

    @Test
    void credentialedCorsEmitsHeadersOnlyForExplicitlyAllowedOrigin() throws Exception {
        int port = freePort();
        Aura app = Aura.create().port(port)
                .cors(c -> c.origins("https://app.example.com").credentials(true))
                .routes(r -> r.get("/data", ctx -> ctx.text("ok")));
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
        Aura app = Aura.create().cors(true);
        assertThat(app.corsOrigin()).isEqualTo("*");
        assertThat(app.corsConfig()).isNull();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiEndpointTest {
    @Test
    void exposesOpenApiWhenEnabledInProduction() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Aura app = Aura.create().port(port).env("prod").openApi(true)
                .routes(r -> r.get("/orders/{id}", ctx -> ctx.text("ok")).describe("Get order"));
        app.start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/openapi.json")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"openapi\":\"3.0.3\"");
            assertThat(response.body()).contains("/orders/{id}");
        } finally {
            app.stop();
        }
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiEndpointTest {
    @Test
    void exposesOpenApiWhenEnabledInProduction() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        AuraApp app = TestApplications.app(
                new AuraConfig().port(port).env("prod").openApi(true),
                "openapi-endpoint",
                new RouteContribution(
                        "GET",
                        "/orders/{id}",
                        (BaseHandler) ctx -> ctx.text("ok"),
                        java.util.Map.of("description", "Get order")));
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

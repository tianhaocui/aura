package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.Route;
import io.aura.port.BaseHandler;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An encoded request path has to reach the same route through a socket and through the in-memory
 * client. Only the in-memory half decoded it, so a suite could pass against a server that 404s.
 */
class EncodedPathParityTest {

    @Test
    void aStaticPathWithASpaceMatchesInBothAdapters() throws Exception {
        int port = freePort();
        AuraApp app = TestApplications.app(new AuraConfig().port(port), "encoded-static",
                Route.of("GET", "/files/a b", (BaseHandler) ctx -> ctx.text("static")),
                Route.of("GET", "/user/{name}", (BaseHandler) ctx -> ctx.text(ctx.path("name"))));
        app.start();
        try {
            HttpResponse<String> socket = get(port, "/files/a%20b");
            TestClient.Response memory = TestClient.of(app).get("/files/a%20b").execute();

            assertThat(socket.statusCode()).isEqualTo(200);
            assertThat(socket.body()).isEqualTo("static");
            assertThat(memory.status()).isEqualTo(200);
            assertThat(memory.body()).isEqualTo("static");
        } finally {
            app.stop();
        }
    }

    @Test
    void aParameterIsDecodedInBothAdapters() throws Exception {
        int port = freePort();
        AuraApp app = TestApplications.app(new AuraConfig().port(port), "encoded-param",
                Route.of("GET", "/user/{name}", (BaseHandler) ctx -> ctx.text(ctx.path("name"))));
        app.start();
        try {
            HttpResponse<String> socket = get(port, "/user/ada%20lint");
            TestClient.Response memory = TestClient.of(app).get("/user/ada%20lint").execute();

            assertThat(socket.statusCode()).isEqualTo(200);
            assertThat(socket.body()).isEqualTo("ada lint");
            assertThat(memory.status()).isEqualTo(200);
            assertThat(memory.body()).isEqualTo("ada lint");
        } finally {
            app.stop();
        }
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

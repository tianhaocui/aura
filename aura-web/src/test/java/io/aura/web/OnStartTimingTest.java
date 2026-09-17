package io.aura.web;

import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class OnStartTimingTest {

    @Test
    void startReturnsAfterServerIsListening() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        var app = TestApplications.app(
                new AuraConfig().port(port),
                "start-timing",
                new RouteContribution("GET", "/ping", (BaseHandler) ctx -> ctx.text("pong")));

        try {
            app.start();
            var conn = (java.net.HttpURLConnection)
                java.net.URI.create("http://localhost:" + port + "/ping").toURL().openConnection();
            conn.setConnectTimeout(2000);
            assertThat(conn.getResponseCode()).isEqualTo(200);
            assertThat(new String(conn.getInputStream().readAllBytes())).isEqualTo("pong");
        } finally {
            app.stop();
        }
    }
}

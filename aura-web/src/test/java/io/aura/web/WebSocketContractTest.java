package io.aura.web;

import io.aura.port.BaseHandler;import
io.aura.port.WsHandler;
import com.alibaba.fastjson2.JSONObject;
import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * A WebSocket endpoint is one of the application's routes, so the schema, the OpenAPI document,
 * and the auth door all have to treat it the same way — including the handshake, which is an
 * HTTP request up to the moment the protocol changes.
 */
class WebSocketContractTest {

    private static final java.util.concurrent.atomic.AtomicInteger PORT_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(18410);

    private static Route chatRoom() {
        return Route.webSocket("/ws/room/{roomId}", (java.util.function.Consumer<WsHandler>)
                socket -> socket.onOpen(connection -> connection.send("hello")));
    }

    @Test
    void webSocketEndpointIsPublishedAsARoute() {
        TestClient client = TestClient.of(TestApplications.app("ws-schema",
                Route.of("GET", "/health", (BaseHandler) ctx -> ctx.text("ok")),
                chatRoom().describedAs("Chat room stream")));

        JSONObject view = SchemaWire.route(client, "/ws/room/{roomId}");

        assertThat(view).containsEntry("method", "WS");
        assertThat(view).containsEntry("description", "Chat room stream");
        // Streaming is what tells a request/response consumer to decline the endpoint, so it has
        // to be in the document and not merely true inside the JVM.
        assertThat(view).containsEntry("streaming", true);
        assertThat(view.getString("example"))
                .contains("Upgrade: websocket")
                .contains("/ws/room/example");
        // The template is only usable if the document says what fills it and from where.
        assertThat(SchemaWire.parameter(view, "roomId"))
                .containsEntry("source", "path")
                .containsEntry("required", true);
    }

    @Test
    void webSocketEndpointStaysOutOfOpenApi() {
        TestClient client = TestClient.of(TestApplications.app("ws-openapi", chatRoom()));

        assertThat(client.openApi().toString()).doesNotContain("/ws/room");
    }

    @Test
    void authenticatedHandshakeIsRejectedBeforeTheProtocolChanges() throws Exception {
        int port = PORT_SEQ.getAndIncrement();
        AuraApp app = TestApplications.app(new AuraConfig().port(port), "ws-auth",
                chatRoom().requireAuth())
                .authenticate(ctx -> "secret-token".equals(ctx.header("X-Token")) ? "user-1" : null);
        app.start();
        try {
            assertThat(handshakeStatus(port, "/ws/room/lobby", Map.of())).isEqualTo(401);
            assertThat(handshakeStatus(port, "/ws/room/lobby", Map.of("X-Token", "secret-token")))
                    .isEqualTo(101);
        } finally {
            app.stop();
        }
    }

    @Test
    void handshakeWithoutThePermissionIsRejected() throws Exception {
        int port = PORT_SEQ.getAndIncrement();
        AuraApp app = TestApplications.app(new AuraConfig().port(port), "ws-permission",
                chatRoom().requireAuth("rooms.write"))
                .authenticate(ctx -> "user-1")
                .authorize((user, permission) -> false);
        app.start();
        try {
            assertThat(handshakeStatus(port, "/ws/room/lobby", Map.of())).isEqualTo(403);
        } finally {
            app.stop();
        }
    }

    @Test
    void unauthenticatedHandshakeStillUpgrades() throws Exception {
        int port = PORT_SEQ.getAndIncrement();
        AuraApp app = TestApplications.app(new AuraConfig().port(port), "ws-open", chatRoom());
        app.start();
        try {
            assertThat(handshakeStatus(port, "/ws/room/lobby", Map.of())).isEqualTo(101);
        } finally {
            app.stop();
        }
    }

    /**
     * The handshake is read from a socket rather than a WebSocket client because the JDK client
     * hides the status code of a rejected upgrade, and the status code is the whole assertion.
     */
    private static int handshakeStatus(int port, String path, Map<String, String> headers)
            throws IOException {
        StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: localhost:").append(port).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
        headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        request.append("\r\n");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String statusLine = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (statusLine == null) throw new IOException("no handshake response");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}

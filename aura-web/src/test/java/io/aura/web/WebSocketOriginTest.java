package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketOriginTest {

    @Test
    void rejectsWebSocketWithMismatchedOrigin() throws Exception {
        int port = freePort();
        AtomicBoolean connected = new AtomicBoolean(false);
        AuraApp app = app(new AuraConfig()
                        .port(port)
                        .cors(cors -> cors.origins("https://allowed.example")),
                context -> context.webSocket("/ws", ws -> ws.onOpen(conn -> connected.set(true))));

        app.start();
        try {
            String response = rawWebSocketHandshake(port, "/ws", "https://evil.example");
            assertThat(response).contains("403");
            assertThat(connected).isFalse();
        } finally {
            app.stop();
        }
    }

    @Test
    void acceptsWebSocketWithMatchingOrigin() throws Exception {
        int port = freePort();
        AtomicBoolean connected = new AtomicBoolean(false);
        AuraApp app = app(new AuraConfig()
                        .port(port)
                        .cors(cors -> cors.origins("https://allowed.example")),
                context -> context.webSocket("/ws", ws -> ws.onOpen(conn -> connected.set(true))));

        app.start();
        try {
            String response = rawWebSocketHandshake(port, "/ws", "https://allowed.example");
            assertThat(response).contains("101");
            Thread.sleep(200);
            assertThat(connected).isTrue();
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsWebSocketWithoutOriginInProdMode() throws Exception {
        int port = freePort();
        AtomicBoolean connected = new AtomicBoolean(false);
        AuraApp app = app(new AuraConfig()
                        .port(port)
                        .env("prod")
                        .cors(cors -> cors.origins("https://allowed.example")),
                context -> context.webSocket("/ws", ws -> ws.onOpen(conn -> connected.set(true))));

        app.start();
        try {
            String response = rawWebSocketHandshakeNoOrigin(port, "/ws");
            assertThat(response).contains("403");
            assertThat(connected).isFalse();
        } finally {
            app.stop();
        }
    }

    @Test
    void acceptsWebSocketWithoutOriginInDevMode() throws Exception {
        int port = freePort();
        AtomicBoolean connected = new AtomicBoolean(false);
        AuraApp app = app(new AuraConfig()
                        .port(port)
                        .env("dev")
                        .cors(cors -> cors.origins("https://allowed.example")),
                context -> context.webSocket("/ws", ws -> ws.onOpen(conn -> connected.set(true))));

        app.start();
        try {
            String response = rawWebSocketHandshakeNoOrigin(port, "/ws");
            assertThat(response).contains("101");
            Thread.sleep(200);
            assertThat(connected).isTrue();
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsWebSocketWithOriginWhenWildcardCors() throws Exception {
        int port = freePort();
        AtomicBoolean connected = new AtomicBoolean(false);
        AuraApp app = app(new AuraConfig()
                        .port(port)
                        .cors("*"),
                context -> context.webSocket("/ws", ws -> ws.onOpen(conn -> connected.set(true))));

        app.start();
        try {
            String response = rawWebSocketHandshake(port, "/ws", "https://any.example");
            assertThat(response).contains("403");
            assertThat(connected).isFalse();
        } finally {
            app.stop();
        }
    }

    private AuraApp app(AuraConfig config, Consumer<ModuleContext> configure) {
        return AuraApp.create(config).use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("ws-origin-test", "1.0.0");
            }
            @Override public void install(ModuleContext context) {
                configure.accept(context);
            }
        });
    }

    private String rawWebSocketHandshake(int port, String path, String origin) throws Exception {
        String key = Base64.getEncoder().encodeToString("test-key-1234567".getBytes());
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Origin: " + origin + "\r\n\r\n";
        return sendRawRequest(port, request);
    }

    private String rawWebSocketHandshakeNoOrigin(int port, String path) throws Exception {
        String key = Base64.getEncoder().encodeToString("test-key-1234567".getBytes());
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        return sendRawRequest(port, request);
    }

    private String sendRawRequest(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[1024];
            int read = socket.getInputStream().read(buffer);
            return read > 0 ? new String(buffer, 0, read, StandardCharsets.US_ASCII) : "";
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

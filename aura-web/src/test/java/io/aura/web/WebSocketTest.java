package io.aura.web;

import io.aura.Aura;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class WebSocketTest {

    private static final java.util.concurrent.atomic.AtomicInteger PORT_SEQ = new java.util.concurrent.atomic.AtomicInteger(18234);

    private int nextPort() { return PORT_SEQ.getAndIncrement(); }

    @Test
    void echo() throws Exception {
        int port = nextPort();
        var app = Aura.create().port(port)
            .routes(r -> r.ws("/ws/echo", ws ->
                ws.onMessage((conn, msg) -> conn.send("echo:" + msg))
            ));
        app.start();
        try {
            var received = new CompletableFuture<String>();
            var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/echo"),
                    new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            received.complete(data.toString());
                            return null;
                        }
                    }).join();
            ws.sendText("hello", true);
            assertThat(received.get(3, TimeUnit.SECONDS)).isEqualTo("echo:hello");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } finally {
            app.stop();
        }
    }

    @Test
    void pathParams() throws Exception {
        int port = nextPort();
        var received = new CompletableFuture<String>();
        var app = Aura.create().port(port)
            .routes(r -> r.ws("/ws/room/{roomId}", ws ->
                ws.onOpen(conn -> conn.send("joined:" + conn.path("roomId")))
            ));
        app.start();
        try {
            var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/room/lobby"),
                    new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            received.complete(data.toString());
                            return null;
                        }
                    }).join();
            assertThat(received.get(3, TimeUnit.SECONDS)).isEqualTo("joined:lobby");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } finally {
            app.stop();
        }
    }

    @Test
    void beforeMiddleware_rejectsUnauthorized() throws Exception {
        int port = nextPort();
        var app = Aura.create().port(port)
            .routes(r -> {
                r.before(ctx -> {
                    if (ctx.header("Authorization") == null) {
                        ctx.status(401).abort();
                    }
                });
                r.ws("/ws/secure", ws ->
                    ws.onMessage((conn, msg) -> conn.send(msg))
                );
            });
        app.start();
        try {
            var failed = new CompletableFuture<Throwable>();
            HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/secure"),
                    new WebSocket.Listener() {})
                .whenComplete((ws, err) -> {
                    if (err != null) failed.complete(err);
                    else failed.complete(null);
                });
            var err = failed.get(3, TimeUnit.SECONDS);
            assertThat(err).isNotNull();
        } finally {
            app.stop();
        }
    }

    @Test
    void onClose_fires() throws Exception {
        int port = nextPort();
        var closed = new CompletableFuture<Integer>();
        var app = Aura.create().port(port)
            .routes(r -> r.ws("/ws/close", ws ->
                ws.onClose((conn, code, reason) -> closed.complete(code))
            ));
        app.start();
        try {
            var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/close"),
                    new WebSocket.Listener() {}).join();
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            assertThat(closed.get(3, TimeUnit.SECONDS)).isEqualTo(1000);
        } finally {
            app.stop();
        }
    }

    @Test
    void multipleMessages() throws Exception {
        int port = nextPort();
        var app = Aura.create().port(port)
            .routes(r -> r.ws("/ws/multi", ws ->
                ws.onMessage((conn, msg) -> conn.send(msg.toUpperCase()))
            ));
        app.start();
        try {
            var messages = new CopyOnWriteArrayList<String>();
            var latch = new CountDownLatch(3);
            var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/multi"),
                    new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            messages.add(data.toString());
                            latch.countDown();
                            webSocket.request(1);
                            return null;
                        }
                    }).join();
            Thread.sleep(100);
            ws.sendText("a", true);
            ws.sendText("b", true);
            ws.sendText("c", true);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(messages).containsExactly("A", "B", "C");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } finally {
            app.stop();
        }
    }

    @Test
    void broadcast() throws Exception {
        int port = nextPort();
        var connections = ConcurrentHashMap.<WsConnection>newKeySet();
        var app = Aura.create().port(port)
            .routes(r -> r.ws("/ws/broadcast", ws -> {
                ws.onOpen(connections::add);
                ws.onMessage((conn, msg) -> {
                    for (WsConnection c : connections) c.send(msg);
                });
                ws.onClose((conn, code, reason) -> connections.remove(conn));
            }));
        app.start();
        try {
            var received1 = new CompletableFuture<String>();
            var received2 = new CompletableFuture<String>();

            var ws1 = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/broadcast"),
                    new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            received1.complete(data.toString());
                            return null;
                        }
                    }).join();

            var ws2 = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/broadcast"),
                    new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            received2.complete(data.toString());
                            return null;
                        }
                    }).join();

            Thread.sleep(100);
            ws1.sendText("hello all", true);

            assertThat(received1.get(3, TimeUnit.SECONDS)).isEqualTo("hello all");
            assertThat(received2.get(3, TimeUnit.SECONDS)).isEqualTo("hello all");

            ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } finally {
            app.stop();
        }
    }

    @Test
    void connectionAttributes() throws Exception {
        int port = nextPort();
        var received = new CompletableFuture<String>();
        var app = Aura.create().port(port)
            .routes(r -> r.ws("/ws/attrs", ws -> {
                ws.onOpen(conn -> conn.set("name", "alice"));
                ws.onMessage((conn, msg) -> conn.send(conn.get("name", String.class) + ":" + msg));
            }));
        app.start();
        try {
            var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/attrs"),
                    new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            received.complete(data.toString());
                            return null;
                        }
                    }).join();
            ws.sendText("hi", true);
            assertThat(received.get(3, TimeUnit.SECONDS)).isEqualTo("alice:hi");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } finally {
            app.stop();
        }
    }

    @Test
    void onError_routesHandlerException() throws Exception {
        int port = nextPort();
        var error = new CompletableFuture<Throwable>();
        var app = Aura.create().port(port)
            .routes(r -> r.ws("/ws/err", ws -> {
                ws.onMessage((conn, msg) -> { throw new RuntimeException("boom"); });
                ws.onError((conn, err) -> error.complete(err));
            }));
        app.start();
        try {
            var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/err"),
                    new WebSocket.Listener() {}).join();
            ws.sendText("trigger", true);
            assertThat(error.get(3, TimeUnit.SECONDS))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } finally {
            app.stop();
        }
    }
}
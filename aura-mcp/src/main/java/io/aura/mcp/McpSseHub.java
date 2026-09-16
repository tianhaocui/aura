package io.aura.mcp;

import io.aura.contract.CancellationToken;
import io.aura.web.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded per-session SSE channel for server-to-client MCP messages. */
final class McpSseHub implements AutoCloseable {
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final int maxEventBytes;

    McpSseHub(int maxEventBytes) {
        if (maxEventBytes < 1) throw new IllegalArgumentException("maxEventBytes must be positive");
        this.maxEventBytes = maxEventBytes;
    }

    boolean attach(String sessionId, SseEmitter emitter, CancellationToken token) {
        Connection connection = new Connection(sessionId, emitter, token);
        if (connections.putIfAbsent(sessionId, connection) != null) return false;
        connection.registration = token.onCancel(() -> close(sessionId, connection));
        return true;
    }

    boolean connected(String sessionId) {
        return connections.containsKey(sessionId);
    }

    boolean publish(String sessionId, String method, Map<String, ?> params) {
        Connection connection = connections.get(sessionId);
        if (connection == null) return false;
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("method", method);
        if (params != null) envelope.put("params", params);
        String payload = com.alibaba.fastjson2.JSON.toJSONString(envelope);
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxEventBytes) return false;
        try {
            synchronized (connection) {
                if (connection.closed.get()) return false;
                connection.emitter.send("message", payload);
            }
            return true;
        } catch (Exception failure) {
            close(sessionId, connection);
            return false;
        }
    }

    void close(String sessionId) {
        Connection connection = connections.remove(sessionId);
        if (connection != null) connection.close();
    }

    private void close(String sessionId, Connection expected) {
        if (connections.remove(sessionId, expected)) expected.close();
    }

    int size() { return connections.size(); }

    @Override public void close() {
        connections.values().forEach(Connection::close);
        connections.clear();
    }

    private static final class Connection {
        private final String sessionId;
        private final SseEmitter emitter;
        private final CancellationToken token;
        private final AtomicBoolean closed = new AtomicBoolean();
        private CancellationToken.Registration registration = () -> { };

        private Connection(String sessionId, SseEmitter emitter, CancellationToken token) {
            this.sessionId = sessionId;
            this.emitter = emitter;
            this.token = token;
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) return;
            registration.close();
            emitter.close();
        }
    }
}

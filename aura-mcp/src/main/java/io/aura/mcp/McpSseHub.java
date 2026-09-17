package io.aura.mcp;

import io.aura.contract.CancellationToken;
import io.aura.web.SseEmitter;

import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded per-session SSE channel for server-to-client MCP messages. */
final class McpSseHub implements AutoCloseable {
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionHistory> histories = new ConcurrentHashMap<>();
    private final int maxEventBytes;
    private static final int MAX_HISTORY_EVENTS = 128;

    McpSseHub(int maxEventBytes) {
        if (maxEventBytes < 1) throw new IllegalArgumentException("maxEventBytes must be positive");
        this.maxEventBytes = maxEventBytes;
    }

    boolean attach(String sessionId, SseEmitter emitter, CancellationToken token, long lastEventId) {
        SessionHistory history = histories.computeIfAbsent(sessionId, ignored -> new SessionHistory());
        Connection connection = new Connection(sessionId, emitter, token, history);
        if (connections.putIfAbsent(sessionId, connection) != null) return false;
        connection.registration = token.onCancel(() -> close(sessionId, connection));
        try {
            synchronized (connection) {
        for (Event event : connection.history.snapshot()) {
                    if (event.id > lastEventId) {
                        connection.emitter.send("message", event.payload, Long.toString(event.id));
                    }
                }
            }
        } catch (Exception failure) {
            close(sessionId, connection);
            return false;
        }
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
        final String payload;
        try {
            payload = BoundedJson.encode(envelope, maxEventBytes);
        } catch (BoundedJson.LimitExceededException tooLarge) {
            return false;
        }
        try {
            synchronized (connection) {
                if (connection.closed.get()) return false;
                Event event = connection.history.record(payload);
                connection.emitter.send("message", payload, Long.toString(event.id));
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

    void remove(String sessionId) {
        close(sessionId);
        histories.remove(sessionId);
    }

    private void close(String sessionId, Connection expected) {
        if (connections.remove(sessionId, expected)) expected.close();
    }

    int size() { return connections.size(); }

    @Override public void close() {
        connections.values().forEach(Connection::close);
        connections.clear();
        histories.clear();
    }

    private static final class Connection {
        private final String sessionId;
        private final SseEmitter emitter;
        private final CancellationToken token;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final SessionHistory history;
        private CancellationToken.Registration registration = () -> { };

        private Connection(String sessionId, SseEmitter emitter, CancellationToken token, SessionHistory history) {
            this.sessionId = sessionId;
            this.emitter = emitter;
            this.token = token;
            this.history = history;
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) return;
            registration.close();
            emitter.close();
        }
    }

    private record Event(long id, String payload) { }

    private static final class SessionHistory {
        private final Deque<Event> events = new ArrayDeque<>();
        private long nextEventId = 1;

        private synchronized Event record(String payload) {
            Event event = new Event(nextEventId++, payload);
            events.addLast(event);
            while (events.size() > MAX_HISTORY_EVENTS) events.removeFirst();
            return event;
        }

        private synchronized Deque<Event> snapshot() {
            return new ArrayDeque<>(events);
        }
    }
}

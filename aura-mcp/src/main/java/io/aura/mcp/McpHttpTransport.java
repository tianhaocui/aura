package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.Aura;
import io.aura.web.BaseContext;
import io.aura.web.BaseHandler;
import io.aura.web.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class McpHttpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpHttpTransport.class);

    private final McpRouter router;
    private final McpProtocol protocol;
    private final ConcurrentHashMap<String, SseSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat;

    public McpHttpTransport(McpRouter router, String serverName) {
        this.router = router;
        this.protocol = new McpProtocol(
                new PrintStream(java.io.OutputStream.nullOutputStream()),
                serverName,
                (method, params) -> switch (method) {
                    case "tools/list" -> router.buildSchema();
                    case "tools/call" -> handleCall(params);
                    default -> null;
                }
        );
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats, 30, 30, TimeUnit.SECONDS);
    }

    public BaseHandler postHandler() {
        return ctx -> {
            String body = ctx.body(String.class);
            if (body == null || body.isBlank()) {
                ctx.status(400).json(Map.of("error", "Empty request body"));
                return;
            }
            try {
                JSONObject request = JSON.parseObject(body);
                JSONObject response = protocol.dispatch(request);
                if (response != null) {
                    ctx.header("Content-Type", "application/json");
                    ctx.jsonRaw(response.toJSONString());
                } else {
                    ctx.status(202).json(Map.of("status", "accepted"));
                }
            } catch (Exception e) {
                JSONObject error = protocol.buildError(null, -32700, "Parse error: " + e.getMessage());
                ctx.status(400).jsonRaw(error.toJSONString());
            }
        };
    }

    public BaseHandler sseHandler() {
        return ctx -> {
            String sessionId = java.util.UUID.randomUUID().toString();
            SseEmitter emitter = ctx.sse();
            SseSession session = new SseSession(sessionId, emitter);
            sessions.put(sessionId, session);
            try {
                emitter.send("endpoint", JSON.toJSONString(Map.of(
                        "sessionId", sessionId,
                        "message", "MCP SSE session established"
                )));
                session.awaitClose();
            } finally {
                sessions.remove(sessionId);
                emitter.close();
            }
        };
    }

    public int toolCount() {
        return router.tools().size();
    }

    public void shutdown() {
        heartbeat.shutdownNow();
        for (SseSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    private Map<String, Object> handleCall(JSONObject params) {
        String toolName = params.getString("name");
        JSONObject args = params.getJSONObject("arguments");
        @SuppressWarnings("unchecked")
        Map<String, Object> argsMap = args != null ? (Map<String, Object>) args.toJavaObject(Map.class) : Map.of();
        try {
            Object result = router.invoke(toolName, argsMap);
            String text = result instanceof String s ? s : JSON.toJSONString(result);
            return Map.of("content", List.of(Map.of("type", "text", "text", text)));
        } catch (Exception e) {
            return Map.of("isError", true,
                    "content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())));
        }
    }

    private void sendHeartbeats() {
        long now = System.currentTimeMillis();
        for (var entry : sessions.entrySet()) {
            SseSession session = entry.getValue();
            if (now - session.lastActivity() > 60_000) {
                session.close();
                sessions.remove(entry.getKey());
            } else {
                try {
                    session.emitter().send("ping", String.valueOf(now));
                } catch (Exception e) {
                    sessions.remove(entry.getKey());
                }
            }
        }
    }

    private static class SseSession {
        private final String id;
        private final SseEmitter emitter;
        private volatile long lastActivity;
        private final java.util.concurrent.CountDownLatch closeLatch = new java.util.concurrent.CountDownLatch(1);

        SseSession(String id, SseEmitter emitter) {
            this.id = id;
            this.emitter = emitter;
            this.lastActivity = System.currentTimeMillis();
        }

        SseEmitter emitter() { return emitter; }
        long lastActivity() { return lastActivity; }

        void close() { closeLatch.countDown(); }

        void awaitClose() {
            try { closeLatch.await(); } catch (InterruptedException ignored) {}
        }
    }
}
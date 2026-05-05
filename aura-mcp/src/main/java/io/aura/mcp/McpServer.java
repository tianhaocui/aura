package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.web.CompiledRoute;
import io.aura.web.Handler;
import io.aura.web.MethodRefHandler;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private final int port;
    private final List<McpTool> tools;
    private final Map<String, ToolExecutor> executors;
    private Undertow server;
    private final Map<String, SseConnection> sessions = new ConcurrentHashMap<>();

    public McpServer(int port, List<CompiledRoute> routes) {
        this.port = port;
        this.tools = new ArrayList<>();
        this.executors = new LinkedHashMap<>();
        buildTools(routes);
    }

    public void start() {
        server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.isInIoThread()) {
                        exchange.dispatch(this::handleRequest);
                        return;
                    }
                    handleRequest(exchange);
                })
                .build();
        server.start();
        log.info("MCP Server started on port {}", port);
    }

    public void stop() {
        if (server != null) {
            sessions.values().forEach(SseConnection::close);
            server.stop();
            log.info("MCP Server stopped");
        }
    }

    private void handleRequest(HttpServerExchange exchange) {
        String method = exchange.getRequestMethod().toString();
        String path = exchange.getRequestURI();

        if ("GET".equals(method) && "/sse".equals(path)) {
            handleSseConnect(exchange);
        } else if ("POST".equals(method) && path.startsWith("/messages")) {
            handleMessage(exchange);
        } else {
            exchange.setStatusCode(404);
            exchange.getResponseSender().send("Not Found");
        }
    }

    private void handleSseConnect(HttpServerExchange exchange) {
        String sessionId = UUID.randomUUID().toString();
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/event-stream");
        exchange.getResponseHeaders().put(new io.undertow.util.HttpString("Cache-Control"), "no-cache");
        exchange.getResponseHeaders().put(new io.undertow.util.HttpString("Connection"), "keep-alive");
        exchange.getResponseHeaders().put(new io.undertow.util.HttpString("Access-Control-Allow-Origin"), "*");

        var conn = new SseConnection(exchange, sessionId);
        sessions.put(sessionId, conn);

        conn.send("endpoint", "/messages?sessionId=" + sessionId);

        // keep connection alive until closed
        try {
            conn.awaitClose();
        } finally {
            sessions.remove(sessionId);
        }
    }

    private void handleMessage(HttpServerExchange exchange) {
        String sessionId = exchange.getQueryParameters()
                .getOrDefault("sessionId", new ArrayDeque<>()).peek();
        if (sessionId == null) {
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("Missing sessionId");
            return;
        }

        SseConnection conn = sessions.get(sessionId);
        if (conn == null) {
            exchange.setStatusCode(404);
            exchange.getResponseSender().send("Session not found");
            return;
        }

        exchange.startBlocking();
        try {
            String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject request = JSON.parseObject(body);
            String rpcMethod = request.getString("method");
            Object id = request.get("id");

            Object result = switch (rpcMethod) {
                case "initialize" -> handleInitialize();
                case "tools/list" -> handleToolsList();
                case "tools/call" -> handleToolsCall(request.getJSONObject("params"));
                case "notifications/initialized" -> null;
                default -> Map.of("error", "Unknown method: " + rpcMethod);
            };

            exchange.setStatusCode(202);
            exchange.getResponseSender().send("Accepted");

            if (id != null && result != null) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("jsonrpc", "2.0");
                response.put("id", id);
                response.put("result", result);
                conn.send("message", JSON.toJSONString(response));
            }
        } catch (Exception e) {
            log.error("Error processing MCP message", e);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("Internal error");
        }
    }

    private Map<String, Object> handleInitialize() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", Map.of("tools", Map.of()));
        result.put("serverInfo", Map.of("name", "aura-mcp", "version", "0.1.0"));
        return result;
    }

    private Map<String, Object> handleToolsList() {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (McpTool t : tools) {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", t.name());
            if (t.description() != null) m.put("description", t.description());
            m.put("inputSchema", t.inputSchema());
            toolList.add(m);
        }
        return Map.of("tools", toolList);
    }

    private Map<String, Object> handleToolsCall(JSONObject params) {
        String toolName = params.getString("name");
        JSONObject args = params.getJSONObject("arguments");

        ToolExecutor executor = executors.get(toolName);
        if (executor == null) {
            return Map.of("isError", true,
                    "content", List.of(Map.of("type", "text", "text", "Tool not found: " + toolName)));
        }

        try {
            Map<String, Object> argMap = args != null ? args.toJavaObject(Map.class) : Map.of();
            Object result = executor.execute(argMap);
            String text = result == null ? "ok" : JSON.toJSONString(result);
            return Map.of("content", List.of(Map.of("type", "text", "text", text)));
        } catch (Exception e) {
            return Map.of("isError", true,
                    "content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())));
        }
    }

    private void buildTools(List<CompiledRoute> routes) {
        for (CompiledRoute cr : routes) {
            if (!(cr.handler() instanceof MethodRefHandler mh)) continue;

            String toolName = buildToolName(cr.method(), cr.rawPath());
            String desc = cr.meta() != null ? cr.meta().description() : null;
            Map<String, Object> inputSchema = buildInputSchema(mh, cr);

            tools.add(new McpTool(toolName, desc, cr.method(), cr.rawPath(), inputSchema));
            executors.put(toolName, mh::invokeWithArgs);
        }
    }

    private static String buildToolName(String method, String path) {
        String clean = path.replaceAll("^/", "")
                .replaceAll("\\{[a-zA-Z_]+}", "by_id")
                .replaceAll("/+", "_");
        if (clean.isEmpty()) clean = "root";
        return method.toLowerCase() + "_" + clean;
    }

    private static Map<String, Object> buildInputSchema(MethodRefHandler mh, CompiledRoute cr) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();

        for (var p : mh.resolvedMethod().getParameters()) {
            if (p.getType().getName().equals("io.aura.web.Context")) continue;
            var prop = new LinkedHashMap<String, Object>();
            prop.put("type", jsonType(p.getType().getSimpleName()));
            properties.put(p.getName(), prop);
            required.add(p.getName());
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static String jsonType(String javaType) {
        return switch (javaType) {
            case "int", "long", "Integer", "Long" -> "integer";
            case "double", "float", "Double", "Float" -> "number";
            case "boolean", "Boolean" -> "boolean";
            default -> "string";
        };
    }

    @FunctionalInterface
    interface ToolExecutor {
        Object execute(Map<String, Object> args) throws Exception;
    }

    static class SseConnection {
        private final HttpServerExchange exchange;
        private final String sessionId;
        private final OutputStream out;
        private volatile boolean closed = false;
        private final Object lock = new Object();

        SseConnection(HttpServerExchange exchange, String sessionId) {
            this.exchange = exchange;
            this.sessionId = sessionId;
            exchange.startBlocking();
            this.out = exchange.getOutputStream();
        }

        synchronized void send(String event, String data) {
            try {
                String msg = "event: " + event + "\ndata: " + data + "\n\n";
                out.write(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                close();
            }
        }

        void awaitClose() {
            synchronized (lock) {
                while (!closed) {
                    try { lock.wait(); } catch (InterruptedException e) { break; }
                }
            }
        }

        void close() {
            closed = true;
            synchronized (lock) { lock.notifyAll(); }
            try { out.close(); } catch (Exception ignored) {}
        }
    }
}

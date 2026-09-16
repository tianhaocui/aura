package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONObject;
import io.aura.AuraMcpRuntime;
import io.aura.web.BaseContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authenticated JSON-RPC over HTTP handler backed by one {@link McpRouter}. */
final class McpHttpTransport {
    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String PROTOCOL_VERSION = McpProtocolConstants.VERSION;
    static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;

    private final McpRouter router;
    private final McpSessionManager sessions;
    private final int maxResponseBytes;
    private final McpSseHub sseHub;
    private final String httpPath;

    McpHttpTransport(McpRouter router, McpSessionManager sessions, int maxResponseBytes,
                     McpSseHub sseHub, String httpPath) {
        this.router = java.util.Objects.requireNonNull(router, "router");
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        if (maxResponseBytes < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
        this.sseHub = sseHub;
        this.httpPath = httpPath;
    }

    void get(BaseContext context) {
        if (!originAllowed(context)) {
            context.status(403).raw("Origin is not allowed");
            return;
        }
        if (context.header("Accept") == null
                || !context.header("Accept").toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
            context.status(406).raw("MCP SSE requires Accept: text/event-stream");
            return;
        }
        AuraMcpRuntime runtime = runtime(context);
        String userId = authenticate(runtime, context);
        if (userId == null) {
            context.status(401).raw("Authentication required");
            return;
        }
        String sessionId = context.header(SESSION_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            context.status(400).raw("Mcp-Session-Id is required");
            return;
        }
        if (sessions.access(sessionId, userId).isEmpty()) {
            context.status(404).raw("MCP session not found or expired");
            return;
        }
        if (sseHub == null) {
            context.status(405).raw("MCP SSE is not enabled");
            return;
        }
        if (sseHub.connected(sessionId)) {
            context.status(409).raw("MCP session already has an SSE stream");
            return;
        }
        long lastEventId = -1L;
        String lastEventHeader = context.header("Last-Event-ID");
        if (lastEventHeader != null && !lastEventHeader.isBlank()) {
            try {
                lastEventId = Long.parseLong(lastEventHeader.trim());
                if (lastEventId < 0) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                context.status(400).raw("Last-Event-ID must be a non-negative integer");
                return;
            }
        }
        try {
            io.aura.web.SseEmitter emitter = context.sse();
            if (!sseHub.attach(sessionId, emitter, context.cancellationToken(), lastEventId)) {
                context.status(409).raw("MCP session already has an SSE stream");
                emitter.close();
                return;
            }
            emitter.send("endpoint", httpPath);
            while (!context.isCancelled()) {
                try {
                    if (sessions.access(sessionId, userId).isEmpty()) break;
                    emitter.send("heartbeat", "");
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception failure) {
            sseHub.close(sessionId);
            if (!context.isResponseStarted()) context.status(500).raw("MCP SSE unavailable");
        } finally {
            if (context.isCancelled()) sseHub.close(sessionId);
        }
    }

    void post(BaseContext context) {
        if (!originAllowed(context)) {
            respondError(context, null, 403, -32004, "Origin is not allowed");
            return;
        }
        if (!acceptsJsonRpc(context)) {
            respondError(context, null, 406, -32005,
                    "MCP POST requires Accept: application/json or text/event-stream");
            return;
        }
        AuraMcpRuntime runtime = runtime(context);
        String userId = authenticate(runtime, context);
        if (userId == null) {
            respondError(context, null, 401, -32001, "Authentication required");
            return;
        }
        JSONObject request;
        try {
            request = context.body(JSONObject.class);
        } catch (Exception invalidJson) {
            respondError(context, null, 400, -32700, "Parse error");
            return;
        }
        Object id = request == null ? null : request.get("id");
        boolean hasId = request != null && request.containsKey("id");
        if (request == null || !"2.0".equals(request.getString("jsonrpc"))) {
            respondError(context, id, 400, -32600, "Invalid Request");
            return;
        }
        if (hasId && id != null && !(id instanceof String) && !(id instanceof Number)) {
            respondError(context, null, 400, -32600, "Invalid Request: id must be a string, number, or null");
            return;
        }
        String method = request.getString("method");
        if (method == null || method.isBlank()) {
            respondError(context, id, 400, -32600, "Invalid Request: method is required");
            return;
        }
        Object rawParams = request.get("params");
        if (rawParams != null && !(rawParams instanceof JSONObject)) {
            respondError(context, id, 400, -32602, "Invalid params");
            return;
        }

        if ("initialize".equals(method)) {
            initialize(context, hasId, id, userId,
                    request.getJSONObject("params") == null ? new JSONObject() : request.getJSONObject("params"));
            return;
        }

        String sessionId = context.header(SESSION_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            respondError(context, id, 400, -32002, "Mcp-Session-Id is required");
            return;
        }
        if (sessions.access(sessionId, userId).isEmpty()) {
            respondError(context, id, 404, -32002, "MCP session not found or expired");
            return;
        }

        JSONObject params = request.getJSONObject("params");
        DispatchResult dispatched;
        try {
            dispatched = dispatch(runtime, userId, method,
                    params == null ? new JSONObject() : params);
        } catch (RuntimeException invalidParams) {
            org.slf4j.LoggerFactory.getLogger(McpHttpTransport.class)
                    .debug("MCP request parameter conversion failed for {}", method, invalidParams);
            respondError(context, id, 400, -32602, "Invalid params");
            return;
        }
        if (!hasId) {
            context.status(202).raw("");
            return;
        }
        if (dispatched.invalidParams()) {
            respondError(context, id, 400, -32602, "Invalid params");
            return;
        }
        if (dispatched.result() == null) {
            respondError(context, id, 200, -32601, "Method not found: " + method);
            return;
        }
        writeRpc(context, rpcResult(id, dispatched.result()));
    }

    void delete(BaseContext context) {
        if (!originAllowed(context)) {
            respondError(context, null, 403, -32004, "Origin is not allowed");
            return;
        }
        AuraMcpRuntime runtime = runtime(context);
        String userId = authenticate(runtime, context);
        if (userId == null) {
            respondError(context, null, 401, -32001, "Authentication required");
            return;
        }
        String sessionId = context.header(SESSION_HEADER);
        if (!sessions.close(sessionId, userId)) {
            respondError(context, null, 404, -32002, "MCP session not found or expired");
            return;
        }
        if (sseHub != null) sseHub.remove(sessionId);
        context.status(204).raw("");
    }

    private void initialize(BaseContext context, boolean hasId, Object id, String userId, JSONObject params) {
        if (!hasId) {
            context.status(202).raw("");
            return;
        }
        String requestedVersion = params.getString("protocolVersion");
        if (requestedVersion == null || !objectValue(params, "clientInfo")
                || !objectValue(params, "capabilities")) {
            respondError(context, id, 400, -32602, "Invalid initialize params");
            return;
        }
        if (!PROTOCOL_VERSION.equals(requestedVersion)) {
            respondError(context, id, 400, -32602,
                    "Unsupported MCP protocol version; supported version is " + PROTOCOL_VERSION);
            return;
        }
        try {
            McpSessionManager.Session session = sessions.create(userId);
            context.header(SESSION_HEADER, session.id());
            writeRpc(context, rpcResult(id, Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of("tools", Map.of(), "resources", Map.of(), "prompts", Map.of()),
                    "serverInfo", Map.of("name", "aura-mcp", "version", McpProtocolConstants.SERVER_VERSION))));
        } catch (McpSessionManager.SessionLimitException full) {
            respondError(context, id, 503, -32003, "MCP session capacity is exhausted");
        }
    }

    private DispatchResult dispatch(AuraMcpRuntime runtime, String userId, String method, JSONObject params) {
        Object result = switch (method) {
            case "ping" -> Map.of();
            case "notifications/initialized" -> Map.of();
            case "tools/list" -> router.buildSchema();
            case "resources/list" -> router.listResources();
            case "resources/read" -> validText(params, "uri") ? router.readResource(params.getString("uri")) : INVALID;
            case "prompts/list" -> router.listPrompts();
            case "prompts/get" -> {
                if (!validText(params, "name") || !objectOrAbsent(params, "arguments")) yield INVALID;
                JSONObject arguments = params.getJSONObject("arguments");
                yield router.getPrompt(params.getString("name"), arguments == null
                        ? Map.of() : arguments.toJavaObject(Map.class));
            }
            case "tools/call" -> validText(params, "name") && objectOrAbsent(params, "arguments")
                    ? callTool(runtime, userId, params) : INVALID;
            default -> null;
        };
        return result == INVALID ? new DispatchResult(null, true) : new DispatchResult(result, false);
    }

    private Object callTool(AuraMcpRuntime runtime, String userId, JSONObject params) {
        String toolName = params.getString("name");
        JSONObject arguments = params.getJSONObject("arguments");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = arguments == null ? Map.of() : arguments.toJavaObject(Map.class);
        try {
            io.aura.contract.McpAuthorizer authorizer = runtime.mcpAuthorizer();
            Object value = router.invoke(toolName, args, userId,
                    authorizer == null ? null : authorizer::authorize);
            return McpContent.result(value);
        } catch (SecurityException denied) {
            return McpContent.error("MCP_PERMISSION_DENIED");
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(McpHttpTransport.class)
                    .warn("MCP HTTP tool invocation failed for {}", toolName, failure);
            return McpContent.error("MCP_TOOL_ERROR");
        }
    }

    private AuraMcpRuntime runtime(BaseContext context) {
        Object value = context.get("_app", Object.class);
        if (!(value instanceof AuraMcpRuntime runtime)) {
            throw new IllegalStateException("MCP HTTP transport requires an Aura MCP runtime");
        }
        return runtime;
    }

    private static String authenticate(AuraMcpRuntime runtime, BaseContext context) {
        if (runtime.mcpAuthenticator() == null || context == null) return null;
        Map<String, String> metadata = new LinkedHashMap<>();
        add(metadata, "authorization", context.header("Authorization"));
        add(metadata, "x-api-key", context.header("X-API-Key"));
        add(metadata, "user-agent", context.header("User-Agent"));
        try {
            String identity = runtime.mcpAuthenticator().authenticate(Map.copyOf(metadata));
            return identity == null || identity.isBlank() ? null : identity.trim();
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(McpHttpTransport.class)
                    .warn("MCP HTTP authentication failed", failure);
            return null;
        }
    }

    private static boolean originAllowed(BaseContext context) {
        String origin = context.header("Origin");
        if (origin == null || origin.isBlank()) return true;
        Object value = context.get("_app", Object.class);
        if (!(value instanceof io.aura.AuraWebRuntime runtime)) return false;
        String configured = runtime.prop("aura.mcp.http.origins");
        if (configured == null || configured.isBlank()) return false;
        return java.util.Arrays.stream(configured.split(","))
                .map(String::trim).anyMatch(origin::equals);
    }

    private static boolean acceptsJsonRpc(BaseContext context) {
        String accept = context.header("Accept");
        if (accept == null || accept.isBlank()) return true;
        for (String value : accept.split(",")) {
            String mediaType = value.split(";", 2)[0].trim();
            if ("application/json".equalsIgnoreCase(mediaType)
                    || "text/event-stream".equalsIgnoreCase(mediaType)
                    || "*/*".equals(mediaType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validText(JSONObject params, String key) {
        Object value = params.get(key);
        return value instanceof String text && !text.isBlank();
    }

    private static boolean objectOrAbsent(JSONObject params, String key) {
        Object value = params.get(key);
        return value == null || value instanceof JSONObject || value instanceof Map<?, ?>;
    }

    private static boolean objectValue(JSONObject params, String key) {
        Object value = params.get(key);
        return value instanceof JSONObject || value instanceof Map<?, ?>;
    }

    private static void add(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) metadata.put(key, value);
    }

    private static Map<String, Object> rpcResult(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private void respondError(BaseContext context, Object id, int status, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("jsonrpc", "2.0");
        error.put("id", id);
        error.put("error", Map.of("code", code, "message", message));
        context.status(status);
        writeRpc(context, error);
    }

    private void writeRpc(BaseContext context, Map<String, Object> response) {
        String json = JSON.toJSONString(response, JSONWriter.Feature.WriteNulls);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxResponseBytes) {
            writeOversizeError(context);
            return;
        }
        context.jsonRaw(json);
    }

    private void writeOversizeError(BaseContext context) {
        String fallback = JSON.toJSONString(McpContent.error("MCP_RESPONSE_TOO_LARGE"));
        if (fallback.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxResponseBytes) {
            context.status(502).jsonRaw(fallback);
        } else {
            // A valid JSON error would itself violate the configured byte
            // budget. Return a bounded empty body rather than bypassing the
            // limit through BaseContext.json(...).
            context.status(502).raw("");
        }
    }

    private static final Object INVALID = new Object();
    private record DispatchResult(Object result, boolean invalidParams) { }
}

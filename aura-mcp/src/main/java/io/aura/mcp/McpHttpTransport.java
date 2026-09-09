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

    private final McpRouter router;
    private final McpSessionManager sessions;

    McpHttpTransport(McpRouter router, McpSessionManager sessions) {
        this.router = java.util.Objects.requireNonNull(router, "router");
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
    }

    void post(BaseContext context) {
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
        if (!sessions.close(context.header(SESSION_HEADER), userId)) {
            respondError(context, null, 404, -32002, "MCP session not found or expired");
            return;
        }
        context.status(204).raw("");
    }

    private void initialize(BaseContext context, boolean hasId, Object id, String userId, JSONObject params) {
        if (!hasId) {
            context.status(202).raw("");
            return;
        }
        String requestedVersion = params.getString("protocolVersion");
        if (requestedVersion == null || params.getJSONObject("clientInfo") == null
                || params.getJSONObject("capabilities") == null) {
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

    private static boolean validText(JSONObject params, String key) {
        Object value = params.get(key);
        return value instanceof String text && !text.isBlank();
    }

    private static boolean objectOrAbsent(JSONObject params, String key) {
        Object value = params.get(key);
        return value == null || value instanceof JSONObject || value instanceof Map<?, ?>;
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

    private static void respondError(BaseContext context, Object id, int status, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("jsonrpc", "2.0");
        error.put("id", id);
        error.put("error", Map.of("code", code, "message", message));
        context.status(status);
        writeRpc(context, error);
    }

    private static void writeRpc(BaseContext context, Map<String, Object> response) {
        context.jsonRaw(JSON.toJSONString(response, JSONWriter.Feature.WriteNulls));
    }

    private static final Object INVALID = new Object();
    private record DispatchResult(Object result, boolean invalidParams) { }
}

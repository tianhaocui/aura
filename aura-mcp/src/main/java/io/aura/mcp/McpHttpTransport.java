package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import io.aura.AuraMcpRuntime;
import io.aura.web.BaseContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authenticated JSON-RPC over HTTP handler backed by one {@link McpRouter}. */
final class McpHttpTransport {
    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    static final String PROTOCOL_VERSION = McpProtocolConstants.VERSION;
    static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int SSE_MESSAGE_FRAME_BYTES = 23;

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
        java.util.Optional<McpSessionManager.Session> inspected = sessions.inspect(sessionId, userId);
        if (inspected.isEmpty()) {
            context.status(404).raw("MCP session not found or expired");
            return;
        }
        if (!protocolVersionAllowed(context, inspected.get().protocolVersion())) {
            context.status(400).raw("Unsupported MCP protocol version");
            return;
        }
        if (inspected.get().phase() != McpSessionManager.SessionPhase.READY) {
            context.status(409).raw("MCP session is not ready");
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
        java.util.Optional<McpSessionManager.RequestLease> admitted = sessions.admitReady(sessionId, userId);
        if (admitted.isEmpty()) {
            context.status(404).raw("MCP session not found, closed, or expired");
            return;
        }
        boolean attached = false;
        McpSseHub.ConnectionHandle connectionHandle = null;
        try (McpSessionManager.RequestLease ignored = admitted.get()) {
            if (!sessions.active(sessionId, userId)) {
                context.status(404).raw("MCP session not found, closed, or expired");
                return;
            }
            io.aura.web.SseEmitter emitter = context.sse();
            McpSseHub.Attachment attachment = sseHub.attachWithHandle(
                    sessionId, emitter, context.cancellationToken(), lastEventId);
            if (attachment.result() != McpSseHub.AttachResult.ATTACHED) {
                emitter.close();
                return;
            }
            connectionHandle = attachment.handle();
            attached = true;
            if (!sessions.active(sessionId, userId)) return;
            emitter.send("endpoint", httpPath);
            while (!context.isCancelled()) {
                try {
                    if (!sessions.active(sessionId, userId)) break;
                    McpSseHub.WriteResult write = sseHub.writeNext(
                            connectionHandle, java.time.Duration.ofSeconds(1));
                    if (write == McpSseHub.WriteResult.CLOSED) break;
                    if (write == McpSseHub.WriteResult.IDLE) emitter.send("heartbeat", "");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception failure) {
            if (attached) sseHub.close(connectionHandle);
            if (!context.isResponseStarted()) context.status(500).raw("MCP SSE unavailable");
        } finally {
            if (attached) {
                if (sessions.active(sessionId, userId)) sseHub.close(connectionHandle);
                else sseHub.remove(connectionHandle);
            }
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
        if (!hasJsonContentType(context)) {
            respondError(context, null, 415, -32006,
                    "MCP POST requires Content-Type: application/json");
            return;
        }
        AuraMcpRuntime runtime = runtime(context);
        String userId = authenticate(runtime, context);
        if (userId == null) {
            respondError(context, null, 401, -32001, "Authentication required");
            return;
        }
        Object parsed;
        try {
            parsed = JSON.parse(context.bodyText());
        } catch (Exception invalidJson) {
            respondError(context, null, 400, -32700, "Parse error");
            return;
        }
        if (parsed instanceof JSONArray batch) {
            if (!acceptsApplicationJson(context)) {
                respondError(context, null, 406, -32005,
                        "MCP batch responses require Accept: application/json");
                return;
            }
            postBatch(context, runtime, userId, batch);
            return;
        }
        JSONObject request = parsed instanceof JSONObject value ? value : null;
        Object id = request == null ? null : request.get("id");
        boolean hasId = request != null && request.containsKey("id");
        boolean sseResponse = hasId && prefersSseResponse(context);
        if (request == null || !"2.0".equals(request.getString("jsonrpc"))) {
            respondError(context, id, 400, -32600, "Invalid Request", sseResponse);
            return;
        }
        if (hasId && id != null && !(id instanceof String) && !(id instanceof Number)) {
            respondError(context, null, 400, -32600,
                    "Invalid Request: id must be a string, number, or null", sseResponse);
            return;
        }
        String method = request.getString("method");
        if (method == null || method.isBlank()) {
            respondError(context, id, 400, -32600,
                    "Invalid Request: method is required", sseResponse);
            return;
        }
        if ("initialize".equals(method)) sseResponse = false;
        if ("initialize".equals(method) && !acceptsApplicationJson(context)) {
            respondError(context, id, 406, -32005,
                    "MCP initialize requires Accept: application/json");
            return;
        }
        Object rawParams = request.get("params");
        if (rawParams != null && !(rawParams instanceof JSONObject)) {
            if (hasId) respondError(context, id, 400, -32602, "Invalid params", sseResponse);
            else context.status(202).raw("");
            return;
        }

        if ("initialize".equals(method)) {
            initialize(context, hasId, id, userId,
                    request.getJSONObject("params") == null ? new JSONObject() : request.getJSONObject("params"));
            return;
        }

        String sessionId = context.header(SESSION_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            respondError(context, id, 400, -32002, "Mcp-Session-Id is required", sseResponse);
            return;
        }
        java.util.Optional<McpSessionManager.Session> inspected = sessions.inspect(sessionId, userId);
        if (inspected.isEmpty()) {
            respondError(context, id, 404, -32002,
                    "MCP session not found or expired", sseResponse);
            return;
        }
        if (!protocolVersionAllowed(context, inspected.get().protocolVersion())) {
            respondError(context, id, 400, -32008,
                    "Unsupported MCP protocol version; expected "
                            + inspected.get().protocolVersion(), sseResponse);
            return;
        }

        if ("notifications/initialized".equals(method)) {
            initialized(context, hasId, id, sessionId, userId, sseResponse);
            return;
        }

        if (inspected.get().phase() == McpSessionManager.SessionPhase.NEGOTIATING) {
            if (!"ping".equals(method)) {
                respondError(context, id, 409, -32007,
                        "MCP session is negotiating; send notifications/initialized first",
                        sseResponse);
                return;
            }
            if (sessions.access(sessionId, userId).isEmpty()) {
                respondError(context, id, 404, -32002,
                        "MCP session not found or expired", sseResponse);
                return;
            }
            writeDispatch(context, runtime, userId, hasId, id, method, sseResponse,
                    request.getJSONObject("params"));
            return;
        }

        java.util.Optional<McpSessionManager.RequestLease> admitted = sessions.admitReady(sessionId, userId);
        if (admitted.isEmpty()) {
            respondError(context, id, 404, -32002,
                    "MCP session not found, closed, or expired", sseResponse);
            return;
        }

        try (McpSessionManager.RequestLease ignored = admitted.get()) {
            writeDispatch(context, runtime, userId, hasId, id, method, sseResponse,
                    request.getJSONObject("params"));
        }
    }

    private void writeDispatch(BaseContext context, AuraMcpRuntime runtime, String userId,
                               boolean hasId, Object id, String method, boolean sseResponse,
                               JSONObject rawParams) {
        JSONObject params = rawParams == null ? new JSONObject() : rawParams;
        DispatchResult dispatched;
        try {
            dispatched = dispatch(runtime, userId, method, params);
        } catch (RuntimeException invalidParams) {
            org.slf4j.LoggerFactory.getLogger(McpHttpTransport.class)
                    .debug("MCP request parameter conversion failed for {}", method, invalidParams);
            if (hasId) respondError(context, id, 400, -32602, "Invalid params", sseResponse);
            else context.status(202).raw("");
            return;
        }
        if (!hasId) {
            context.status(202).raw("");
            return;
        }
        if (dispatched.invalidParams()) {
            respondError(context, id, 400, -32602, "Invalid params", sseResponse);
            return;
        }
        if (dispatched.result() == null) {
            respondError(context, id, 200, -32601,
                    "Method not found: " + method, sseResponse);
            return;
        }
        writeRpcResponse(context, rpcResult(id, dispatched.result()), id, sseResponse);
    }

    private void initialized(BaseContext context, boolean hasId, Object id,
                             String sessionId, String userId, boolean sseResponse) {
        if (hasId) {
            respondError(context, id, 400, -32600,
                    "notifications/initialized must not include an id", sseResponse);
            return;
        }
        McpSessionManager.TransitionResult result = sessions.activate(sessionId, userId);
        if (result == McpSessionManager.TransitionResult.NOT_FOUND) {
            context.status(404).raw("");
        } else if (result == McpSessionManager.TransitionResult.INVALID_PHASE) {
            context.status(409).raw("");
        } else {
            context.status(202).raw("");
        }
    }

    private static boolean hasJsonContentType(BaseContext context) {
        String contentType = context.header("Content-Type");
        if (contentType == null) return false;
        int separator = contentType.indexOf(';');
        String mediaType = separator >= 0 ? contentType.substring(0, separator) : contentType;
        return "application/json".equalsIgnoreCase(mediaType.trim());
    }

    private static boolean protocolVersionAllowed(BaseContext context, String negotiatedVersion) {
        String declared = context.header(PROTOCOL_VERSION_HEADER);
        return declared == null || negotiatedVersion.equals(declared.trim());
    }

    private void postBatch(BaseContext context, AuraMcpRuntime runtime,
                           String userId, JSONArray batch) {
        if (batch.isEmpty() || batch.size() > 32) {
            respondError(context, null, 400, -32600, "Invalid Request: batch size must be 1..32");
            return;
        }
        String sessionId = context.header(SESSION_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            respondError(context, null, 400, -32002, "Mcp-Session-Id is required");
            return;
        }
        java.util.Optional<McpSessionManager.Session> inspected = sessions.inspect(sessionId, userId);
        if (inspected.isEmpty()) {
            respondError(context, null, 404, -32002, "MCP session not found or expired");
            return;
        }
        if (!protocolVersionAllowed(context, inspected.get().protocolVersion())) {
            respondError(context, null, 400, -32008,
                    "Unsupported MCP protocol version; expected " + inspected.get().protocolVersion());
            return;
        }
        if (inspected.get().phase() != McpSessionManager.SessionPhase.READY) {
            respondError(context, null, 409, -32007,
                    "MCP session is negotiating; send notifications/initialized first");
            return;
        }
        java.util.Optional<McpSessionManager.RequestLease> admitted = sessions.admitReady(sessionId, userId);
        if (admitted.isEmpty()) {
            respondError(context, null, 404, -32002, "MCP session not found, closed, or expired");
            return;
        }
        List<Map<String, Object>> responses = new java.util.ArrayList<>();
        try (McpSessionManager.RequestLease ignored = admitted.get()) {
            for (Object raw : batch) {
                if (!(raw instanceof JSONObject request)) {
                    responses.add(rpcError(null, -32600, "Invalid Request"));
                    continue;
                }
                Object id = request.get("id");
                boolean hasId = request.containsKey("id");
                if (!"2.0".equals(request.getString("jsonrpc"))) {
                    responses.add(rpcError(hasId ? id : null, -32600, "Invalid Request"));
                    continue;
                }
                String method = request.getString("method");
                if (method == null || method.isBlank()) {
                    responses.add(rpcError(hasId ? id : null, -32600, "Invalid Request: method is required"));
                    continue;
                }
                if ("initialize".equals(method)) {
                    responses.add(rpcError(hasId ? id : null, -32600,
                            "initialize must be sent as a single request"));
                    continue;
                }
                if ("notifications/initialized".equals(method)) {
                    if (hasId) {
                        responses.add(rpcError(id, -32600,
                                "notifications/initialized must be sent as a standalone notification"));
                    }
                    continue;
                }
                if (id != null && !(id instanceof String) && !(id instanceof Number)) {
                    responses.add(rpcError(null, -32600, "Invalid Request: id must be a string, number, or null"));
                    continue;
                }
                Object rawParams = request.get("params");
                if (rawParams != null && !(rawParams instanceof JSONObject)) {
                    if (hasId) responses.add(rpcError(id, -32602, "Invalid params"));
                    continue;
                }
                DispatchResult dispatched;
                try {
                    dispatched = dispatch(runtime, userId, method,
                            request.getJSONObject("params") == null
                                    ? new JSONObject() : request.getJSONObject("params"));
                } catch (RuntimeException invalid) {
                    dispatched = new DispatchResult(null, true);
                }
                if (!hasId) continue;
                if (dispatched.invalidParams()) {
                    responses.add(rpcError(id, -32602, "Invalid params"));
                } else if (dispatched.result() == null) {
                    responses.add(rpcError(id, -32601, "Method not found: " + method));
                } else {
                    responses.add(rpcResult(id, dispatched.result()));
                }
            }
        }
        if (responses.isEmpty()) {
            context.status(202).raw("");
        } else {
            writeRpc(context, responses);
        }
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
        java.util.Optional<McpSessionManager.Session> inspected = sessions.inspect(sessionId, userId);
        if (inspected.isEmpty()) {
            respondError(context, null, 404, -32002, "MCP session not found or expired");
            return;
        }
        if (!protocolVersionAllowed(context, inspected.get().protocolVersion())) {
            respondError(context, null, 400, -32008,
                    "Unsupported MCP protocol version; expected " + inspected.get().protocolVersion());
            return;
        }
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
            McpSessionManager.Session session = sessions.create(userId, requestedVersion);
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
            return McpContent.result(value, Math.max(1, maxResponseBytes / 2));
        } catch (BoundedJson.LimitExceededException tooLarge) {
            return McpContent.error("MCP_RESPONSE_TOO_LARGE");
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
        return acceptsMediaType(accept, "application/json")
                || acceptsMediaType(accept, "text/event-stream");
    }

    private static boolean acceptsApplicationJson(BaseContext context) {
        String accept = context.header("Accept");
        return accept == null || accept.isBlank()
                || acceptsMediaType(accept, "application/json");
    }

    private static boolean prefersSseResponse(BaseContext context) {
        String accept = context.header("Accept");
        return accept != null && qualityForMediaType(accept, "text/event-stream")
                > qualityForMediaType(accept, "application/json");
    }

    private static boolean acceptsMediaType(String header, String expected) {
        return qualityForMediaType(header, expected) > 0.0;
    }

    private static double qualityForMediaType(String header, String expected) {
        String expectedType = expected.substring(0, expected.indexOf('/'));
        int bestSpecificity = -1;
        double best = 0.0;
        for (String raw : header.split(",")) {
            String[] parts = raw.trim().split(";");
            String mediaType = parts[0].trim();
            double quality = 1.0;
            for (int index = 1; index < parts.length; index++) {
                String parameter = parts[index].trim();
                int equals = parameter.indexOf('=');
                if (equals < 0 || !"q".equalsIgnoreCase(parameter.substring(0, equals).trim())) continue;
                try {
                    quality = Double.parseDouble(parameter.substring(equals + 1).trim());
                } catch (NumberFormatException invalid) {
                    quality = 0.0;
                }
            }
            if (!Double.isFinite(quality) || quality < 0.0 || quality > 1.0) quality = 0.0;
            int specificity = expected.equalsIgnoreCase(mediaType) ? 2
                    : (expectedType + "/*").equalsIgnoreCase(mediaType) ? 1
                    : "*/*".equals(mediaType) ? 0 : -1;
            if (specificity < 0 || specificity < bestSpecificity) continue;
            if (specificity > bestSpecificity) {
                bestSpecificity = specificity;
                best = quality;
            } else {
                best = Math.max(best, quality);
            }
        }
        return best;
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
        respondError(context, id, status, code, message, false);
    }

    private void respondError(BaseContext context, Object id, int status, int code,
                              String message, boolean sseResponse) {
        Map<String, Object> error = rpcError(id, code, message);
        context.status(status);
        writeRpcResponse(context, error, id, sseResponse);
    }

    private static Map<String, Object> rpcError(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("jsonrpc", "2.0");
        error.put("id", id);
        error.put("error", Map.of("code", code, "message", message));
        return error;
    }

    private void writeRpc(BaseContext context, Object response) {
        final String json;
        try {
            json = BoundedJson.encode(response, maxResponseBytes);
        } catch (BoundedJson.LimitExceededException tooLarge) {
            writeOversizeError(context);
            return;
        }
        context.jsonRaw(json);
    }

    private void writeRpcResponse(
            BaseContext context, Object response, Object id, boolean sseResponse) {
        if (!sseResponse) {
            writeRpc(context, response);
            return;
        }
        writeRpcSse(context, response, id);
    }

    private void writeRpcSse(BaseContext context, Object response, Object id) {
        final String json;
        int payloadBudget = maxResponseBytes - SSE_MESSAGE_FRAME_BYTES;
        if (payloadBudget < 1) {
            context.status(502).raw("");
            return;
        }
        try {
            json = BoundedJson.encode(response, payloadBudget);
        } catch (BoundedJson.LimitExceededException tooLarge) {
            writeOversizeSseError(context, id);
            return;
        }
        io.aura.web.SseEmitter emitter = null;
        try {
            emitter = context.sse();
            emitter.send("message", json);
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(McpHttpTransport.class)
                    .debug("MCP request-scoped SSE response failed", failure);
            if (!context.isResponseStarted()) context.status(500).raw("");
        } finally {
            if (emitter != null) emitter.close();
        }
    }

    private void writeOversizeSseError(BaseContext context, Object id) {
        context.status(502);
        int payloadBudget = maxResponseBytes - SSE_MESSAGE_FRAME_BYTES;
        if (payloadBudget < 1) {
            context.raw("");
            return;
        }
        try {
            String fallback = BoundedJson.encode(
                    rpcError(id, -32009, "MCP response exceeds the configured byte limit"),
                    payloadBudget);
            io.aura.web.SseEmitter emitter = context.sse();
            try {
                emitter.send("message", fallback);
            } finally {
                emitter.close();
            }
        } catch (Exception tooSmallOrDisconnected) {
            if (!context.isResponseStarted()) context.raw("");
        }
    }

    private void writeOversizeError(BaseContext context) {
        try {
            String fallback = BoundedJson.encode(McpContent.error("MCP_RESPONSE_TOO_LARGE"), maxResponseBytes);
            context.status(502).jsonRaw(fallback);
        } catch (BoundedJson.LimitExceededException tooLarge) {
            // A valid JSON error would itself violate the configured byte
            // budget. Return a bounded empty body rather than bypassing the
            // limit through BaseContext.json(...).
            context.status(502).raw("");
        }
    }

    private static final Object INVALID = new Object();
    private record DispatchResult(Object result, boolean invalidParams) { }
}

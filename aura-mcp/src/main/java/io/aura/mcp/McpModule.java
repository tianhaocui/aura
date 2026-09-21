package io.aura.mcp;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.McpRouterSpec;
import io.aura.contract.RouteContribution;
import io.aura.web.BaseHandler;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Explicit Aura module for MCP tool/resource/prompt registration. */
public final class McpModule implements AuraModule {
    private final McpRouterSpec router;
    private final String httpPath;
    private final McpSessionManager sessions;
    private final int maxResponseBytes;
    private final boolean sseEnabled;
    private final McpSseHub sseHub;

    private McpModule(McpRouterSpec router, String httpPath, McpSessionManager sessions, int maxResponseBytes,
                      boolean sseEnabled, McpSseHub sseHub) {
        this.router = Objects.requireNonNull(router, "router");
        this.httpPath = httpPath;
        this.sessions = sessions;
        this.maxResponseBytes = maxResponseBytes;
        this.sseEnabled = sseEnabled;
        this.sseHub = sseHub;
    }

    public static McpModule create() { return new McpModule(new McpRouter(), null, null, 0, false, null); }
    public static McpModule create(McpRouter router) { return new McpModule(router, null, null, 0, false, null); }
    public static McpModule create(McpRouterSpec router) {
        return new McpModule(router, null, null, 0, false, null);
    }

    /** Enables authenticated JSON-RPC over HTTP at {@code /mcp}. */
    public McpModule http() {
        return http("/mcp", McpSessionManager.DEFAULT_MAX_SESSIONS,
                McpSessionManager.DEFAULT_IDLE_TTL, McpHttpTransport.DEFAULT_MAX_RESPONSE_BYTES);
    }

    public McpModule http(String path, int maxSessions, Duration idleTtl) {
        return http(path, maxSessions, idleTtl, McpHttpTransport.DEFAULT_MAX_RESPONSE_BYTES);
    }

    public McpModule http(String path, int maxSessions, Duration idleTtl, int maxResponseBytes) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("MCP HTTP path must start with '/'");
        }
        if (maxResponseBytes < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        return new McpModule(router, path.trim(), new McpSessionManager(maxSessions, idleTtl), maxResponseBytes,
                false, null);
    }

    /** Enables the authenticated server-to-client SSE stream on the HTTP endpoint. */
    public McpModule sse() {
        if (sessions == null) return http().sse();
        if (sseEnabled) return this;
        return new McpModule(router, httpPath, sessions, maxResponseBytes, true,
                new McpSseHub(maxResponseBytes));
    }

    /** Enables SSE with explicit per-connection queue and per-session replay bounds. */
    public McpModule sse(int maxQueueEvents, int maxQueueBytes,
                         int maxHistoryEvents, int maxHistoryBytes) {
        if (sessions == null) {
            return http().sse(maxQueueEvents, maxQueueBytes, maxHistoryEvents, maxHistoryBytes);
        }
        return new McpModule(router, httpPath, sessions, maxResponseBytes, true,
                new McpSseHub(maxResponseBytes, maxQueueEvents, maxQueueBytes,
                        maxHistoryEvents, maxHistoryBytes));
    }

    @Override public ModuleMetadata descriptor() {
        ModuleMetadata metadata = ModuleMetadata.of("mcp", "1.0.0")
                .provides("mcp.tools", "mcp.resources", "mcp.prompts");
        if (sessions != null) metadata = metadata.provides("mcp.http");
        return sseEnabled ? metadata.provides("mcp.sse") : metadata;
    }

    @Override public void stop(ModuleContext context) {
        if (sessions != null) sessions.close();
        if (sseHub != null) sseHub.close();
    }

    @Override public void install(ModuleContext context) {
        context.register(router);
        context.register("mcp", router);
        if (sessions != null) {
            context.register(sessions);
            if (sseHub != null) sessions.onSessionRemoved(sseHub::sessionRemoved);
            McpHttpTransport transport = new McpHttpTransport(router, sessions, maxResponseBytes, sseHub, httpPath);
            context.route(new RouteContribution("POST", httpPath,
                    (BaseHandler) transport::post,
                    Map.of("description", "MCP JSON-RPC transport", "returnType", "JsonRpcResponse")));
            context.route(new RouteContribution("DELETE", httpPath,
                    (BaseHandler) transport::delete,
                    Map.of("description", "Close MCP session", "returnType", "void")));
            if (sseEnabled) {
                context.route(new RouteContribution("GET", httpPath,
                        (BaseHandler) transport::get,
                        Map.of("description", "MCP server-sent events transport",
                                "returnType", "text/event-stream", "streaming", true)));
            }
        }
    }

    /** Returns the built-in mutable router or fails for a custom router implementation. */
    public McpRouter router() {
        if (router instanceof McpRouter builtIn) return builtIn;
        throw new IllegalStateException("This MCP module uses a custom McpRouterSpec; use routerSpec()");
    }
    public McpRouterSpec routerSpec() { return router; }
    public boolean httpEnabled() { return sessions != null; }
    public String httpPath() { return httpPath; }
    public McpSessionManager sessions() { return sessions; }
    public boolean sseEnabled() { return sseEnabled; }
    public java.util.Optional<McpSseMetrics> sseMetrics() {
        return sseHub == null ? java.util.Optional.empty()
                : java.util.Optional.of(sseHub.metrics());
    }
    public boolean publish(String sessionId, String method, Map<String, ?> params) {
        if (sseHub == null) return false;
        return sseHub.publish(sessionId, method, params);
    }
}

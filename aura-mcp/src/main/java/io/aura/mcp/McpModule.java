package io.aura.mcp;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import io.aura.web.BaseHandler;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Explicit Aura module for MCP tool/resource/prompt registration. */
public final class McpModule implements AuraModule {
    private final McpRouter router;
    private final String httpPath;
    private final McpSessionManager sessions;

    private McpModule(McpRouter router, String httpPath, McpSessionManager sessions) {
        this.router = Objects.requireNonNull(router, "router");
        this.httpPath = httpPath;
        this.sessions = sessions;
    }

    public static McpModule create() { return new McpModule(new McpRouter(), null, null); }
    public static McpModule create(McpRouter router) { return new McpModule(router, null, null); }

    /** Enables authenticated JSON-RPC over HTTP at {@code /mcp}. */
    public McpModule http() {
        return http("/mcp", McpSessionManager.DEFAULT_MAX_SESSIONS,
                McpSessionManager.DEFAULT_IDLE_TTL);
    }

    public McpModule http(String path, int maxSessions, Duration idleTtl) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("MCP HTTP path must start with '/'");
        }
        return new McpModule(router, path.trim(), new McpSessionManager(maxSessions, idleTtl));
    }

    @Override public ModuleMetadata descriptor() {
        ModuleMetadata metadata = ModuleMetadata.of("mcp", "1.0.0")
                .provides("mcp.tools", "mcp.resources", "mcp.prompts");
        return sessions == null ? metadata : metadata.provides("mcp.http");
    }

    @Override public void stop(ModuleContext context) {
        if (sessions != null) sessions.close();
    }

    @Override public void install(ModuleContext context) {
        context.register(router);
        context.register("mcp", router);
        if (sessions != null) {
            context.register(sessions);
            McpHttpTransport transport = new McpHttpTransport(router, sessions);
            context.route(new RouteContribution("POST", httpPath,
                    (BaseHandler) transport::post,
                    Map.of("description", "MCP JSON-RPC transport", "returnType", "JsonRpcResponse")));
            context.route(new RouteContribution("DELETE", httpPath,
                    (BaseHandler) transport::delete,
                    Map.of("description", "Close MCP session", "returnType", "void")));
        }
    }

    public McpRouter router() { return router; }
    public boolean httpEnabled() { return sessions != null; }
    public String httpPath() { return httpPath; }
    public McpSessionManager sessions() { return sessions; }
}

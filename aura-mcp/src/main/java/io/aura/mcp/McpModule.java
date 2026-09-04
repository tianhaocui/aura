package io.aura.mcp;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.ModuleDescriptor;

import java.util.Objects;

/** Explicit Aura module for MCP tool/resource/prompt registration. */
public final class McpModule implements AuraModule {
    private final McpRouter router;

    private McpModule(McpRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    public static McpModule create() { return new McpModule(new McpRouter()); }
    public static McpModule create(McpRouter router) { return new McpModule(router); }

    @Override public ModuleDescriptor descriptor() {
        return ModuleDescriptor.of("mcp", "1.0.0").build();
    }

    @Override public void install(ModuleContext context) {
        context.register(router);
        context.register("mcp", router);
    }

    public McpRouter router() { return router; }
}

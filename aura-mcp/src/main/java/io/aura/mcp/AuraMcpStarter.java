package io.aura.mcp;

import io.aura.Aura;
import io.aura.McpStarter;
import io.aura.web.CompiledRoute;

import java.util.List;

public class AuraMcpStarter implements McpStarter {

    private McpServer server;

    @Override
    @SuppressWarnings("unchecked")
    public void start(Aura app) {
        List<CompiledRoute> routes = (List<CompiledRoute>) app.getCompiledRoutes();
        if (routes == null) {
            throw new IllegalStateException("No compiled routes. Ensure web starter runs before MCP.");
        }
        int port = app.mcpPort() > 0 ? app.mcpPort() : app.port() + 1;
        server = new McpServer(port, routes);
        server.start();
    }

    @Override
    public void startStdio(Aura app) {
        try {
            java.io.PrintStream out = app.mcpStdout() != null ? app.mcpStdout() : System.out;
            new McpBridge("http://localhost:" + app.port(), out).run();
        } catch (Exception e) {
            throw new RuntimeException("MCP stdio failed", e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}

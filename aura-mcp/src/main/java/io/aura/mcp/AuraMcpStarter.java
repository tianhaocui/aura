package io.aura.mcp;

import io.aura.Aura;
import io.aura.McpStarter;

public class AuraMcpStarter implements McpStarter {

    @Override
    public void start(Aura app) {
        // SSE mode removed — use --mcp-stdio or npm bridge instead
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
    public void stop() {}
}

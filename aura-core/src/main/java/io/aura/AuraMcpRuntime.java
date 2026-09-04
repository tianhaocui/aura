package io.aura;

import io.aura.contract.McpRouterSpec;

import java.io.PrintStream;

/** Stable runtime contract consumed by the optional MCP transport adapter. */
public interface AuraMcpRuntime extends AuraRuntime {
    int port();
    int mcpPort();
    PrintStream mcpStdout();
    McpRouterSpec mcpRouter();
}

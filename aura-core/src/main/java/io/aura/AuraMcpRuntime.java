package io.aura;

import io.aura.contract.McpRouterSpec;
import io.aura.contract.McpAuthenticator;
import io.aura.contract.McpAuthorizer;

import java.io.PrintStream;

/** Stable runtime contract consumed by the optional MCP transport adapter. */
public interface AuraMcpRuntime extends AuraRuntime {
    int port();
    int mcpPort();
    PrintStream mcpStdout();
    McpRouterSpec mcpRouter();
    /** Optional transport-neutral MCP identity resolver. */
    default McpAuthenticator mcpAuthenticator() { return null; }
    /** Optional transport-neutral MCP permission checker. */
    default McpAuthorizer mcpAuthorizer() { return null; }
}

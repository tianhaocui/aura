package io.aura.mcp;

/** Explicit MCP content returned by a tool or prompt handler. */
public sealed interface McpOutput permits McpText, McpStructured, McpImage, McpResourceLink { }

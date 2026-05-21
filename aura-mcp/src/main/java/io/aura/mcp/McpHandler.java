package io.aura.mcp;

@FunctionalInterface
public interface McpHandler {
    Object handle(McpContext ctx) throws Exception;
}

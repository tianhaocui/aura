package io.aura.contract;

/** Explicit permission decision for an authenticated MCP caller. */
@FunctionalInterface
public interface McpAuthorizer {
    boolean authorize(String userId, String permission);
}

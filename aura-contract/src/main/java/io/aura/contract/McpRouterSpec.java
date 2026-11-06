package io.aura.contract;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/** Provider-neutral MCP catalog and invocation boundary implemented by optional adapters. */
public interface McpRouterSpec {
    /** Returns the MCP {@code tools/list} result object. */
    Map<String, Object> buildSchema();

    /** Invokes a tool with optional authenticated identity and permission policy. */
    Object invoke(String toolName, Map<String, Object> arguments, String userId,
                  BiPredicate<String, String> permissionChecker) throws Exception;

    default Map<String, Object> listResources() {
        return Map.of("resources", List.of());
    }

    default Map<String, Object> readResource(String uri) {
        throw new IllegalArgumentException("MCP resource not found: " + uri);
    }

    default Map<String, Object> listPrompts() {
        return Map.of("prompts", List.of());
    }

    default Map<String, Object> getPrompt(String name, Map<String, Object> arguments) {
        throw new IllegalArgumentException("MCP prompt not found: " + name);
    }
}

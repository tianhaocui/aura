package io.aura.contract;

import java.util.Map;

/** Resolves an authenticated MCP identity from transport-provided metadata. */
@FunctionalInterface
public interface McpAuthenticator {
    /**
     * Returns a stable user/service identity, or {@code null} when the caller
     * is unauthenticated. Metadata must be treated as untrusted input.
     */
    String authenticate(Map<String, String> metadata);
}

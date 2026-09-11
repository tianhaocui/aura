package io.aura.mcp;

import java.util.Objects;

/** Embedded resource content block returned by an MCP tool. */
public record McpResourceLink(String uri, String mimeType, String text) implements McpOutput {
    public McpResourceLink {
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("resource uri must not be blank");
        if (text == null && mimeType == null) throw new IllegalArgumentException("resource content must not be empty");
        uri = uri.trim();
        if (mimeType != null) mimeType = mimeType.trim();
    }
}

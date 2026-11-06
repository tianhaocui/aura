package io.aura.mcp;

import java.util.Objects;

/** Base64-encoded image content, as required by MCP content blocks. */
public record McpImage(String data, String mimeType) implements McpOutput {
    public McpImage {
        if (data == null || data.isBlank()) throw new IllegalArgumentException("image data must not be blank");
        if (mimeType == null || mimeType.isBlank() || !mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            throw new IllegalArgumentException("image mimeType must be an image/* type");
        }
        data = data.trim();
        mimeType = mimeType.trim();
    }
}

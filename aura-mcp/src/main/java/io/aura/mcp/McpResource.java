package io.aura.mcp;

import java.util.Objects;

public record McpResource(String uri, String name, String description, String mimeType, String text) {

    public McpResource {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(text, "text");
    }
}

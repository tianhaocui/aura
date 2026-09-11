package io.aura.mcp;

import java.util.Objects;

public record McpText(String text) implements McpOutput {
    public McpText { Objects.requireNonNull(text, "text"); }
}

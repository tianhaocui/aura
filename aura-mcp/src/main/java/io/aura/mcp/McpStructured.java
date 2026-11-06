package io.aura.mcp;

import java.util.Map;
import java.util.Objects;

public record McpStructured(Map<String, Object> value) implements McpOutput {
    public McpStructured {
        value = Map.copyOf(Objects.requireNonNull(value, "value"));
    }
}

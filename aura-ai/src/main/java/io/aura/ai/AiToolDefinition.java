package io.aura.ai;

import java.util.Map;

/** Provider-neutral function tool definition sent to a model. */
public record AiToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    public AiToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name must not be blank");
        name = name.trim();
        inputSchema = inputSchema == null
                ? Map.of("type", "object")
                : AiJson.immutableMap(inputSchema, "tool input schema");
    }

    public AiToolDefinition(String name, String description) {
        this(name, description, Map.of("type", "object"));
    }
}

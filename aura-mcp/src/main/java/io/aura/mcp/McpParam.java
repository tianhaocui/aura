package io.aura.mcp;

import java.util.List;

public record McpParam(String name, String type, String description, List<McpEnumValue> enumValues, boolean required) {

    public McpParam(String name, String type, String description, List<McpEnumValue> enumValues) {
        this(name, type, description, enumValues, true);
    }

    public boolean isEnum() {
        return enumValues != null && !enumValues.isEmpty();
    }
}

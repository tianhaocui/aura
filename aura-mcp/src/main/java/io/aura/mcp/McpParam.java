package io.aura.mcp;

import java.util.List;

public record McpParam(String name, String type, String description, List<McpEnumValue> enumValues) {

    public boolean isEnum() {
        return enumValues != null && !enumValues.isEmpty();
    }
}

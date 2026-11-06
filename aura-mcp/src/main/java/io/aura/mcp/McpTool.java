package io.aura.mcp;

import java.util.List;

public record McpTool(String name, String description, List<McpParam> params,
                      McpHandler handler, String permission, boolean authenticated) {
    public McpTool {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name must not be blank");
        params = params == null ? List.of() : List.copyOf(params);
        if (permission != null && permission.isBlank()) permission = null;
        if (permission != null) authenticated = true;
    }
}

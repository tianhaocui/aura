package io.aura.mcp;

import java.util.List;

public record McpTool(String name, String description, List<McpParam> params, McpHandler handler) {}

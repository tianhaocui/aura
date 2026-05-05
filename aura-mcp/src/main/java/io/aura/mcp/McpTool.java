package io.aura.mcp;

import java.util.List;
import java.util.Map;

public record McpTool(
        String name,
        String description,
        String method,
        String path,
        Map<String, Object> inputSchema
) {

    public record Param(String name, String type, String source, String description) {

        public Map<String, Object> toSchemaProperty() {
            var prop = new java.util.LinkedHashMap<String, Object>();
            prop.put("type", jsonType());
            if (description != null && !description.isEmpty()) {
                prop.put("description", description + " (from " + source + ")");
            } else {
                prop.put("description", "from " + source);
            }
            return prop;
        }

        private String jsonType() {
            return switch (type) {
                case "int", "long", "Integer", "Long" -> "integer";
                case "double", "float", "Double", "Float", "BigDecimal" -> "number";
                case "boolean", "Boolean" -> "boolean";
                default -> "string";
            };
        }
    }
}

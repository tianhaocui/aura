package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpContent {
    private static final int DEFAULT_TEXT_BYTES = 1024 * 1024;

    private McpContent() {}

    static Map<String, Object> result(Object value) {
        return result(value, DEFAULT_TEXT_BYTES);
    }

    static Map<String, Object> result(Object value, int maxTextBytes) {
        if (value instanceof McpOutput output) return output(output, maxTextBytes);
        String text = value instanceof String string
                ? string : BoundedJson.encode(value, maxTextBytes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));

        Object structured = value instanceof String string ? parseObject(string) : value;
        if (structured != null && !(structured instanceof String)) {
            result.put("structuredContent", structured);
        }
        return result;
    }

    private static Map<String, Object> output(McpOutput output, int maxTextBytes) {
        Map<String, Object> block = new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        if (output instanceof McpText text) {
            block.put("type", "text");
            block.put("text", text.text());
        } else if (output instanceof McpStructured structured) {
            block.put("type", "text");
            block.put("text", BoundedJson.encode(structured.value(), maxTextBytes));
            result.put("structuredContent", structured.value());
        } else if (output instanceof McpImage image) {
            block.put("type", "image");
            block.put("data", image.data());
            block.put("mimeType", image.mimeType());
        } else if (output instanceof McpResourceLink resource) {
            block.put("type", "resource");
            Map<String, Object> embedded = new LinkedHashMap<>();
            embedded.put("uri", resource.uri());
            if (resource.mimeType() != null) embedded.put("mimeType", resource.mimeType());
            if (resource.text() != null) embedded.put("text", resource.text());
            block.put("resource", embedded);
        }
        result.put("content", List.of(block));
        return result;
    }

    /** Stable, non-sensitive tool failure returned to remote callers. */
    static Map<String, Object> error(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("isError", true);
        result.put("code", code);
        result.put("content", List.of(Map.of(
                "type", "text",
                "text", "Tool invocation failed (" + code + ")")));
        return result;
    }

    private static JSONObject parseObject(String value) {
        try {
            return JSON.parseObject(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}

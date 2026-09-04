package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpContent {

    private McpContent() {}

    static Map<String, Object> result(Object value) {
        String text = value instanceof String string ? string : JSON.toJSONString(value);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));

        Object structured = value instanceof String string ? parseObject(string) : JSON.toJSON(value);
        if (structured instanceof JSONObject) {
            result.put("structuredContent", structured);
        }
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

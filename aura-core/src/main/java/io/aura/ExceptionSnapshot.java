package io.aura;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

public record ExceptionSnapshot(
    String requestId,
    String method,
    String path,
    Map<String, String> headers,
    Map<String, String> queryParams,
    Map<String, String> pathParams,
    String body,
    String userId,
    long durationMs,
    String exceptionClass,
    String message,
    String stackTrace
) {

    public String toJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        appendField(sb, "requestId", requestId); sb.append(',');
        appendField(sb, "method", method); sb.append(',');
        appendField(sb, "path", path); sb.append(',');
        appendField(sb, "userId", userId); sb.append(',');
        sb.append("\"durationMs\":").append(durationMs).append(',');
        appendField(sb, "exceptionClass", exceptionClass); sb.append(',');
        appendField(sb, "message", message); sb.append(',');
        appendField(sb, "body", body); sb.append(',');
        appendMap(sb, "headers", headers); sb.append(',');
        appendMap(sb, "queryParams", queryParams); sb.append(',');
        appendMap(sb, "pathParams", pathParams); sb.append(',');
        appendField(sb, "stackTrace", stackTrace);
        sb.append('}');
        return sb.toString();
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_id", requestId);
        row.put("method", method);
        row.put("path", path);
        row.put("user_id", userId);
        row.put("duration_ms", durationMs);
        row.put("exception_class", exceptionClass);
        row.put("message", message);
        row.put("stack_trace", stackTrace);
        row.put("body", body);
        return row;
    }

    public static String captureStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) { sb.append("null"); return; }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void appendMap(StringBuilder sb, String key, Map<String, String> map) {
        sb.append('"').append(key).append("\":");
        if (map == null || map.isEmpty()) { sb.append("{}"); return; }
        sb.append('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendField(sb, entry.getKey(), entry.getValue());
        }
        sb.append('}');
    }
}

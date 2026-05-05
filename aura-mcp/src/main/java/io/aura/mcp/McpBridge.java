package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class McpBridge {

    private final String baseUrl;
    private JSONArray routes;

    public McpBridge(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public void run() throws Exception {
        loadSchema();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JSONObject request = JSON.parseObject(line);
            JSONObject response = handle(request);
            if (response != null) {
                System.out.println(response.toJSONString());
                System.out.flush();
            }
        }
    }

    private JSONObject handle(JSONObject request) {
        String method = request.getString("method");
        Object id = request.get("id");

        Object result = switch (method) {
            case "initialize" -> handleInitialize();
            case "notifications/initialized" -> null;
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(request.getJSONObject("params"));
            default -> null;
        };

        if (id == null || result == null) return null;

        JSONObject resp = new JSONObject();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        return resp;
    }

    private Map<String, Object> handleInitialize() {
        return Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "aura-mcp-bridge", "version", "0.1.0")
        );
    }

    private Map<String, Object> handleToolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            JSONObject route = routes.getJSONObject(i);
            String httpMethod = route.getString("method");
            String path = route.getString("path");
            String desc = route.getString("description");

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", buildToolName(httpMethod, path));
            if (desc != null) tool.put("description", desc);
            tool.put("inputSchema", buildInputSchema(route.getJSONArray("params")));
            tools.add(tool);
        }
        return Map.of("tools", tools);
    }

    private Map<String, Object> handleToolsCall(JSONObject params) {
        String toolName = params.getString("name");
        JSONObject args = params.getJSONObject("arguments");

        for (int i = 0; i < routes.size(); i++) {
            JSONObject route = routes.getJSONObject(i);
            String name = buildToolName(route.getString("method"), route.getString("path"));
            if (!name.equals(toolName)) continue;

            try {
                String result = callHttp(route, args);
                return Map.of("content", List.of(Map.of("type", "text", "text", result)));
            } catch (Exception e) {
                return Map.of("isError", true,
                        "content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())));
            }
        }
        return Map.of("isError", true,
                "content", List.of(Map.of("type", "text", "text", "Tool not found: " + toolName)));
    }

    private String callHttp(JSONObject route, JSONObject args) throws Exception {
        String httpMethod = route.getString("method");
        String path = route.getString("path");
        JSONArray params = route.getJSONArray("params");

        // replace path params and build query string
        StringBuilder query = new StringBuilder();
        String body = null;

        if (params != null && args != null) {
            for (int i = 0; i < params.size(); i++) {
                JSONObject p = params.getJSONObject(i);
                String pName = p.getString("name");
                String source = p.getString("source");
                Object val = args.get(pName);
                if (val == null) continue;

                if ("path".equals(source)) {
                    path = path.replace("{" + pName + "}", val.toString());
                } else if ("query".equals(source)) {
                    query.append(query.isEmpty() ? "?" : "&").append(pName).append("=").append(val);
                } else if ("body".equals(source)) {
                    body = args.toJSONString();
                }
            }
        }

        String url = baseUrl + path + query;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(httpMethod);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }

        try (InputStream is = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            return is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
        }
    }

    private void loadSchema() throws Exception {
        String url = baseUrl + "/__schema__";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JSONObject schema = JSON.parseObject(json);
        this.routes = schema.getJSONArray("routes");
    }

    private static String buildToolName(String method, String path) {
        String name = path.replaceAll("^/", "")
                .replaceAll("\\{[a-zA-Z_]+}", "by_id")
                .replaceAll("/+", "_");
        if (name.isEmpty()) name = "root";
        return method.toLowerCase() + "_" + name;
    }

    private static Map<String, Object> buildInputSchema(JSONArray params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                JSONObject p = params.getJSONObject(i);
                String name = p.getString("name");
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", jsonType(p.getString("type")));
                if (p.getString("description") != null) prop.put("description", p.getString("description"));
                properties.put(name, prop);
                required.add(name);
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static String jsonType(String javaType) {
        if (javaType == null) return "string";
        return switch (javaType) {
            case "int", "long", "Integer", "Long" -> "integer";
            case "double", "float" -> "number";
            case "boolean", "Boolean" -> "boolean";
            default -> "string";
        };
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java -jar aura-mcp-bridge.jar <base-url>");
            System.err.println("Example: java -jar aura-mcp-bridge.jar http://localhost:8080");
            System.exit(1);
        }
        new McpBridge(args[0]).run();
    }
}

package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class McpBridge {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final String baseUrl;
    private final PrintStream out;
    private final String schemaToken;
    private JSONArray routes;

    public McpBridge(String baseUrl) {
        this(baseUrl, System.out, configuredSchemaToken());
    }

    public McpBridge(String baseUrl, PrintStream out) {
        this(baseUrl, out, configuredSchemaToken());
    }

    public McpBridge(String baseUrl, PrintStream out, String schemaToken) {
        URI parsed = URI.create(Objects.requireNonNull(baseUrl, "baseUrl"));
        if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL without userinfo");
        }
        String normalized = parsed.toString();
        this.baseUrl = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        this.out = out;
        this.schemaToken = schemaToken == null || schemaToken.isBlank() ? null : schemaToken;
    }

    public void run() throws Exception {
        loadSchema();
        McpProtocol protocol = new McpProtocol(out, "aura-mcp-bridge", (method, params) -> switch (method) {
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(params);
            default -> null;
        });
        protocol.runStdio();
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
                    path = path.replace("{" + pName + "}", encode(val.toString()));
                } else if ("query".equals(source)) {
                    query.append(query.isEmpty() ? "?" : "&")
                            .append(encode(pName)).append("=").append(encode(val.toString()));
                } else if ("body".equals(source)) {
                    body = args.toJSONString();
                }
            }
        }

        String url = baseUrl + path + query;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(httpMethod);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        try (InputStream is = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            return is != null ? readBounded(is, MAX_RESPONSE_BYTES) : "";
        }
    }

    private void loadSchema() throws Exception {
        String url = baseUrl + "/__schema__";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (schemaToken != null) conn.setRequestProperty("X-Aura-Schema-Token", schemaToken);
        try (InputStream is = conn.getInputStream()) {
            String json = readBounded(is, MAX_RESPONSE_BYTES);
            JSONObject schema = JSON.parseObject(json);
            this.routes = schema.getJSONArray("routes");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String configuredSchemaToken() {
        String value = System.getProperty("aura.schema.auth-token");
        if (value == null) value = System.getProperty("aura.schema-token");
        if (value == null) value = System.getenv("AURA_SCHEMA_TOKEN");
        return value;
    }

    private static String readBounded(InputStream input, int maxBytes) throws IOException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new IOException("HTTP response exceeds maximum size");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String buildToolName(String method, String path) {
        return McpUtil.buildToolName(method, path);
    }

    private static Map<String, Object> buildInputSchema(JSONArray params) {
        List<McpUtil.ParamInfo> list = new ArrayList<>();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                JSONObject p = params.getJSONObject(i);
                list.add(new McpUtil.ParamInfo(p.getString("name"), p.getString("type"), p.getString("description")));
            }
        }
        return McpUtil.buildInputSchema(list);
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

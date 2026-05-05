package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.aura.Aura;
import io.aura.web.Handler;
import io.aura.web.Router;

import java.util.*;

public class McpExporter {

    private final Aura app;
    private List<McpTool> tools;

    public McpExporter(Aura app) {
        this.app = app;
    }

    public List<McpTool> export(String schemaJson) {
        JSONObject schema = JSON.parseObject(schemaJson);
        JSONArray routes = schema.getJSONArray("routes");
        List<McpTool> result = new ArrayList<>();

        for (int i = 0; i < routes.size(); i++) {
            JSONObject route = routes.getJSONObject(i);
            String method = route.getString("method");
            String path = route.getString("path");
            String desc = route.getString("description");

            String toolName = buildToolName(method, path);
            List<McpTool.Param> params = parseParams(route.getJSONArray("params"));
            Map<String, Object> inputSchema = buildInputSchema(params);

            result.add(new McpTool(toolName, desc, method, path, inputSchema));
        }
        this.tools = result;
        return result;
    }

    public Handler mcpListHandler() {
        return ctx -> {
            if (tools == null) throw new IllegalStateException("Call export() first");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("result", Map.of("tools", tools.stream().map(t -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("name", t.name());
                m.put("description", t.description());
                m.put("inputSchema", t.inputSchema());
                return m;
            }).toList()));
            ctx.json(response);
        };
    }

    public Handler mcpCallHandler() {
        return ctx -> {
            if (tools == null) throw new IllegalStateException("Call export() first");
            JSONObject body = JSON.parseObject(ctx.body(String.class));
            String toolName = body.getJSONObject("params").getString("name");
            JSONObject args = body.getJSONObject("params").getJSONObject("arguments");

            McpTool tool = tools.stream()
                    .filter(t -> t.name().equals(toolName))
                    .findFirst()
                    .orElse(null);

            if (tool == null) {
                ctx.status(404).json(Map.of("error", "Tool not found: " + toolName));
                return;
            }

            String url = tool.path();
            if (args != null) {
                for (String key : args.keySet()) {
                    url = url.replace("{" + key + "}", args.getString(key));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tool", tool.name());
            result.put("method", tool.method());
            result.put("url", url);
            result.put("description", tool.description());
            result.put("inputSchema", tool.inputSchema());
            if (args != null) result.put("arguments", args);
            ctx.json(Map.of("jsonrpc", "2.0", "result", result));
        };
    }

    public void registerRoutes(Router router) {
        router.get("/__mcp__/tools", mcpListHandler());
        router.post("/__mcp__/call", mcpCallHandler());
    }

    private static String buildToolName(String method, String path) {
        String name = path.replaceAll("^/", "")
                .replaceAll("\\{[a-zA-Z_]+}", "")
                .replaceAll("/+", "_")
                .replaceAll("_+$", "");
        if (name.isEmpty()) name = "root";
        return method.toLowerCase() + "_" + name;
    }

    private static List<McpTool.Param> parseParams(JSONArray arr) {
        if (arr == null) return List.of();
        List<McpTool.Param> params = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject p = arr.getJSONObject(i);
            params.add(new McpTool.Param(
                    p.getString("name"),
                    p.getString("type"),
                    p.getString("source"),
                    p.getString("description")
            ));
        }
        return params;
    }

    private static Map<String, Object> buildInputSchema(List<McpTool.Param> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (McpTool.Param p : params) {
            properties.put(p.name(), p.toSchemaProperty());
            required.add(p.name());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}

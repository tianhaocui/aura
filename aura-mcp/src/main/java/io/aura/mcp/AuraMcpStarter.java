package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.Aura;
import io.aura.McpStarter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuraMcpStarter implements McpStarter {

    private static final Logger log = LoggerFactory.getLogger(AuraMcpStarter.class);

    @Override
    public void start(Aura app) {
        // SSE mode removed — use --mcp-stdio or npm bridge instead
    }

    @Override
    public void startStdio(Aura app) {
        try {
            PrintStream out = app.mcpStdout() != null ? app.mcpStdout() : System.out;
            if (app.mcpRouter() instanceof McpRouter mcpRouter) {
                new McpRouterBridge(mcpRouter, out).run();
            } else {
                new McpBridge("http://localhost:" + app.port(), out).run();
            }
        } catch (Exception e) {
            throw new RuntimeException("MCP stdio failed", e);
        }
    }

    @Override
    public void stop() {}

    private static class McpRouterBridge {
        private final McpRouter router;
        private final PrintStream out;

        McpRouterBridge(McpRouter router, PrintStream out) {
            this.router = router;
            this.out = out;
        }

        void run() throws Exception {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JSONObject request = JSON.parseObject(line);
                    JSONObject response = handle(request);
                    if (response != null) {
                        out.println(response.toJSONString());
                        out.flush();
                    }
                } catch (Exception e) {
                    JSONObject err = new JSONObject();
                    err.put("jsonrpc", "2.0");
                    err.put("id", (Object) null);
                    err.put("error", Map.of("code", -32700, "message", "Parse error: " + e.getMessage()));
                    out.println(err.toJSONString());
                    out.flush();
                }
            }
            log.info("MCP stdio stream closed");
        }

        private JSONObject handle(JSONObject request) {
            String method = request.getString("method");
            Object id = request.get("id");

            if (method == null) return null;

            Object result = switch (method) {
                case "initialize" -> Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "aura-mcp", "version", "0.1.0")
                );
                case "notifications/initialized" -> null;
                case "tools/list" -> router.buildSchema();
                case "tools/call" -> handleCall(request.getJSONObject("params"));
                default -> null;
            };

            if (id == null || result == null) return null;
            JSONObject resp = new JSONObject();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);
            resp.put("result", result);
            return resp;
        }

        private Map<String, Object> handleCall(JSONObject params) {
            String toolName = params.getString("name");
            JSONObject args = params.getJSONObject("arguments");
            Map<String, Object> argsMap = args != null ? args.toJavaObject(Map.class) : Map.of();
            try {
                Object result = router.invoke(toolName, argsMap);
                String text = result instanceof String s ? s : JSON.toJSONString(result);
                return Map.of("content", List.of(Map.of("type", "text", "text", text)));
            } catch (Exception e) {
                return Map.of("isError", true,
                    "content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())));
            }
        }
    }
}

package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.Aura;
import io.aura.McpStarter;

import java.io.*;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuraMcpStarter implements McpStarter {

    private static final Logger log = LoggerFactory.getLogger(AuraMcpStarter.class);
    private McpHttpTransport httpTransport;

    @Override
    public void start(Aura app) {
    }

    @Override
    public void startStdio(Aura app) {
        try {
            PrintStream out = app.mcpStdout() != null ? app.mcpStdout() : System.out;
            if (app.mcpRouter() instanceof McpRouter mcpRouter) {
                McpProtocol protocol = new McpProtocol(out, "aura-mcp", (method, params) -> switch (method) {
                    case "tools/list" -> mcpRouter.buildSchema();
                    case "tools/call" -> handleRouterCall(mcpRouter, params);
                    default -> null;
                });
                protocol.runStdio();
            } else {
                new McpBridge("http://localhost:" + app.port(), out).run();
            }
        } catch (Exception e) {
            throw new RuntimeException("MCP stdio failed", e);
        }
    }

    @Override
    public Object httpHandler(Aura app) {
        if (!(app.mcpRouter() instanceof McpRouter mcpRouter)) {
            log.warn("MCP HTTP transport requires McpRouter. Use app.mcp(new McpRouter()) first.");
            return null;
        }
        String serverName = app.prop("app.name") != null ? app.prop("app.name") : "aura-mcp";
        httpTransport = new McpHttpTransport(mcpRouter, serverName);
        String path = app.mcpPath();
        app.post(path, httpTransport.postHandler());
        app.get(path, httpTransport.sseHandler());
        log.info("[Aura] MCP Server:");
        log.info("  Transport: HTTP+SSE at {}", path);
        log.info("  Tools: {} registered", httpTransport.toolCount());
        return httpTransport;
    }

    @Override
    public void stop() {
        if (httpTransport != null) {
            httpTransport.shutdown();
            httpTransport = null;
        }
    }

    private static Map<String, Object> handleRouterCall(McpRouter router, JSONObject params) {
        String toolName = params.getString("name");
        JSONObject args = params.getJSONObject("arguments");
        @SuppressWarnings("unchecked")
        Map<String, Object> argsMap = args != null ? (Map<String, Object>) args.toJavaObject(Map.class) : Map.of();
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

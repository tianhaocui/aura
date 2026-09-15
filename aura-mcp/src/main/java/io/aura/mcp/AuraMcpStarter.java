package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.AuraMcpRuntime;
import io.aura.McpStarter;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuraMcpStarter implements McpStarter {

    private static final Logger log = LoggerFactory.getLogger(AuraMcpStarter.class);

    @Override
    public void start(AuraMcpRuntime app) {
    }

    @Override
    public void startStdio(AuraMcpRuntime app) {
        try {
            PrintStream out = app.mcpStdout() != null ? app.mcpStdout() : System.out;
            if (app.mcpRouter() instanceof McpRouter mcpRouter) {
                McpProtocol protocol = new McpProtocol(out, "aura-mcp", (method, params) -> switch (method) {
                        case "tools/list" -> mcpRouter.buildSchema();
                        case "tools/call" -> handleRouterCall(app, mcpRouter, params);
                        case "ping" -> Map.of();
                        case "resources/list" -> mcpRouter.listResources();
                        case "resources/read" -> mcpRouter.readResource(params.getString("uri"));
                        case "prompts/list" -> mcpRouter.listPrompts();
                        case "prompts/get" -> {
                            JSONObject arguments = params.getJSONObject("arguments");
                            yield mcpRouter.getPrompt(params.getString("name"), arguments == null
                                    ? Map.of() : arguments.toJavaObject(Map.class));
                        }
                    default -> null;
                });
                protocol.runStdio();
            } else {
                new McpBridge("http://localhost:" + app.port(), out,
                        null, true).run();
            }
        } catch (Exception e) {
            throw new RuntimeException("MCP stdio failed", e);
        }
    }

    @Override
    public void stop() {}

    private static Map<String, Object> handleRouterCall(AuraMcpRuntime app, McpRouter router, JSONObject params) {
        String toolName = params.getString("name");
        JSONObject args = params.getJSONObject("arguments");
        JSONObject metadata = params.getJSONObject("_meta");
        Map<String, String> transportMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            for (String key : metadata.keySet()) {
                Object value = metadata.get(key);
                if (value != null) transportMetadata.put(key, value.toString());
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> argsMap = args != null ? (Map<String, Object>) args.toJavaObject(Map.class) : Map.of();
        try {
            String userId = app.mcpAuthenticator() == null
                    ? null : app.mcpAuthenticator().authenticate(Map.copyOf(transportMetadata));
            io.aura.contract.McpAuthorizer authorizer = app.mcpAuthorizer();
            Object result = router.invoke(toolName, argsMap, userId,
                    authorizer == null ? null : authorizer::authorize);
            return McpContent.result(result);
        } catch (Exception e) {
            log.warn("MCP tool invocation failed for {}", toolName, e);
            return McpContent.error("MCP_TOOL_ERROR");
        }
    }
}

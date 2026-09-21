package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.AuraMcpRuntime;
import io.aura.McpStarter;
import io.aura.contract.McpRouterSpec;

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
            McpRouterSpec mcpRouter = app.mcpRouter();
            if (mcpRouter != null) {
                McpProtocol protocol = new McpProtocol(out, "aura-mcp", handler(app, mcpRouter));
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

    static McpProtocol.RequestHandler handler(AuraMcpRuntime app, McpRouterSpec router) {
        return (method, rawParams) -> {
            JSONObject params = rawParams == null ? new JSONObject() : rawParams;
            return switch (method) {
            case "tools/list" -> router.buildSchema();
            case "tools/call" -> {
                requireText(params, "name");
                requireObjectOrAbsent(params, "arguments");
                yield handleRouterCall(app, router, params);
            }
            case "ping" -> Map.of();
            case "resources/list" -> router.listResources();
            case "resources/read" -> {
                requireText(params, "uri");
                yield router.readResource(params.getString("uri"));
            }
            case "prompts/list" -> router.listPrompts();
            case "prompts/get" -> {
                requireText(params, "name");
                requireObjectOrAbsent(params, "arguments");
                JSONObject arguments = params.getJSONObject("arguments");
                yield router.getPrompt(params.getString("name"), arguments == null
                        ? Map.of() : arguments.toJavaObject(Map.class));
            }
            default -> null;
            };
        };
    }

    private static void requireText(JSONObject params, String name) {
        Object value = params.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new McpProtocol.RequestException(-32602, "Invalid params");
        }
    }

    private static void requireObjectOrAbsent(JSONObject params, String name) {
        Object value = params.get(name);
        if (value != null && !(value instanceof JSONObject) && !(value instanceof Map<?, ?>)) {
            throw new McpProtocol.RequestException(-32602, "Invalid params");
        }
    }

    private static Map<String, Object> handleRouterCall(AuraMcpRuntime app, McpRouterSpec router, JSONObject params) {
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
        String userId;
        try {
            userId = app.mcpAuthenticator() == null
                    ? null : app.mcpAuthenticator().authenticate(Map.copyOf(transportMetadata));
        } catch (RuntimeException authenticationFailure) {
            log.warn("MCP stdio authentication failed", authenticationFailure);
            return McpContent.error("MCP_AUTHENTICATION_REQUIRED");
        }
        if (app.mcpAuthenticator() != null && userId == null) {
            return McpContent.error("MCP_AUTHENTICATION_REQUIRED");
        }
        try {
            io.aura.contract.McpAuthorizer authorizer = app.mcpAuthorizer();
            Object result = router.invoke(toolName, argsMap, userId,
                    authorizer == null ? null : authorizer::authorize);
            return McpContent.result(result);
        } catch (SecurityException denied) {
            log.warn("MCP tool permission denied for {}", toolName);
            return McpContent.error("MCP_PERMISSION_DENIED");
        } catch (Exception e) {
            log.warn("MCP tool invocation failed for {}", toolName, e);
            return McpContent.error("MCP_TOOL_ERROR");
        }
    }
}

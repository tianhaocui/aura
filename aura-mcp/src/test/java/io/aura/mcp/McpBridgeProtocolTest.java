package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpBridgeProtocolTest {

    private McpRouter router;

    @BeforeEach
    void setup() {
        router = new McpRouter();
        router.tool("get_user", new UserService(), "get", "获取用户");
        router.tool("greet", "打招呼")
              .param("name", String.class, "名字")
              .handler(ctx -> "hello " + ctx.getString("name"));
    }

    static class UserService {
        public String get(int id) { return "user-" + id; }
    }

    @Test
    void initialize_returnsProtocolVersion() throws Exception {
        String response = sendRequest("initialize", 1, null);
        JSONObject result = parseResult(response);
        assertThat(result.getString("protocolVersion")).isEqualTo("2025-03-26");
        assertThat(result.getJSONObject("capabilities")).containsKey("tools");
        assertThat(result.getJSONObject("serverInfo").getString("name")).isEqualTo("aura-mcp");
        assertThat(result.getJSONObject("serverInfo").getString("version")).isEqualTo("1.0.0");
    }

    @Test
    void toolsList_returnsRegisteredTools() throws Exception {
        String response = sendRequest("tools/list", 2, null);
        JSONObject result = parseResult(response);
        var tools = result.getJSONArray("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools.getJSONObject(0).getString("name")).isEqualTo("get_user");
        assertThat(tools.getJSONObject(1).getString("name")).isEqualTo("greet");
    }

    @Test
    void toolsCall_invokesCorrectly() throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", "get_user");
        params.put("arguments", Map.of("id", "7"));

        String response = sendRequest("tools/call", 3, params);
        JSONObject result = parseResult(response);
        var content = result.getJSONArray("content");
        assertThat(content.getJSONObject(0).getString("text")).isEqualTo("user-7");
    }

    @Test
    void toolsCall_unknownTool_returnsError() throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", "nonexistent");
        params.put("arguments", Map.of());

        String response = sendRequest("tools/call", 4, params);
        JSONObject result = parseResult(response);
        assertThat(result.getBoolean("isError")).isTrue();
        assertThat(result.getString("code")).isEqualTo("MCP_TOOL_ERROR");
        assertThat(result.getJSONArray("content").getJSONObject(0).getString("text"))
                .doesNotContain("nonexistent");
    }

    @Test
    void nullMethodReturnsInvalidRequestError() throws Exception {
        JSONObject response = JSON.parseObject(sendRaw("{\"jsonrpc\":\"2.0\",\"id\":5}"));
        assertThat(response.getJSONObject("error").getInteger("code")).isEqualTo(-32600);
    }

    @Test
    void notification_noId_returnsNothing() throws Exception {
        String response = sendRequest("notifications/initialized", null, null);
        assertThat(response).isEmpty();
    }

    @Test
    void protocolExtensions_areDeterministic() throws Exception {
        assertThat(parseResult(sendRequest("ping", 5, null))).isEmpty();
        assertThat(parseResult(sendRequest("resources/list", 6, null)).getJSONArray("resources")).isEmpty();
        assertThat(parseResult(sendRequest("resources/read", 7, new JSONObject())).getJSONArray("contents")).isEmpty();
        assertThat(parseResult(sendRequest("prompts/list", 8, null)).getJSONArray("prompts")).isEmpty();
        assertThat(parseResult(sendRequest("prompts/get", 9, new JSONObject())).getJSONArray("messages")).isEmpty();
    }

    @Test
    void unknownMethodReturnsJsonRpcMethodNotFoundError() throws Exception {
        JSONObject response = JSON.parseObject(sendRequest("unknown/method", 11, null));
        assertThat(response.getJSONObject("error").getInteger("code")).isEqualTo(-32601);
        assertThat(response.getJSONObject("error").getString("message")).contains("unknown/method");
    }

    @Test
    void invalidParamsReturnsJsonRpcInvalidParamsErrorAndNotificationsStaySilent() throws Exception {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 12);
        request.put("method", "tools/list");
        request.put("params", "not-an-object");

        JSONObject response = JSON.parseObject(sendRaw(request.toJSONString()));
        assertThat(response.getJSONObject("error").getInteger("code")).isEqualTo(-32602);

        request.remove("id");
        assertThat(sendRaw(request.toJSONString())).isBlank();
    }

    private String sendRequest(String method, Object id, JSONObject params) throws Exception {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        if (method != null) request.put("method", method);
        if (id != null) request.put("id", id);
        if (params != null) request.put("params", params);
        return sendRaw(request.toJSONString());
    }

    private String sendRaw(String jsonLine) throws Exception {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream((jsonLine + "\n").getBytes(StandardCharsets.UTF_8)));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8);

            McpProtocol protocol = new McpProtocol(out, "aura-mcp", (method, params) -> switch (method) {
                case "ping" -> Map.of();
                case "resources/list" -> Map.of("resources", List.of());
                case "resources/read" -> Map.of("contents", List.of());
                case "prompts/list" -> Map.of("prompts", List.of());
                case "prompts/get" -> Map.of("messages", List.of());
                case "tools/list" -> router.buildSchema();
                case "tools/call" -> {
                    String toolName = params.getString("name");
                    JSONObject args = params.getJSONObject("arguments");
                    Map<String, Object> argsMap = args != null ? args.toJavaObject(Map.class) : Map.of();
                    try {
                        Object result = router.invoke(toolName, argsMap);
                        String text = result instanceof String s ? s : JSON.toJSONString(result);
                        yield Map.of("content", List.of(Map.of("type", "text", "text", text)));
                    } catch (Exception e) {
                        yield McpContent.error("MCP_TOOL_ERROR");
                    }
                }
                default -> null;
            });
            protocol.runStdio();

            return baos.toString(StandardCharsets.UTF_8).trim();
        } finally {
            System.setIn(originalIn);
        }
    }

    private JSONObject parseResult(String response) {
        assertThat(response).isNotEmpty();
        JSONObject resp = JSON.parseObject(response);
        assertThat(resp.getString("jsonrpc")).isEqualTo("2.0");
        return resp.getJSONObject("result") != null ? resp.getJSONObject("result") : resp;
    }
}

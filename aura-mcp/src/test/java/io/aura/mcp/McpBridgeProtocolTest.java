package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.Aura;
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
        assertThat(result.getString("protocolVersion")).isEqualTo("2024-11-05");
        assertThat(result.getJSONObject("capabilities")).containsKey("tools");
        assertThat(result.getJSONObject("serverInfo").getString("name")).isEqualTo("aura-mcp");
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
        assertThat(result.getJSONArray("content").getJSONObject(0).getString("text"))
                .contains("nonexistent");
    }

    @Test
    void nullMethod_returnsNothing() throws Exception {
        String response = sendRaw("{\"jsonrpc\":\"2.0\",\"id\":5}");
        assertThat(response).isEmpty();
    }

    @Test
    void notification_noId_returnsNothing() throws Exception {
        String response = sendRequest("notifications/initialized", null, null);
        assertThat(response).isEmpty();
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

            Aura app = Aura.create().port(19999);
            // Use reflection to access the private McpRouterBridge
            var bridgeClass = Class.forName("io.aura.mcp.AuraMcpStarter$McpRouterBridge");
            var constructor = bridgeClass.getDeclaredConstructor(McpRouter.class, PrintStream.class);
            constructor.setAccessible(true);
            Object bridge = constructor.newInstance(router, out);
            var runMethod = bridgeClass.getDeclaredMethod("run");
            runMethod.setAccessible(true);
            runMethod.invoke(bridge);

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

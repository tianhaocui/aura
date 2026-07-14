package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.Aura;
import io.aura.web.TestClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpHttpTransportTest {

    static class CalcService {
        public int add(int a, int b) { return a + b; }
        public String greet(String name) { return "Hello, " + name; }
    }

    private Aura buildApp() {
        McpRouter router = new McpRouter();
        CalcService svc = new CalcService();
        router.tool("add", svc, "add", "Add two numbers");
        router.tool("greet", svc, "greet", "Greet someone");

        return Aura.create().env("dev")
                .mcp(router)
                .mcp("/mcp");
    }

    @Test
    void post_toolsList_returnsTools() {
        var app = buildApp();
        var client = TestClient.of(app);

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/list");

        var resp = client.post("/mcp").body(request.toJSONString()).execute();
        assertThat(resp.status()).isEqualTo(200);

        JSONObject body = JSON.parseObject(resp.body());
        assertThat(body.getString("jsonrpc")).isEqualTo("2.0");
        assertThat(body.getInteger("id")).isEqualTo(1);
        assertThat(body.getJSONObject("result").getJSONArray("tools")).hasSize(2);
    }

    @Test
    void post_toolsCall_invokesTool() {
        var app = buildApp();
        var client = TestClient.of(app);

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", "tools/call");
        request.put("params", Map.of("name", "add", "arguments", Map.of("a", 3, "b", 4)));

        var resp = client.post("/mcp").body(request.toJSONString()).execute();
        assertThat(resp.status()).isEqualTo(200);

        JSONObject body = JSON.parseObject(resp.body());
        assertThat(body.getInteger("id")).isEqualTo(2);
        var content = body.getJSONObject("result").getJSONArray("content");
        assertThat(content.getJSONObject(0).getString("text")).isEqualTo("7");
    }

    @Test
    void post_toolsCall_greet() {
        var app = buildApp();
        var client = TestClient.of(app);

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 3);
        request.put("method", "tools/call");
        request.put("params", Map.of("name", "greet", "arguments", Map.of("name", "World")));

        var resp = client.post("/mcp").body(request.toJSONString()).execute();
        assertThat(resp.status()).isEqualTo(200);

        JSONObject body = JSON.parseObject(resp.body());
        var text = body.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text");
        assertThat(text).isEqualTo("Hello, World");
    }

    @Test
    void post_initialize_returnsServerInfo() {
        var app = buildApp();
        var client = TestClient.of(app);

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 0);
        request.put("method", "initialize");

        var resp = client.post("/mcp").body(request.toJSONString()).execute();
        assertThat(resp.status()).isEqualTo(200);

        JSONObject body = JSON.parseObject(resp.body());
        assertThat(body.getJSONObject("result").getString("protocolVersion")).isEqualTo("2025-03-26");
        assertThat(body.getJSONObject("result").getJSONObject("serverInfo")).isNotNull();
    }

    @Test
    void post_unknownTool_returnsError() {
        var app = buildApp();
        var client = TestClient.of(app);

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", 4);
        request.put("method", "tools/call");
        request.put("params", Map.of("name", "nonexistent", "arguments", Map.of()));

        var resp = client.post("/mcp").body(request.toJSONString()).execute();
        assertThat(resp.status()).isEqualTo(200);

        JSONObject body = JSON.parseObject(resp.body());
        var result = body.getJSONObject("result");
        assertThat(result.getBoolean("isError")).isTrue();
        assertThat(result.getJSONArray("content").getJSONObject(0).getString("text")).contains("Tool not found");
    }

    @Test
    void post_emptyBody_returns400() {
        var app = buildApp();
        var client = TestClient.of(app);

        var resp = client.post("/mcp").execute();
        assertThat(resp.status()).isEqualTo(400);
    }

    @Test
    void post_invalidJson_returnsError() {
        var app = buildApp();
        var client = TestClient.of(app);

        var resp = client.post("/mcp")
                .header("Content-Type", "application/json")
                .body("not valid json {{{")
                .execute();
        // Invalid JSON body results in error (400 or 500 depending on parse layer)
        assertThat(resp.status()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void post_notification_returns202() {
        var app = buildApp();
        var client = TestClient.of(app);

        JSONObject request = new JSONObject();
        request.put("method", "notifications/initialized");
        // No id = notification

        var resp = client.post("/mcp").body(request.toJSONString()).execute();
        assertThat(resp.status()).isEqualTo(202);
    }

    @Test
    void mcp_withoutRouter_post404() {
        var app = Aura.create().env("dev").mcp("/mcp");
        var client = TestClient.of(app);

        var resp = client.post("/mcp").body("{}").execute();
        // No McpRouter set, so no routes registered for /mcp
        assertThat(resp.status()).isEqualTo(404);
    }

    @Test
    void backwardCompat_mcpBoolean_unchanged() {
        // Verify mcp(true) still sets mcpPort, not mcpPath
        var app = Aura.create().mcp(true);
        assertThat(app.mcpPath()).isNull();
        assertThat(app.mcpPort()).isEqualTo(0);
    }

    @Test
    void backwardCompat_mcpInt_unchanged() {
        var app = Aura.create().mcp(8081);
        assertThat(app.mcpPath()).isNull();
        assertThat(app.mcpPort()).isEqualTo(8081);
    }
}

package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.AuraApp;
import io.aura.web.TestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpHttpTransportTest {
    private McpModule module;
    private TestClient client;

    @BeforeEach
    void setUp() {
        McpRouter router = new McpRouter();
        router.tool("whoami", "Return identity").handler(ctx -> ctx.userId());
        router.tool("delete_order", "Delete order")
                .permission("orders.delete")
                .handler(ctx -> "deleted");
        module = McpModule.create(router).http("/mcp", 2, Duration.ofMinutes(5));
        AuraApp app = AuraApp.create()
                .mcpAuthenticate(metadata -> {
                    String authorization = metadata.get("authorization");
                    if ("Bearer alice".equals(authorization)) return "alice";
                    if ("Bearer bob".equals(authorization)) return "bob";
                    return null;
                })
                .mcpAuthorize((user, permission) ->
                        "alice".equals(user) && "orders.delete".equals(permission))
                .use(module);
        client = TestClient.of(app);
    }

    @Test
    void initializeRequiresAuthenticationAndNegotiatesVersion() {
        TestClient.Response anonymous = client.post("/mcp").body(initialize(1)).expect(401);
        assertThat(errorCode(anonymous)).isEqualTo(-32001);
        assertThat(module.sessions().size()).isZero();

        Map<String, Object> unsupported = initialize(2);
        ((Map<String, Object>) unsupported.get("params")).put("protocolVersion", "1900-01-01");
        TestClient.Response rejected = client.post("/mcp")
                .header("Authorization", "Bearer alice").body(unsupported).expect(400);
        assertThat(errorCode(rejected)).isEqualTo(-32602);
        assertThat(module.sessions().size()).isZero();
    }

    @Test
    void sessionMustFinishTheInitializedHandshakeBeforeBusinessRequests() {
        TestClient.Response initialized = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .body(initialize(1)).expect(200);
        String session = initialized.header(McpHttpTransport.SESSION_HEADER);
        McpSessionManager.Session negotiating = module.sessions().inspect(session, "alice").orElseThrow();
        assertThat(negotiating.protocolVersion()).isEqualTo(McpHttpTransport.PROTOCOL_VERSION);
        assertThat(negotiating.phase()).isEqualTo(McpSessionManager.SessionPhase.NEGOTIATING);

        request("Bearer alice", session, 2, "ping", Map.of()).expect(200);
        TestClient.Response blocked = request(
                "Bearer alice", session, 3, "tools/list", Map.of()).expect(409);
        assertThat(errorCode(blocked)).isEqualTo(-32007);

        TestClient.Response correlatedNotification = request(
                "Bearer alice", session, 4, "notifications/initialized", Map.of()).expect(400);
        assertThat(errorCode(correlatedNotification)).isEqualTo(-32600);
        assertThat(module.sessions().inspect(session, "alice").orElseThrow().phase())
                .isEqualTo(McpSessionManager.SessionPhase.NEGOTIATING);

        client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                        "params", Map.of()))
                .expect(202);
        assertThat(module.sessions().inspect(session, "alice").orElseThrow().phase())
                .isEqualTo(McpSessionManager.SessionPhase.READY);

        TestClient.Response duplicate = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                        "params", Map.of()))
                .execute();
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body()).isEmpty();
        request("Bearer alice", session, 5, "tools/list", Map.of()).expect(200);
    }

    @Test
    void explicitProtocolVersionMismatchFailsBeforeSessionTouch() {
        String session = initialize("Bearer alice", 1).header(McpHttpTransport.SESSION_HEADER);

        client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .body(Map.of("jsonrpc", "2.0", "id", 2, "method", "ping", "params", Map.of()))
                .expect(200);
        McpSessionManager.Session before = module.sessions().inspect(session, "alice").orElseThrow();

        TestClient.Response wrongPost = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, "1900-01-01")
                .body(Map.of("jsonrpc", "2.0", "id", 3, "method", "ping", "params", Map.of()))
                .expect(400);
        assertThat(errorCode(wrongPost)).isEqualTo(-32008);
        assertThat(module.sessions().inspect(session, "alice").orElseThrow().lastAccessedAt())
                .isEqualTo(before.lastAccessedAt());

        client.delete("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, "1900-01-01")
                .expect(400);
        assertThat(module.sessions().inspect(session, "alice")).isPresent();

        McpModule sseModule = McpModule.create(new McpRouter()).http().sse();
        TestClient sseClient = TestClient.of(AuraApp.create()
                .mcpAuthenticate(metadata -> "alice")
                .use(sseModule));
        String sseSession = sseClient.post("/mcp")
                .header("Authorization", "Bearer alice")
                .body(initialize(10)).expect(200).header(McpHttpTransport.SESSION_HEADER);
        sseClient.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, sseSession)
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                        "params", Map.of()))
                .expect(202);
        sseClient.get("/mcp")
                .header("Authorization", "Bearer alice")
                .header("Accept", "text/event-stream")
                .header(McpHttpTransport.SESSION_HEADER, sseSession)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, "1900-01-01")
                .expect(400);
    }

    @Test
    void postRejectsUnsupportedAcceptAndMissingSessionUsesBadRequest() {
        TestClient.Response unsupported = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header("Accept", "text/html")
                .body(initialize(1)).expect(406);
        assertThat(errorCode(unsupported)).isEqualTo(-32005);

        TestClient.Response missingSession = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header("Accept", "application/json, text/event-stream")
                .body(Map.of("jsonrpc", "2.0", "id", 2, "method", "ping",
                        "params", Map.of())).expect(400);
        assertThat(errorCode(missingSession)).isEqualTo(-32002);
    }

    @Test
    void postRequiresJsonContentTypeAndAllowsCharset() {
        TestClient.Response text = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header("Content-Type", "text/plain")
                .body(JSON.toJSONString(initialize(1))).expect(415);
        assertThat(errorCode(text)).isEqualTo(-32006);

        TestClient.Response json = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(initialize(2)).expect(200);
        assertThat(json.header(McpHttpTransport.SESSION_HEADER)).isNotBlank();
    }

    @Test
    void realUndertowSocketCarriesAuthenticatedMcpSession() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        McpRouter router = new McpRouter();
        router.tool("ping_tool", "Ping").handler(ctx -> "pong");
        AuraApp app = AuraApp.create(new io.aura.AuraConfig().port(port))
                .mcpAuthenticate(metadata -> "alice")
                .use(McpModule.create(router).http());
        HttpClient http = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
        try {
            app.start();
            HttpResponse<String> missingContentType = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Authorization", "Bearer alice")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(initialize(0))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(missingContentType.statusCode()).isEqualTo(415);
            assertThat(JSON.parseObject(missingContentType.body()).getJSONObject("error")
                    .getInteger("code")).isEqualTo(-32006);

            HttpResponse<String> initialized = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Authorization", "Bearer alice")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(initialize(1))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(initialized.statusCode()).isEqualTo(200);
            String session = initialized.headers()
                    .firstValue(McpHttpTransport.SESSION_HEADER).orElseThrow();
            HttpResponse<String> ready = http.send(initializedNotification(endpoint, session),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ready.statusCode()).isEqualTo(202);

            HttpResponse<String> listed = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Authorization", "Bearer alice")
                            .header(McpHttpTransport.SESSION_HEADER, session)
                            .header(McpHttpTransport.PROTOCOL_VERSION_HEADER,
                                    McpHttpTransport.PROTOCOL_VERSION)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(
                                    Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list",
                                            "params", Map.of()))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(listed.statusCode()).isEqualTo(200);
            assertThat(JSON.parseObject(listed.body()).getJSONObject("result")
                    .getJSONArray("tools").getJSONObject(0).getString("name"))
                    .isEqualTo("ping_tool");
        } finally {
            app.stop();
        }
    }

    @Test
    void realUndertowSocketHandlesJsonRpcBatchesAndNotificationOnlyBatches() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        McpRouter router = new McpRouter();
        router.tool("ping_tool", "Ping").handler(ctx -> "pong");
        AuraApp app = AuraApp.create(new io.aura.AuraConfig().port(port))
                .mcpAuthenticate(metadata -> "Bearer alice".equals(metadata.get("authorization"))
                        ? "alice" : null)
                .use(McpModule.create(router).http());
        HttpClient http = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
        try {
            app.start();
            HttpResponse<String> initialized = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Authorization", "Bearer alice")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(initialize(1))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(initialized.statusCode()).isEqualTo(200);
            String session = initialized.headers()
                    .firstValue(McpHttpTransport.SESSION_HEADER).orElseThrow();
            HttpResponse<String> ready = http.send(initializedNotification(endpoint, session),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ready.statusCode()).isEqualTo(202);

            HttpResponse<String> batch = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Authorization", "Bearer alice")
                            .header("Accept", "application/json, text/event-stream")
                            .header(McpHttpTransport.SESSION_HEADER, session)
                            .header(McpHttpTransport.PROTOCOL_VERSION_HEADER,
                                    McpHttpTransport.PROTOCOL_VERSION)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(java.util.List.of(
                                    Map.of("jsonrpc", "2.0", "id", 2, "method", "ping", "params", Map.of()),
                                    Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/list",
                                            "params", Map.of())))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(batch.statusCode()).isEqualTo(200);
            com.alibaba.fastjson2.JSONArray responses = JSON.parseArray(batch.body());
            assertThat(responses).hasSize(2);
            assertThat(responses.getJSONObject(0).getInteger("id")).isEqualTo(2);
            assertThat(responses.getJSONObject(1).getInteger("id")).isEqualTo(3);

            HttpResponse<String> notifications = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Authorization", "Bearer alice")
                            .header("Accept", "application/json, text/event-stream")
                            .header(McpHttpTransport.SESSION_HEADER, session)
                            .header(McpHttpTransport.PROTOCOL_VERSION_HEADER,
                                    McpHttpTransport.PROTOCOL_VERSION)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(java.util.List.of(
                                    Map.of("jsonrpc", "2.0", "method", "ping",
                                            "params", Map.of()),
                                    Map.of("jsonrpc", "2.0", "method", "ping",
                                            "params", Map.of())))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(notifications.statusCode()).isEqualTo(202);
            assertThat(notifications.body()).isEmpty();
        } finally {
            app.stop();
        }
    }

    @Test
    void realUndertowSseStreamPublishesIdentityBoundServerMessages() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        McpRouter router = new McpRouter();
        router.tool("ping_tool", "Ping").handler(ctx -> "pong");
        McpModule sseModule = McpModule.create(router).http("/mcp", 2,
                Duration.ofMinutes(5), 16 * 1024).sse();
        AuraApp app = AuraApp.create(new io.aura.AuraConfig().port(port))
                .mcpAuthenticate(metadata -> "alice")
                .use(sseModule);
        HttpClient http = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
        java.io.BufferedReader reader = null;
        try {
            app.start();
            HttpResponse<String> initialized = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Authorization", "Bearer alice")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(initialize(1))))
                            .build(), HttpResponse.BodyHandlers.ofString());
            String session = initialized.headers()
                    .firstValue(McpHttpTransport.SESSION_HEADER).orElseThrow();
            HttpResponse<String> ready = http.send(initializedNotification(endpoint, session),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ready.statusCode()).isEqualTo(202);

            HttpResponse<java.io.InputStream> stream = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Authorization", "Bearer alice")
                            .header("Accept", "text/event-stream")
                            .header(McpHttpTransport.SESSION_HEADER, session)
                            .header(McpHttpTransport.PROTOCOL_VERSION_HEADER,
                                    McpHttpTransport.PROTOCOL_VERSION)
                            .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(stream.headers().firstValue("Content-Type").orElse(""))
                    .contains("text/event-stream");
            reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream.body(), java.nio.charset.StandardCharsets.UTF_8));
            String initial = readEvent(reader);
            assertThat(initial).contains("event: endpoint");
            assertThat(sseModule.publish(session, "notifications/test", Map.of("value", "ready")))
                    .isTrue();
            String message = "";
            for (int i = 0; i < 5 && !message.contains("event: message"); i++) {
                message = readEvent(reader);
            }
            assertThat(message).contains("event: message").contains("notifications/test").contains("ready");
        } finally {
            if (reader != null) reader.close();
            app.stop();
        }
    }

    private static String readEvent(java.io.BufferedReader reader) throws Exception {
        StringBuilder frame = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) break;
            frame.append(line).append('\n');
        }
        return frame.toString();
    }

    @Test
    void sessionCarriesIdentityAndSupportsToolCalls() {
        TestClient.Response initialized = initialize("Bearer alice", 1);
        String session = initialized.header(McpHttpTransport.SESSION_HEADER);
        assertThat(session).isNotBlank();
        assertThat(JSON.parseObject(initialized.body()).getJSONObject("result")
                .getString("protocolVersion")).isEqualTo(McpHttpTransport.PROTOCOL_VERSION);

        TestClient.Response call = request("Bearer alice", session, 2, "tools/call",
                Map.of("name", "whoami", "arguments", Map.of())).expect(200);
        JSONObject result = JSON.parseObject(call.body()).getJSONObject("result");
        assertThat(result.getJSONArray("content").getJSONObject(0).getString("text"))
                .isEqualTo("alice");
    }

    @Test
    void batchRequestsReturnOnlyCorrelatedResponsesAndBoundBatchSize() {
        String session = initialize("Bearer alice", 1).header(McpHttpTransport.SESSION_HEADER);
        TestClient.Response response = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header("Accept", "application/json, text/event-stream")
                .body(java.util.List.of(
                        Map.of("jsonrpc", "2.0", "id", 2, "method", "ping", "params", Map.of()),
                        Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()),
                        Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/list", "params", Map.of())))
                .expect(200);
        com.alibaba.fastjson2.JSONArray responses = JSON.parseArray(response.body());
        assertThat(responses).hasSize(2);
        assertThat(responses.getJSONObject(0).getInteger("id")).isEqualTo(2);
        assertThat(responses.getJSONObject(1).getInteger("id")).isEqualTo(3);

        TestClient.Response invalid = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .body(java.util.List.of(Map.of("jsonrpc", "2.0", "params", Map.of())))
                .expect(200);
        assertThat(JSON.parseArray(invalid.body()).getJSONObject(0).getJSONObject("error")
                .getInteger("code")).isEqualTo(-32600);

        java.util.List<Map<String, Object>> oversized = new java.util.ArrayList<>();
        for (int i = 0; i < 33; i++) {
            oversized.add(Map.of("jsonrpc", "2.0", "id", i, "method", "ping", "params", Map.of()));
        }
        assertThat(errorCode(client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .body(oversized).expect(400))).isEqualTo(-32600);
    }

    @Test
    void sessionIsIdentityBoundWithoutDestroyingTheOwnersSession() {
        String session = initialize("Bearer alice", 1).header(McpHttpTransport.SESSION_HEADER);

        assertThat(errorCode(request("Bearer bob", session, 2, "ping", Map.of()).expect(404)))
                .isEqualTo(-32002);
        request("Bearer alice", session, 3, "ping", Map.of()).expect(200);
        assertThat(module.sessions().size()).isEqualTo(1);
    }

    @Test
    void permissionFailureIsAStableSanitizedToolResult() {
        String session = initialize("Bearer bob", 1).header(McpHttpTransport.SESSION_HEADER);

        TestClient.Response response = request("Bearer bob", session, 2, "tools/call",
                Map.of("name", "delete_order", "arguments", Map.of())).expect(200);
        JSONObject result = JSON.parseObject(response.body()).getJSONObject("result");
        assertThat(result.getString("code")).isEqualTo("MCP_PERMISSION_DENIED");
        assertThat(response.body()).doesNotContain("orders.delete", "permission denied");
    }

    @Test
    void deleteClosesOnlyTheOwnedSession() {
        String session = initialize("Bearer alice", 1).header(McpHttpTransport.SESSION_HEADER);

        client.delete("/mcp").header("Authorization", "Bearer bob")
                .header(McpHttpTransport.SESSION_HEADER, session).expect(404);
        request("Bearer alice", session, 2, "ping", Map.of()).expect(200);
        client.delete("/mcp").header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session).expect(204);
        assertThat(module.sessions().size()).isZero();
        request("Bearer alice", session, 3, "ping", Map.of()).expect(404);
    }

    @Test
    void deletePreventsNewDispatchWhileAnAdmittedRequestDrains() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        McpRouter router = new McpRouter();
        router.tool("block", "Block until released").handler(ctx -> {
            started.countDown();
            release.await();
            return "done";
        });
        McpModule draining = McpModule.create(router).http();
        TestClient local = TestClient.of(AuraApp.create()
                .mcpAuthenticate(metadata -> "alice")
                .use(draining));
        String session = local.post("/mcp").header("Authorization", "Bearer alice")
                .body(initialize(1)).expect(200).header(McpHttpTransport.SESSION_HEADER);
        local.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                        "params", Map.of()))
                .expect(202);

        java.util.concurrent.atomic.AtomicReference<TestClient.Response> first =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller = new Thread(() -> first.set(local.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .body(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                        "params", Map.of("name", "block", "arguments", Map.of())))
                .execute()));
        caller.start();
        assertThat(started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        local.delete("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .expect(204);
        assertThat(draining.sessions().size()).isEqualTo(1);
        local.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .body(Map.of("jsonrpc", "2.0", "id", 3, "method", "ping", "params", Map.of()))
                .expect(404);

        release.countDown();
        caller.join(1_000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(first.get().status()).isEqualTo(200);
        assertThat(draining.sessions().size()).isZero();
    }

    @Test
    void malformedAndUnknownRequestsUseJsonRpcErrors() {
        assertThat(errorCode(client.post("/mcp").header("Authorization", "Bearer alice")
                .body("not-json").expect(400))).isEqualTo(-32700);
        assertThat(errorCode(client.post("/mcp").header("Authorization", "Bearer alice")
                .body(Map.of("jsonrpc", "1.0", "id", 1, "method", "initialize"))
                .expect(400))).isEqualTo(-32600);

        String session = initialize("Bearer alice", 2).header(McpHttpTransport.SESSION_HEADER);
        assertThat(errorCode(request("Bearer alice", session, 3, "unknown/method", Map.of())
                .expect(200))).isEqualTo(-32601);

        assertThat(errorCode(request("Bearer alice", session, 4, "tools/call",
                Map.of("arguments", "invalid")).expect(400))).isEqualTo(-32602);
        assertThat(errorCode(request("Bearer alice", session, 5, "resources/read",
                Map.of()).expect(400))).isEqualTo(-32602);
        assertThat(errorCode(request("Bearer alice", session, 6, "prompts/get",
                Map.of()).expect(400))).isEqualTo(-32602);

        Map<String, Object> invalidInitialize = initialize(7);
        ((Map<String, Object>) invalidInitialize.get("params")).put("clientInfo", "invalid");
        assertThat(errorCode(client.post("/mcp").header("Authorization", "Bearer alice")
                .body(invalidInitialize).expect(400))).isEqualTo(-32602);
    }

    @Test
    void explicitNullIdGetsAResponseButNotificationsDoNot() {
        String session = initialize("Bearer alice", 1).header(McpHttpTransport.SESSION_HEADER);
        TestClient.Response response = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\",\"params\":{}}")
                .expect(200);
        assertThat(response.body()).contains("\"id\":null", "\"result\"");

        TestClient.Response notification = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .body(Map.of("jsonrpc", "2.0", "method", "ping",
                        "params", Map.of())).expect(202);
        assertThat(notification.body()).isEmpty();

        TestClient.Response invalidNotification = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .body(Map.of("jsonrpc", "2.0", "method", "ping",
                        "params", java.util.List.of("invalid"))).expect(202);
        assertThat(invalidNotification.body()).isEmpty();

        TestClient.Response invalidNotificationBatch = client.post("/mcp")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .body(java.util.List.of(
                        Map.of("jsonrpc", "2.0", "method", "ping",
                                "params", java.util.List.of("invalid")),
                        Map.of("jsonrpc", "2.0", "method", "ping", "params", Map.of())))
                .expect(202);
        assertThat(invalidNotificationBatch.body()).isEmpty();
    }

    @Test
    void browserOriginsMustBeExplicitlyAllowedForPostAndDelete() {
        TestClient.Response deniedPost = client.post("/mcp")
                .header("Origin", "https://attacker.example")
                .header("Authorization", "Bearer alice")
                .body(initialize(1)).expect(403);
        assertThat(errorCode(deniedPost)).isEqualTo(-32004);

        client.delete("/mcp")
                .header("Origin", "https://attacker.example")
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, "missing")
                .expect(403);
    }

    @Test
    void configuredOriginCanUseTheAuthenticatedTransport() {
        McpRouter router = new McpRouter();
        router.tool("ping", "Ping").handler(ctx -> "pong");
        McpModule allowed = McpModule.create(router).http();
        AuraApp app = AuraApp.create()
                .property("aura.mcp.http.origins", "https://trusted.example")
                .mcpAuthenticate(metadata -> "alice")
                .use(allowed);
        TestClient trusted = TestClient.of(app);

        TestClient.Response response = trusted.post("/mcp")
                .header("Origin", "https://trusted.example")
                .header("Authorization", "Bearer anything")
                .body(initialize(1)).expect(200);
        assertThat(response.header(McpHttpTransport.SESSION_HEADER)).isNotBlank();
    }

    @Test
    void oversizedToolResponsesAreRejectedBeforeBeingReturned() {
        McpRouter router = new McpRouter();
        router.tool("large", "Large response")
                .handler(ctx -> "x".repeat(1024));
        McpModule bounded = McpModule.create(router).http("/mcp", 2,
                Duration.ofMinutes(5), 512);
        AuraApp app = AuraApp.create()
                .mcpAuthenticate(metadata -> "alice")
                .use(bounded);
        TestClient boundedClient = TestClient.of(app);
        String session = boundedClient.post("/mcp").header("Authorization", "Bearer anything")
                .body(initialize(1)).expect(200).header(McpHttpTransport.SESSION_HEADER);
        boundedClient.post("/mcp")
                .header("Authorization", "Bearer anything")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                        "params", Map.of()))
                .expect(202);
        TestClient.Response response = boundedClient.post("/mcp")
                .header("Authorization", "Bearer anything")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .body(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                        "params", Map.of("name", "large", "arguments", Map.of())))
                .expect(502);
        assertThat(response.body()).contains("MCP_RESPONSE_TOO_LARGE");
    }

    @Test
    void oversizedFallbackErrorNeverExceedsConfiguredResponseBudget() {
        McpRouter router = new McpRouter();
        router.tool("large", "Large response")
                .handler(ctx -> "x".repeat(1024));
        McpModule bounded = McpModule.create(router).http("/mcp", 2,
                Duration.ofMinutes(5), 1);
        AuraApp app = AuraApp.create()
                .mcpAuthenticate(metadata -> "alice")
                .use(bounded);
        TestClient client = TestClient.of(app);
        TestClient.Response response = client.post("/mcp")
                .header("Authorization", "Bearer anything")
                .body(initialize(1))
                .expect(502);
        assertThat(response.body() == null ? 0 : response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(1);
    }

    private TestClient.Response initialize(String authorization, int id) {
        TestClient.Response initialized = client.post("/mcp").header("Authorization", authorization)
                .body(initialize(id)).expect(200);
        String session = initialized.header(McpHttpTransport.SESSION_HEADER);
        client.post("/mcp")
                .header("Authorization", authorization)
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                        "params", Map.of()))
                .expect(202);
        return initialized;
    }

    private TestClient.Request request(String authorization, String session, int id,
                                       String method, Map<String, Object> params) {
        return client.post("/mcp")
                .header("Authorization", authorization)
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .body(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    private static HttpRequest initializedNotification(URI endpoint, String session) {
        return HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer alice")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of(
                        "jsonrpc", "2.0", "method", "notifications/initialized",
                        "params", Map.of()))))
                .build();
    }

    private static Map<String, Object> initialize(int id) {
        return new java.util.LinkedHashMap<>(Map.of(
                "jsonrpc", "2.0", "id", id, "method", "initialize",
                "params", new java.util.LinkedHashMap<>(Map.of(
                        "protocolVersion", McpHttpTransport.PROTOCOL_VERSION,
                        "clientInfo", Map.of("name", "test-client", "version", "1.0"),
                        "capabilities", Map.of()))));
    }

    private static int errorCode(TestClient.Response response) {
        return JSON.parseObject(response.body()).getJSONObject("error").getIntValue("code");
    }
}

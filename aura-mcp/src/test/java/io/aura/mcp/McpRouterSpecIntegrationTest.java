package io.aura.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.aura.AuraApp;
import io.aura.AuraMcpRuntime;
import io.aura.contract.McpRouterSpec;
import io.aura.web.TestClient;
import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpRouterSpecIntegrationTest {
    @Test
    void customRouterRunsThroughStdioDispatchContract() {
        RecordingRouter router = new RecordingRouter();
        AuraApp app = application(router).use(McpModule.create(router)).prepare();
        AuraMcpRuntime runtime = (AuraMcpRuntime) app.runtimeView();

        Object schema = AuraMcpStarter.handler(runtime, router)
                .handle("tools/list", new JSONObject());
        Object result = AuraMcpStarter.handler(runtime, router).handle("tools/call",
                JSONObject.of(
                        "name", "echo",
                        "arguments", Map.of("value", "stdio"),
                        "_meta", Map.of("authorization", "Bearer custom")));

        assertThat(schema).isEqualTo(router.buildSchema());
        assertThat(result).isInstanceOfSatisfying(Map.class, value ->
                assertThat(value.toString()).contains("stdio"));
        assertThat(router.lastUser()).isEqualTo("custom-user");
        assertThat(router.lastPermissionAllowed()).isTrue();
    }

    @Test
    void customRouterRunsThroughAuthenticatedHttpTransport() {
        RecordingRouter router = new RecordingRouter();
        AuraApp app = application(router)
                .use(McpModule.create((McpRouterSpec) router)
                        .http("/mcp", 4, Duration.ofMinutes(5)));
        TestClient client = TestClient.of(app);

        TestClient.Response initialized = client.post("/mcp")
                .header("Authorization", "Bearer custom")
                .body(initialize(1))
                .expect(200);
        String session = initialized.header(McpHttpTransport.SESSION_HEADER);
        client.post("/mcp")
                .header("Authorization", "Bearer custom")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized",
                        "params", Map.of()))
                .expect(202);

        TestClient.Response listed = request(client, session, 2, "tools/list", Map.of()).expect(200);
        TestClient.Response called = request(client, session, 3, "tools/call",
                Map.of("name", "echo", "arguments", Map.of("value", "http"))).expect(200);

        assertThat(JSON.parseObject(listed.body()).toString()).contains("echo");
        assertThat(JSON.parseObject(called.body()).toString()).contains("http");
        assertThat(router.lastUser()).isEqualTo("custom-user");
        assertThat(router.lastPermissionAllowed()).isTrue();
        assertThat(app.require(McpRouterSpec.class)).isSameAs(router);
    }

    @Test
    void stdioRejectsInvalidParamsBeforeInvokingCustomRouter() throws Exception {
        RecordingRouter router = new RecordingRouter();
        AuraMcpRuntime runtime = (AuraMcpRuntime) application(router)
                .use(McpModule.create(router)).prepare().runtimeView();

        JSONObject response = runStdio(runtime, router, Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of()));

        assertThat(response.getJSONObject("error").getIntValue("code")).isEqualTo(-32602);
        assertThat(response.getJSONObject("error").getString("message")).isEqualTo("Invalid params");
        assertThat(router.invocations()).isZero();

        for (Object invalidName : List.of(123, true, List.of("echo"), Map.of("value", "echo"))) {
            JSONObject typed = runStdio(runtime, router, Map.of(
                    "jsonrpc", "2.0", "id", 10, "method", "tools/call",
                    "params", Map.of("name", invalidName)));
            assertThat(typed.getJSONObject("error").getIntValue("code")).isEqualTo(-32602);
        }
        assertThat(router.invocations()).isZero();
    }

    @Test
    void stdioHidesCustomCatalogFailuresAsInternalErrors() throws Exception {
        RecordingRouter router = new RecordingRouter() {
            @Override public Map<String, Object> buildSchema() {
                throw new IllegalStateException("password=secret internal/path");
            }
        };
        AuraMcpRuntime runtime = (AuraMcpRuntime) application(router)
                .use(McpModule.create((McpRouterSpec) router)).prepare().runtimeView();

        JSONObject response = runStdio(runtime, router, Map.of(
                "jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()));

        assertThat(response.getJSONObject("error").getIntValue("code")).isEqualTo(-32603);
        assertThat(response.getJSONObject("error").getString("message")).isEqualTo("Internal error");
        assertThat(response.toJSONString()).doesNotContain("password", "secret", "internal/path");
    }

    @Test
    void stdioUsesStableAuthenticationAndPermissionFailures() throws Exception {
        RecordingRouter router = new RecordingRouter();
        AuraApp deniedApp = AuraApp.create()
                .mcpAuthenticate(metadata -> "user")
                .mcpAuthorize((user, permission) -> false)
                .use(McpModule.create((McpRouterSpec) router))
                .prepare();

        JSONObject denied = runStdio((AuraMcpRuntime) deniedApp.runtimeView(), router, Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "echo", "arguments", Map.of("value", "x"))));
        assertThat(denied.toJSONString()).contains("MCP_PERMISSION_DENIED");

        AuraApp anonymousApp = AuraApp.create()
                .mcpAuthenticate(metadata -> null)
                .use(McpModule.create((McpRouterSpec) router))
                .prepare();
        JSONObject anonymous = runStdio((AuraMcpRuntime) anonymousApp.runtimeView(), router, Map.of(
                "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "echo", "arguments", Map.of("value", "x"))));
        assertThat(anonymous.toJSONString()).contains("MCP_AUTHENTICATION_REQUIRED");
    }

    private static AuraApp application(RecordingRouter router) {
        return AuraApp.create()
                .mcpAuthenticate(metadata -> "Bearer custom".equals(metadata.get("authorization"))
                        ? "custom-user" : null)
                .mcpAuthorize((user, permission) ->
                        "custom-user".equals(user) && "custom.invoke".equals(permission));
    }

    private static TestClient.Request request(TestClient client, String session, int id,
                                              String method, Map<String, Object> params) {
        return client.post("/mcp")
                .header("Authorization", "Bearer custom")
                .header(McpHttpTransport.SESSION_HEADER, session)
                .header(McpHttpTransport.PROTOCOL_VERSION_HEADER, McpHttpTransport.PROTOCOL_VERSION)
                .body(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    private static Map<String, Object> initialize(int id) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", McpHttpTransport.PROTOCOL_VERSION,
                        "clientInfo", Map.of("name", "custom-test", "version", "1.0"),
                        "capabilities", Map.of()));
    }

    private static JSONObject runStdio(AuraMcpRuntime runtime, McpRouterSpec router,
                                       Map<String, Object> request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpProtocol protocol = new McpProtocol(
                new PrintStream(output, true, StandardCharsets.UTF_8),
                "custom-test", AuraMcpStarter.handler(runtime, router));
        protocol.run(new ByteArrayInputStream(
                (JSON.toJSONString(request) + "\n").getBytes(StandardCharsets.UTF_8)));
        return JSON.parseObject(output.toString(StandardCharsets.UTF_8).trim());
    }

    private static class RecordingRouter implements McpRouterSpec {
        private final AtomicReference<String> lastUser = new AtomicReference<>();
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile boolean lastPermissionAllowed;

        @Override public Map<String, Object> buildSchema() {
            return Map.of("tools", List.of(Map.of(
                    "name", "echo",
                    "description", "Echo a value",
                    "inputSchema", Map.of("type", "object"))));
        }

        @Override public Object invoke(String toolName, Map<String, Object> arguments,
                                       String userId, BiPredicate<String, String> permissions) {
            invocations.incrementAndGet();
            lastUser.set(userId);
            lastPermissionAllowed = permissions != null
                    && permissions.test(userId, "custom.invoke");
            if (!"echo".equals(toolName) || !lastPermissionAllowed) {
                throw new SecurityException("denied");
            }
            return Map.of("value", arguments.get("value"));
        }

        String lastUser() { return lastUser.get(); }
        boolean lastPermissionAllowed() { return lastPermissionAllowed; }
        int invocations() { return invocations.get(); }
    }
}

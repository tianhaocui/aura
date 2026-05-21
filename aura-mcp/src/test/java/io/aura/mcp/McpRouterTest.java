package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpRouterTest {

    enum OrderStatus {
        NEW(1, "新建"), CANCEL(9, "已取消"), PAID(2, "已支付");
        final int code;
        final String label;
        OrderStatus(int code, String label) { this.code = code; this.label = label; }
    }

    static class UserService {
        public String get(int id) { return "user-" + id; }
        public List<String> list() { return List.of("alice", "bob"); }
    }

    @Test
    void tool_serviceMethod_registersCorrectly() {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool("get_user", svc, "get", "获取用户");

        assertThat(mcp.tools()).hasSize(1);
        McpTool tool = mcp.tools().get(0);
        assertThat(tool.name()).isEqualTo("get_user");
        assertThat(tool.description()).isEqualTo("获取用户");
        assertThat(tool.params()).hasSize(1);
        assertThat(tool.params().get(0).name()).isEqualTo("id");
    }

    @Test
    void tool_serviceMethod_invokesCorrectly() throws Exception {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool("get_user", svc, "get", "获取用户");

        Object result = mcp.invoke("get_user", Map.of("id", "42"));
        assertThat(result).isEqualTo("user-42");
    }

    @Test
    void tool_customHandler_works() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("greet", "打招呼")
           .param("name", String.class, "名字")
           .handler(ctx -> "hello " + ctx.getString("name"));

        Object result = mcp.invoke("greet", Map.of("name", "Alice"));
        assertThat(result).isEqualTo("hello Alice");
    }

    @Test
    void tool_enumParam_schemaContainsEnumValues() {
        McpRouter mcp = new McpRouter();
        mcp.tool("query", "查询")
           .param("status", OrderStatus.class, "订单状态")
           .handler(ctx -> null);

        McpTool tool = mcp.tools().get(0);
        McpParam param = tool.params().get(0);
        assertThat(param.isEnum()).isTrue();
        assertThat(param.enumValues()).hasSize(3);
        assertThat(param.enumValues().get(0).label()).contains("NEW");
        assertThat(param.enumValues().get(0).label()).contains("新建");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tool_enumParam_schemaOutputCorrect() {
        McpRouter mcp = new McpRouter();
        mcp.tool("query", "查询")
           .param("status", OrderStatus.class, "订单状态")
           .handler(ctx -> null);

        Map<String, Object> schema = mcp.buildSchema();
        List<Map<String, Object>> tools = (List<Map<String, Object>>) schema.get("tools");
        Map<String, Object> inputSchema = (Map<String, Object>) tools.get(0).get("inputSchema");
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        Map<String, Object> statusProp = (Map<String, Object>) properties.get("status");

        assertThat(statusProp.get("type")).isEqualTo("string");
        assertThat((List<String>) statusProp.get("enum")).hasSize(3);
        assertThat(statusProp.get("description").toString()).contains("新建");
    }

    @Test
    void tool_mapMapping_convertsLabelToCode() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("query_city", "查询城市")
           .param("city", String.class, "城市", Map.of("北京", "010", "上海", "021"))
           .handler(ctx -> ctx.getString("city"));

        Object result = mcp.invoke("query_city", Map.of("city", "北京"));
        assertThat(result).isEqualTo("010");
    }

    @Test
    void invoke_unknownTool_throws() {
        McpRouter mcp = new McpRouter();
        assertThatThrownBy(() -> mcp.invoke("nope", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void tool_multipleRegistrations_allPresent() {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool("get_user", svc, "get", "获取用户");
        mcp.tool("list_users", svc, "list", "用户列表");

        assertThat(mcp.tools()).hasSize(2);
    }

    // --- 边界场景 ---

    @Test
    void tool_serviceMethod_notFound_throws() {
        McpRouter mcp = new McpRouter();
        assertThatThrownBy(() -> mcp.tool("x", new UserService(), "nonexistent", "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void tool_enumParam_invokeWithEnumName_resolvesCorrectly() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("query", "查询")
           .param("status", OrderStatus.class, "订单状态")
           .handler(ctx -> ctx.getEnum("status", OrderStatus.class).code);

        Object result = mcp.invoke("query", Map.of("status", "PAID"));
        assertThat(result).isEqualTo(2);
    }

    @Test
    void tool_mapMapping_unknownLabel_passesThrough() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("query_city", "查询城市")
           .param("city", String.class, "城市", Map.of("北京", "010", "上海", "021"))
           .handler(ctx -> ctx.getString("city"));

        Object result = mcp.invoke("query_city", Map.of("city", "深圳"));
        assertThat(result).isEqualTo("深圳");
    }

    @Test
    void tool_customHandler_multipleParams() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("add", "加法")
           .param("a", int.class, "数字A")
           .param("b", int.class, "数字B")
           .handler(ctx -> ctx.getInt("a") + ctx.getInt("b"));

        Object result = mcp.invoke("add", Map.of("a", "3", "b", "7"));
        assertThat(result).isEqualTo(10);
    }

    @Test
    void tool_customHandler_missingParam_returnsZeroForInt() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("inc", "加一")
           .param("n", int.class, "数字")
           .handler(ctx -> ctx.getInt("n") + 1);

        Object result = mcp.invoke("inc", Map.of());
        assertThat(result).isEqualTo(1);
    }

    @Test
    void tool_customHandler_missingParam_returnsNullForString() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("echo", "回声")
           .param("msg", String.class, "消息")
           .handler(ctx -> ctx.getString("msg"));

        Object result = mcp.invoke("echo", Map.of());
        assertThat(result).isNull();
    }

    @Test
    void buildSchema_multipleTools_allPresent() {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool("get_user", svc, "get", "获取用户");
        mcp.tool("list_users", svc, "list", "用户列表");

        Map<String, Object> schema = mcp.buildSchema();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) schema.get("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).get("name")).isEqualTo("get_user");
        assertThat(tools.get(1).get("name")).isEqualTo("list_users");
    }

    @Test
    void buildSchema_paramTypes_correctJsonTypes() {
        McpRouter mcp = new McpRouter();
        mcp.tool("test", "测试")
           .param("name", String.class, "名字")
           .param("age", int.class, "年龄")
           .param("active", boolean.class, "是否激活")
           .handler(ctx -> null);

        McpTool tool = mcp.tools().get(0);
        assertThat(tool.params().get(0).type()).isEqualTo("string");
        assertThat(tool.params().get(1).type()).isEqualTo("integer");
        assertThat(tool.params().get(2).type()).isEqualTo("boolean");
    }

    @Test
    void unregisteredRoutes_notInTools() {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool("get_user", svc, "get", "获取用户");

        List<String> names = mcp.tools().stream().map(McpTool::name).toList();
        assertThat(names).containsExactly("get_user");
        assertThat(names).doesNotContain("list_users");
    }

    @Test
    void tool_serviceMethod_listReturnsCollection() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("list_users", new UserService(), "list", "用户列表");

        Object result = mcp.invoke("list_users", Map.of());
        assertThat(result).isEqualTo(List.of("alice", "bob"));
    }

    enum SimpleEnum { A, B, C }

    @Test
    void tool_enumWithoutLabel_usesNameOnly() {
        McpRouter mcp = new McpRouter();
        mcp.tool("pick", "选择")
           .param("choice", SimpleEnum.class, "选项")
           .handler(ctx -> null);

        McpParam param = mcp.tools().get(0).params().get(0);
        assertThat(param.isEnum()).isTrue();
        assertThat(param.enumValues().get(0).label()).isEqualTo("A");
    }
}

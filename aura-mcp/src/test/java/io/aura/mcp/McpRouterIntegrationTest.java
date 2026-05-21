package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpRouterIntegrationTest {

    // --- domain models ---

    enum OrderStatus {
        NEW(1, "新建"), PROCESSING(2, "处理中"), DONE(3, "已完成"), CANCEL(9, "已取消");
        final int code;
        final String label;
        OrderStatus(int code, String label) { this.code = code; this.label = label; }
    }

    record Order(int id, String item, OrderStatus status) {}
    record User(int id, String name) {}

    // --- services ---

    static class OrderService {
        public Order get(int id) { return new Order(id, "Widget", OrderStatus.NEW); }
        public List<Order> list() { return List.of(new Order(1, "A", OrderStatus.NEW), new Order(2, "B", OrderStatus.DONE)); }
        public Order create(String body) { return new Order(99, body, OrderStatus.NEW); }
    }

    static class UserService {
        public User get(int id) { return new User(id, "Alice"); }
    }

    // === 1. service 方法绑定 ===

    @Test
    void serviceMethod_get_bindsAndInvokes() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("get_order", new OrderService(), "get", "获取订单");

        McpTool tool = mcp.tools().get(0);
        assertThat(tool.name()).isEqualTo("get_order");
        assertThat(tool.description()).isEqualTo("获取订单");
        assertThat(tool.params()).hasSize(1);
        assertThat(tool.params().get(0).name()).isEqualTo("id");

        Object result = mcp.invoke("get_order", Map.of("id", "5"));
        assertThat(result).isInstanceOf(Order.class);
        assertThat(((Order) result).id()).isEqualTo(5);
    }

    @Test
    void serviceMethod_list_noParams() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("list_orders", new OrderService(), "list", "订单列表");

        assertThat(mcp.tools().get(0).params()).isEmpty();
        Object result = mcp.invoke("list_orders", Map.of());
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).hasSize(2);
    }

    // === 2. enum 参数 — schema + invoke ===

    @Test
    void enumParam_schemaContainsAllValues() {
        McpRouter mcp = new McpRouter();
        mcp.tool("query_orders", "按状态查询订单")
           .param("status", OrderStatus.class, "订单状态")
           .handler(ctx -> null);

        McpParam param = mcp.tools().get(0).params().get(0);
        assertThat(param.isEnum()).isTrue();
        assertThat(param.enumValues()).hasSize(4);

        List<String> labels = param.enumValues().stream().map(McpEnumValue::label).toList();
        assertThat(labels).anyMatch(l -> l.contains("NEW") && l.contains("新建"));
        assertThat(labels).anyMatch(l -> l.contains("CANCEL") && l.contains("已取消"));
    }

    @Test
    void enumParam_invokeConvertsNameToEnum() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("query_orders", "按状态查询订单")
           .param("status", OrderStatus.class, "订单状态")
           .handler(ctx -> {
               OrderStatus s = ctx.getEnum("status", OrderStatus.class);
               return "code=" + s.code;
           });

        Object result = mcp.invoke("query_orders", Map.of("status", "PROCESSING"));
        assertThat(result).isEqualTo("code=2");
    }

    @Test
    void enumParam_invalidName_throwsIllegalArgument() {
        McpRouter mcp = new McpRouter();
        mcp.tool("query_orders", "按状态查询订单")
           .param("status", OrderStatus.class, "订单状态")
           .handler(ctx -> ctx.getEnum("status", OrderStatus.class));

        assertThatThrownBy(() -> mcp.invoke("query_orders", Map.of("status", "INVALID")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // === 3. Map mapping — label→code 转换 ===

    @Test
    void mapMapping_labelToCode_works() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("query_by_city", "按城市查询")
           .param("city", String.class, "城市", Map.of("北京", "010", "上海", "021", "广州", "020"))
           .handler(ctx -> "code:" + ctx.getString("city"));

        assertThat(mcp.invoke("query_by_city", Map.of("city", "北京"))).isEqualTo("code:010");
        assertThat(mcp.invoke("query_by_city", Map.of("city", "上海"))).isEqualTo("code:021");
    }

    @Test
    void mapMapping_unknownLabel_passesThrough() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool("query_by_city", "按城市查询")
           .param("city", String.class, "城市", Map.of("北京", "010"))
           .handler(ctx -> ctx.getString("city"));

        assertThat(mcp.invoke("query_by_city", Map.of("city", "深圳"))).isEqualTo("深圳");
    }

    @Test
    void mapMapping_schemaShowsEnumOptions() {
        McpRouter mcp = new McpRouter();
        mcp.tool("query_by_city", "按城市查询")
           .param("city", String.class, "城市", Map.of("北京", "010", "上海", "021"))
           .handler(ctx -> null);

        McpParam param = mcp.tools().get(0).params().get(0);
        assertThat(param.isEnum()).isTrue();
        assertThat(param.enumValues()).hasSize(2);
    }

    // === 4. 多 API 聚合 ===

    @Test
    @SuppressWarnings("unchecked")
    void multiApiAggregation_combinesResults() throws Exception {
        var userService = new UserService();
        var orderService = new OrderService();

        McpRouter mcp = new McpRouter();
        mcp.tool("get_user_profile", "获取用户完整资料")
           .param("id", int.class, "用户ID")
           .handler(ctx -> {
               int id = ctx.getInt("id");
               User user = userService.get(id);
               List<Order> orders = orderService.list();
               return Map.of("user", user, "orders", orders);
           });

        Object result = mcp.invoke("get_user_profile", Map.of("id", "1"));
        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("user")).isInstanceOf(User.class);
        assertThat(((User) map.get("user")).name()).isEqualTo("Alice");
        assertThat((List<?>) map.get("orders")).hasSize(2);
    }

    // === 5. buildSchema — MCP protocol 格式 ===

    @Test
    @SuppressWarnings("unchecked")
    void buildSchema_conformsToMcpProtocol() {
        McpRouter mcp = new McpRouter();
        mcp.tool("get_order", new OrderService(), "get", "获取订单");
        mcp.tool("query_orders", "按状态查询订单")
           .param("status", OrderStatus.class, "订单状态")
           .param("limit", int.class, "返回数量")
           .handler(ctx -> null);

        Map<String, Object> schema = mcp.buildSchema();
        assertThat(schema).containsKey("tools");

        List<Map<String, Object>> tools = (List<Map<String, Object>>) schema.get("tools");
        assertThat(tools).hasSize(2);

        // tool 0: get_order
        Map<String, Object> t0 = tools.get(0);
        assertThat(t0.get("name")).isEqualTo("get_order");
        assertThat(t0.get("description")).isEqualTo("获取订单");
        assertThat(t0).containsKey("inputSchema");

        Map<String, Object> inputSchema0 = (Map<String, Object>) t0.get("inputSchema");
        assertThat(inputSchema0.get("type")).isEqualTo("object");
        assertThat(inputSchema0).containsKey("properties");
        assertThat(inputSchema0).containsKey("required");

        // tool 1: query_orders — enum param
        Map<String, Object> t1 = tools.get(1);
        Map<String, Object> inputSchema1 = (Map<String, Object>) t1.get("inputSchema");
        Map<String, Object> properties = (Map<String, Object>) inputSchema1.get("properties");
        Map<String, Object> statusProp = (Map<String, Object>) properties.get("status");

        assertThat(statusProp.get("type")).isEqualTo("string");
        assertThat(statusProp).containsKey("enum");
        assertThat((List<String>) statusProp.get("enum")).hasSize(4);
        assertThat(statusProp.get("description").toString()).contains("新建");

        Map<String, Object> limitProp = (Map<String, Object>) properties.get("limit");
        assertThat(limitProp.get("type")).isEqualTo("integer");
    }

    // === 6. 混合注册 — 多种方式共存 ===

    @Test
    void mixedRegistration_allToolsPresent() throws Exception {
        McpRouter mcp = new McpRouter();
        var orderService = new OrderService();
        var userService = new UserService();

        mcp.tool("get_order", orderService, "get", "获取订单");
        mcp.tool("get_user", userService, "get", "获取用户");
        mcp.tool("search", "搜索")
           .param("keyword", String.class, "关键词")
           .handler(ctx -> "found:" + ctx.getString("keyword"));

        assertThat(mcp.tools()).hasSize(3);

        assertThat(mcp.invoke("get_order", Map.of("id", "1"))).isInstanceOf(Order.class);
        assertThat(mcp.invoke("get_user", Map.of("id", "2"))).isInstanceOf(User.class);
        assertThat(mcp.invoke("search", Map.of("keyword", "test"))).isEqualTo("found:test");
    }
}

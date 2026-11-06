package io.aura.mcp;

import io.aura.contract.ApiParameter;
import io.aura.contract.Route;
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

    // --- declared parameters ---

    private static ApiParameter id() {
        return new ApiParameter("id", "int", "query", true, "订单号");
    }

    /** The choices an enum parameter accepts, declared the way the route catalog declares them. */
    private static ApiParameter status() {
        return new ApiParameter("status", "OrderStatus", "query", true, "订单状态",
                Map.of("type", "string", "enum", List.of("NEW", "PROCESSING", "DONE", "CANCEL")));
    }

    // === 1. service 方法绑定 ===

    @Test
    void serviceMethod_get_bindsAndInvokes() throws Exception {
        McpRouter mcp = new McpRouter();
        OrderService orders = new OrderService();
        mcp.tool(Route.of("GET", "/order", (McpHandler) ctx -> orders.get(ctx.getInt("id")))
                .describedAs("获取订单")
                .withParams(List.of(id())));

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
        OrderService orders = new OrderService();
        mcp.tool(Route.of("GET", "/orders", (McpHandler) ctx -> orders.list())
                .describedAs("订单列表"));

        assertThat(mcp.tools().get(0).params()).isEmpty();
        Object result = mcp.invoke("get_orders", Map.of());
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).hasSize(2);
    }

    // === 2. enum 参数 — schema + invoke ===

    @Test
    void enumParam_publishesTheChoicesItsContractDeclares() {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/query", (McpHandler) ctx -> null)
                .describedAs("按状态查询订单")
                .withParams(List.of(status())));

        McpParam param = mcp.tools().get(0).params().get(0);
        assertThat(param.contract().type()).isEqualTo("OrderStatus");

        McpWire.Property status = McpWire.of(mcp.buildSchema()).tool("post_query").inputSchema()
                .property("status");
        assertThat(status.type()).isEqualTo("string");
        assertThat(status.enumValues()).containsExactly("NEW", "PROCESSING", "DONE", "CANCEL");
        assertThat(status.description()).isEqualTo("订单状态");
    }

    @Test
    void enumParam_invokeConvertsNameToEnum() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/query", (McpHandler) ctx -> {
                    OrderStatus s = ctx.getEnum("status", OrderStatus.class);
                    return "code=" + s.code;
                })
                .describedAs("按状态查询订单")
                .withParams(List.of(status())));

        Object result = mcp.invoke("post_query", Map.of("status", "PROCESSING"));
        assertThat(result).isEqualTo("code=2");
    }

    @Test
    void enumParam_invalidName_throwsIllegalArgument() {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/query",
                (McpHandler) ctx -> ctx.getEnum("status", OrderStatus.class))
                .describedAs("按状态查询订单")
                .withParams(List.of(status())));

        assertThatThrownBy(() -> mcp.invoke("post_query", Map.of("status", "INVALID")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // === 3. label→code 转换属于 handler ===

    @Test
    void labelMapping_livesInTheHandlerThatUsesIt() throws Exception {
        McpRouter mcp = new McpRouter();
        Map<String, String> cityCodes = Map.of("北京", "010", "上海", "021", "广州", "020");
        mcp.tool(Route.of("POST", "/city",
                (McpHandler) ctx -> "code:" + cityCodes.getOrDefault(ctx.getString("city"), ctx.getString("city")))
                .describedAs("按城市查询")
                .withParams(List.of(new ApiParameter("city", "String", "query", true, "城市",
                        Map.of("type", "string", "enum", List.of("北京", "上海", "广州"))))));

        assertThat(mcp.invoke("post_city", Map.of("city", "北京"))).isEqualTo("code:010");
        assertThat(mcp.invoke("post_city", Map.of("city", "上海"))).isEqualTo("code:021");
        assertThat(mcp.invoke("post_city", Map.of("city", "深圳"))).isEqualTo("code:深圳");
    }

    // === 4. 多 API 聚合 ===

    @Test
    @SuppressWarnings("unchecked")
    void multiApiAggregation_combinesResults() throws Exception {
        var userService = new UserService();
        var orderService = new OrderService();

        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("GET", "/user/profile", (McpHandler) ctx -> {
                    int id = ctx.getInt("id");
                    User user = userService.get(id);
                    List<Order> orders = orderService.list();
                    return Map.of("user", user, "orders", orders);
                })
                .describedAs("获取用户完整资料")
                .withParams(List.of(new ApiParameter("id", "int", "query", true, "用户ID"))));

        Object result = mcp.invoke("get_user_profile", Map.of("id", "1"));
        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("user")).isInstanceOf(User.class);
        assertThat(((User) map.get("user")).name()).isEqualTo("Alice");
        assertThat((List<?>) map.get("orders")).hasSize(2);
    }

    // === 5. buildSchema — MCP protocol 格式 ===

    @Test
    void buildSchema_conformsToMcpProtocol() {
        McpRouter mcp = new McpRouter();
        var orders = new OrderService();
        mcp.tool(Route.of("GET", "/order", (McpHandler) ctx -> orders.get(ctx.getInt("id")))
                .describedAs("获取订单")
                .withParams(List.of(id())));
        mcp.tool(Route.of("POST", "/query", (McpHandler) ctx -> null)
                .describedAs("按状态查询订单")
                .withParams(List.of(status(),
                        new ApiParameter("limit", "int", "query", true, "返回数量"))));

        McpWire wire = McpWire.of(mcp.buildSchema());
        assertThat(wire.tools()).hasSize(2);

        // tool 0: get_order
        McpWire.Tool getOrder = wire.tool("get_order");
        assertThat(getOrder.description()).isEqualTo("获取订单");
        assertThat(getOrder.inputSchema().type()).isEqualTo("object");
        assertThat(getOrder.inputSchema().keys()).contains("properties", "required");

        // tool 1: post_query — enum param rendered from the declared schema, and a scalar
        // rendered from the shared Java-to-JSON table.
        McpWire.Schema query = wire.tool("post_query").inputSchema();
        assertThat(query.property("status").type()).isEqualTo("string");
        assertThat(query.property("status").enumValues()).hasSize(4);
        assertThat(query.property("status").description()).isEqualTo("订单状态");
        assertThat(query.property("limit").type()).isEqualTo("integer");
    }

    // === 6. 混合注册 — 多种方式共存 ===

    @Test
    void mixedRegistration_allToolsPresent() throws Exception {
        McpRouter mcp = new McpRouter();
        var orderService = new OrderService();
        var userService = new UserService();

        mcp.tool(Route.of("GET", "/order", (McpHandler) ctx -> orderService.get(ctx.getInt("id")))
                .withParams(List.of(id())));
        mcp.tool(Route.of("GET", "/user", (McpHandler) ctx -> userService.get(ctx.getInt("id")))
                .withParams(List.of(new ApiParameter("id", "int", "query", true, "用户号"))));
        mcp.tool(Route.of("POST", "/search", (McpHandler) ctx -> "found:" + ctx.getString("keyword"))
                .withParams(List.of(new ApiParameter("keyword", "String", "query", true, "关键词"))));

        assertThat(mcp.tools()).hasSize(3);

        assertThat(mcp.invoke("get_order", Map.of("id", "1"))).isInstanceOf(Order.class);
        assertThat(mcp.invoke("get_user", Map.of("id", "2"))).isInstanceOf(User.class);
        assertThat(mcp.invoke("post_search", Map.of("keyword", "test"))).isEqualTo("found:test");
    }
}

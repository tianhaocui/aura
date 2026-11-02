package io.aura.mcp;

import io.aura.contract.ApiParameter;
import io.aura.contract.ContractException;
import io.aura.contract.Route;
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
        public String check(boolean active) { return "active=" + active; }
        public String rate(double value) { return "rate=" + value; }
    }

    /** An enum parameter declares its accepted values in its schema, the way the route catalog does. */
    private static ApiParameter statusParam() {
        return new ApiParameter("status", "OrderStatus", "query", true, "订单状态",
                Map.of("type", "string", "enum", List.of("NEW", "CANCEL", "PAID")));
    }

        @Test
    void tool_registersDeclaredParams() {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool(Route.of("GET", "/user", (McpHandler) ctx -> svc.get(ctx.getInt("id")))
                .describedAs("获取用户")
                .withParams(List.of(new ApiParameter("id", "int", "query", true, "用户号"))));

        assertThat(mcp.tools()).hasSize(1);
        McpTool tool = mcp.tools().get(0);
        assertThat(tool.name()).isEqualTo("get_user");
        assertThat(tool.description()).isEqualTo("获取用户");
        assertThat(tool.params()).hasSize(1);
        assertThat(tool.params().get(0).name()).isEqualTo("id");
    }

    @Test
    void tool_invokesDeclaredHandler() throws Exception {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool(Route.of("GET", "/user", (McpHandler) ctx -> svc.get(ctx.getInt("id")))
                .describedAs("获取用户")
                .withParams(List.of(new ApiParameter("id", "int", "query", true, "用户号"))));

        Object result = mcp.invoke("get_user", Map.of("id", "42"));
        assertThat(result).isEqualTo("user-42");
    }

    @Test
    void tool_customHandler_works() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/greet", (McpHandler) ctx -> "hello " + ctx.getString("name"))
                .withParams(List.of(new ApiParameter("name", "String", "query", true, "名字"))));

        Object result = mcp.invoke("post_greet", Map.of("name", "Alice"));
        assertThat(result).isEqualTo("hello Alice");
    }

    @Test
    void tool_sharedRouteContract_reusesNameAndParameterMetadata() throws Exception {
        var route = Route.of("GET", "/orders/{id}", (McpHandler) ctx -> "order-" + ctx.getLong("id"))
                .describedAs("Get order")
                .withReturnType("Order")
                .withParams(List.of(new ApiParameter("id", "long", "path", true, "Order ID")));
        McpRouter mcp = new McpRouter().tool(route);

        assertThat(mcp.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("get_orders_by_id");
            assertThat(tool.description()).isEqualTo("Get order");
            assertThat(tool.params()).singleElement().extracting(McpParam::name).isEqualTo("id");
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> toolSchema = ((List<Map<String, Object>>) mcp.buildSchema().get("tools")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>) toolSchema.get("inputSchema"))
                .get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> idSchema = (Map<String, Object>) properties.get("id");
        assertThat(idSchema).containsEntry("type", "integer");
        assertThat(mcp.invoke("get_orders_by_id", Map.of("id", "42"))).isEqualTo("order-42");
    }

    @Test
    void tool_sharedRouteContract_keepsBodySchemaIntact() throws Exception {
        Map<String, Object> body = Map.of("type", "object",
                "properties", Map.of("name", Map.of("type", "string")));
        var route = Route.of("POST", "/orders", (McpHandler) ctx -> "created")
                .describedAs("Create order")
                .withReturnType("Order")
                .withParams(List.of(new ApiParameter("request", "CreateOrderRequest", "body",
                        true, "Order fields", body)));
        McpRouter mcp = new McpRouter().tool(route);

        @SuppressWarnings("unchecked")
        Map<String, Object> tool = ((List<Map<String, Object>>) mcp.buildSchema().get("tools")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>) tool.get("inputSchema"))
                .get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) properties.get("request");
        assertThat(request).containsEntry("type", "object");
        assertThat(request.get("description")).isEqualTo("Order fields");

        // The whole point of the shared contract: the declared source reaches the tool.
        assertThat(mcp.tools().get(0).params().get(0).contract().source()).isEqualTo("body");
    }

    @Test
    void tool_routeHandledByAnotherTransport_failsAndNamesTheRoute() {
        // A Route whose handler belongs to HTTP cannot be invoked as a tool. Accepting it and
        // ignoring its handler was how a tool published a contract whose behaviour was elsewhere.
        var httpRoute = Route.of("GET", "/orders", (io.aura.port.BaseHandler) ctx -> { });
        assertThatThrownBy(() -> new McpRouter().tool(httpRoute))
                .isInstanceOfSatisfying(ContractException.class, failure -> {
                    assertThat(failure.error().code()).isEqualTo("AURA_MCP_HANDLER_MISMATCH");
                    assertThat(failure.getMessage()).contains("GET /orders")
                            .contains("McpHandler").contains("withHandler");
                });
    }

    @Test
    void tool_paramTheJsonTableCannotRender_failsInsteadOfCallingItAString() {
        // The builder refused a complex argument at declaration; the shared contract cannot,
        // because a body parameter is exactly what a route carries. What it can refuse is a
        // parameter that has no rendering and no declared schema — the silent {"type":"string"}.
        var route = Route.of("POST", "/orders", (McpHandler) ctx -> "created")
                .withParams(List.of(new ApiParameter("user", "CreateOrderRequest", "body", true, "Order")));
        assertThatThrownBy(() -> new McpRouter().tool(route))
                .isInstanceOfSatisfying(ContractException.class, failure -> {
                    assertThat(failure.error().code()).isEqualTo("AURA_MCP_PARAM_SCHEMA_MISSING");
                    assertThat(failure.getMessage()).contains("'user' is declared as CreateOrderRequest")
                            .contains("tool POST /orders");
                });
    }

    @Test
    void permissionedToolFailsClosedAndAcceptsExplicitAuthorizedIdentity() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("DELETE", "/order", (McpHandler) ctx -> "deleted-" + ctx.getLong("id"))
                .describedAs("Delete order")
                .withParams(List.of(new ApiParameter("id", "long", "query", true, "Order ID")))
                .requireAuth("orders.delete"));

        assertThatThrownBy(() -> mcp.invoke("delete_order", Map.of("id", 7L)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> mcp.invoke("delete_order", Map.of("id", 7L), "user-1",
                (user, permission) -> false)).isInstanceOf(SecurityException.class);
        assertThat(mcp.invoke("delete_order", Map.of("id", 7L), "user-1",
                (user, permission) -> user.equals("user-1") && permission.equals("orders.delete")))
                .isEqualTo("deleted-7");

        @SuppressWarnings("unchecked")
        Map<String, Object> tool = ((List<Map<String, Object>>) mcp.buildSchema().get("tools")).get(0);
        assertThat(tool).containsEntry("x-aura-permission", "orders.delete");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tool_enumParam_projectsTheChoicesItsContractDeclares() {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/query", (McpHandler) ctx -> null)
                .describedAs("查询")
                .withParams(List.of(statusParam())));

        Map<String, Object> schema = mcp.buildSchema();
        Map<String, Object> inputSchema = (Map<String, Object>)
                ((List<Map<String, Object>>) schema.get("tools")).get(0).get("inputSchema");
        Map<String, Object> statusProp = (Map<String, Object>)
                ((Map<String, Object>) inputSchema.get("properties")).get("status");

        // MCP renders what the contract declared; it has no enum vocabulary of its own.
        assertThat(statusProp).containsEntry("type", "string");
        assertThat((List<String>) statusProp.get("enum")).containsExactly("NEW", "CANCEL", "PAID");
        assertThat(statusProp.get("description")).isEqualTo("订单状态");
    }

    @Test
    void tool_handlerOwnsTheLabelToCodeMapping() throws Exception {
        McpRouter mcp = new McpRouter();
        Map<String, String> cityCodes = Map.of("北京", "010", "上海", "021");
        mcp.tool(Route.of("POST", "/city", (McpHandler) ctx ->
                        cityCodes.getOrDefault(ctx.getString("city"), ctx.getString("city")))
                .describedAs("查询城市")
                .withParams(List.of(new ApiParameter("city", "String", "query", true, "城市",
                        Map.of("type", "string", "enum", List.of("北京", "上海"))))));

        assertThat(mcp.invoke("post_city", Map.of("city", "北京"))).isEqualTo("010");
        assertThat(mcp.invoke("post_city", Map.of("city", "深圳"))).isEqualTo("深圳");
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
        mcp.tool(Route.of("GET", "/user", (McpHandler) ctx -> svc.get(ctx.getInt("id"))));
        mcp.tool(Route.of("GET", "/users", (McpHandler) ctx -> svc.list()));

        assertThat(mcp.tools()).hasSize(2);
    }

    // --- 边界场景 ---

    @Test
    void tool_enumParam_invokeWithEnumName_resolvesCorrectly() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/query",
                (McpHandler) ctx -> ctx.getEnum("status", OrderStatus.class).code)
                .describedAs("查询")
                .withParams(List.of(statusParam())));

        Object result = mcp.invoke("post_query", Map.of("status", "PAID"));
        assertThat(result).isEqualTo(2);
    }

    @Test
    void tool_customHandler_multipleParams() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/add", (McpHandler) ctx -> ctx.getInt("a") + ctx.getInt("b"))
                .describedAs("加法")
                .withParams(List.of(new ApiParameter("a", "int", "query", true, "数字A"),
                        new ApiParameter("b", "int", "query", true, "数字B"))));

        Object result = mcp.invoke("post_add", Map.of("a", "3", "b", "7"));
        assertThat(result).isEqualTo(10);
    }

    @Test
    void tool_customHandler_missingParam_namesTheRequiredArgument() {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/inc", (McpHandler) ctx -> ctx.getInt("n") + 1)
                .describedAs("加一")
                .withParams(List.of(new ApiParameter("n", "int", "query", true, "数字"))));

        // The tool published n as required, so running it on an implied 1 was a result the
        // model never asked for.
        assertThatThrownBy(() -> mcp.invoke("post_inc", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required argument missing: n");
    }

    @Test
    void tool_missingBooleanParam_namesTheRequiredArgument() {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool(Route.of("POST", "/check", (McpHandler) ctx -> svc.check(ctx.getBoolean("active")))
                .describedAs("检查")
                .withParams(List.of(new ApiParameter("active", "boolean", "query", true, "是否激活"))));

        assertThatThrownBy(() -> mcp.invoke("post_check", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required argument missing: active");
    }

    @Test
    void tool_missingDoubleParam_namesTheRequiredArgument() {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool(Route.of("POST", "/rate", (McpHandler) ctx -> svc.rate(ctx.getDouble("value")))
                .describedAs("费率")
                .withParams(List.of(new ApiParameter("value", "double", "query", true, "金额"))));

        assertThatThrownBy(() -> mcp.invoke("post_rate", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required argument missing: value");
    }

    @Test
    void tool_optionalParamStillRunsWhileAbsent() throws Exception {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/echo", (McpHandler) ctx -> ctx.getString("msg"))
                .describedAs("回声")
                .withParams(List.of(new ApiParameter("msg", "String", "query", false, "消息"))));

        // A parameter the contract does not require is how a tool says null is a value it handles.
        assertThat(mcp.invoke("post_echo", Map.of())).isNull();
    }

    @Test
    void buildSchema_multipleTools_allPresent() {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool(Route.of("GET", "/user", (McpHandler) ctx -> svc.get(ctx.getInt("id")))
                .describedAs("获取用户"));
        mcp.tool(Route.of("GET", "/users", (McpHandler) ctx -> svc.list())
                .describedAs("用户列表"));

        Map<String, Object> schema = mcp.buildSchema();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) schema.get("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).get("name")).isEqualTo("get_user");
        assertThat(tools.get(1).get("name")).isEqualTo("get_users");
    }

    @Test
    void buildSchema_paramTypes_correctJsonTypes() {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/test", (McpHandler) ctx -> null)
                .describedAs("测试")
                .withParams(List.of(new ApiParameter("name", "String", "query", true, "名字"),
                        new ApiParameter("age", "int", "query", true, "年龄"),
                        new ApiParameter("active", "boolean", "query", true, "是否激活"))));

        // A tool parameter stores the type name it was declared with, exactly as a route parameter
        // does, and the wire document is where the Java-to-JSON decision appears -- once.
        McpTool tool = mcp.tools().get(0);
        assertThat(tool.params()).extracting(p -> p.contract().type())
                .containsExactly("String", "int", "boolean");

        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>)
                ((List<Map<String, Object>>) mcp.buildSchema().get("tools")).get(0).get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        assertThat(properties).extractingByKey("name").isInstanceOfSatisfying(Map.class,
                property -> assertThat(property.get("type")).isEqualTo("string"));
        assertThat(properties).extractingByKey("age").isInstanceOfSatisfying(Map.class,
                property -> assertThat(property.get("type")).isEqualTo("integer"));
        assertThat(properties).extractingByKey("active").isInstanceOfSatisfying(Map.class,
                property -> assertThat(property.get("type")).isEqualTo("boolean"));
        assertThat((List<String>) inputSchema.get("required")).containsExactly("name", "age", "active");
    }

    @Test
    void unregisteredRoutes_notInTools() {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool(Route.of("GET", "/user", (McpHandler) ctx -> svc.get(ctx.getInt("id")))
                .describedAs("获取用户")
                .withParams(List.of(new ApiParameter("id", "int", "query", true, "用户号"))));

        List<String> names = mcp.tools().stream().map(McpTool::name).toList();
        assertThat(names).containsExactly("get_user");
        assertThat(names).doesNotContain("get_users");
    }

    @Test
    void tool_listReturnsCollection() throws Exception {
        McpRouter mcp = new McpRouter();
        var svc = new UserService();
        mcp.tool(Route.of("GET", "/users", (McpHandler) ctx -> svc.list()).describedAs("用户列表"));

        Object result = mcp.invoke("get_users", Map.of());
        assertThat(result).isEqualTo(List.of("alice", "bob"));
    }

    @Test
    void tool_missingLongParam_namesTheRequiredArgument() {
        McpRouter mcp = new McpRouter();
        mcp.tool(Route.of("POST", "/ts", (McpHandler) ctx -> ctx.getLong("t"))
                .describedAs("时间戳")
                .withParams(List.of(new ApiParameter("t", "long", "query", true, "时间"))));

        assertThatThrownBy(() -> mcp.invoke("post_ts", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required argument missing: t");
    }

    @Test
    @SuppressWarnings("unchecked")
    void routeSecurityMetadataProtectsGeneratedTool() throws Exception {
        var route = Route.of("DELETE", "/orders/{id}", (McpHandler) ctx -> "deleted")
                .describedAs("Delete order")
                .withReturnType("void")
                .withParams(List.of(new ApiParameter("id", "long", "path", true, "Order ID")))
                .requireAuth("orders.delete");
        McpRouter mcp = new McpRouter().tool(route);

        Map<String, Object> schema = mcp.buildSchema();
        Map<String, Object> tool = ((List<Map<String, Object>>) schema.get("tools")).get(0);
        assertThat(tool).containsEntry("x-aura-authenticated", true)
                .containsEntry("x-aura-permission", "orders.delete");
        assertThatThrownBy(() -> mcp.invoke("delete_orders_by_id", Map.of("id", 1)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("authentication required");
        assertThatThrownBy(() -> mcp.invoke("delete_orders_by_id", Map.of("id", 1),
                "user-1", (user, permission) -> false))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("denied");
        assertThat(mcp.invoke("delete_orders_by_id", Map.of("id", 1),
                "user-1", (user, permission) -> permission.equals("orders.delete")))
                .isEqualTo("deleted");
    }
}

package io.aura.mcp;

import io.aura.contract.AccessPolicy;
import io.aura.contract.ApiParameter;
import io.aura.contract.Capability;
import io.aura.contract.CapabilityDefinition;
import io.aura.contract.CapabilityHandler;
import io.aura.contract.ContractException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCapabilityProjectionTest {

    private static final ApiParameter ID = new ApiParameter("id", "long", "query", true, "Order ID");

    private static CapabilityDefinition orderQuery(AccessPolicy access, CapabilityHandler handler) {
        return Capability.of("order.query", "Query an order", access, handler, ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectsTheSameDefinitionThroughTheOneSchemaFunnel() {
        CapabilityDefinition definition = orderQuery(AccessPolicy.permission("order.read"),
                (args, userId) -> "order-" + args.get("id"));
        McpRouter router = new McpRouter().tool(definition);

        assertThat(router.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("order_query");
            assertThat(tool.description()).isEqualTo("Query an order");
            assertThat(tool.authenticated()).isTrue();
            assertThat(tool.permission()).isEqualTo("order.read");
        });
        Map<String, Object> entry = (Map<String, Object>) ((List<Object>) router.buildSchema()
                .get("tools")).get(0);
        assertThat(entry.get("x-aura-capability")).isEqualTo("order.query");
        assertThat(entry.get("x-aura-authenticated")).isEqualTo(true);
        assertThat(entry.get("x-aura-permission")).isEqualTo("order.read");
        assertThat(entry.get("inputSchema")).isEqualTo(McpUtil.inputSchema(
                definition.params().stream().map(McpParam::new).toList()));
        assertThat(router.capabilityBindings()).containsExactlyEntriesOf(
                Map.of("order_query", "order.query"));
    }

    @Test
    void theDeclaredSchemaWinsOverAnyRederivation() {
        Map<String, Object> declared = Map.of("type", "string", "enum", List.of("NEW", "PAID"));
        CapabilityDefinition definition = Capability.of("order.query", null, AccessPolicy.PUBLIC,
                (args, userId) -> args.get("status"),
                new ApiParameter("status", "OrderStatus", "query", true, null, declared));
        McpRouter router = new McpRouter().tool(definition);

        McpWire.Property status = McpWire.of(router.buildSchema()).tool("order_query")
                .inputSchema().property("status");
        assertThat(status.type()).isEqualTo("string");
        assertThat(status.enumValues()).containsExactly("NEW", "PAID");
    }

    @Test
    void collidingToolNamesFailInsteadOfShadowing() {
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.PUBLIC, (args, userId) -> "a"));
        CapabilityDefinition collision = Capability.of("order_query", null, AccessPolicy.PUBLIC,
                (args, userId) -> "b");

        assertThatThrownBy(() -> router.tool(collision))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_MCP_TOOL_DUPLICATE"));
        assertThat(router.tools()).hasSize(1);
    }

    @Test
    void anUndescribableParameterFailsWithTheCapabilityContext() {
        CapabilityDefinition definition = Capability.of("order.query", null, AccessPolicy.PUBLIC,
                (args, userId) -> null,
                new ApiParameter("request", "CreateOrderRequest", "body", true, null));

        assertThatThrownBy(() -> new McpRouter().tool(definition))
                .isInstanceOfSatisfying(ContractException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("AURA_MCP_PARAM_SCHEMA_MISSING");
                    assertThat(e.error().message()).contains("capability order.query");
                });
    }

    @Test
    void publicCapabilityInvokesAnonymously() throws Exception {
        AtomicReference<String> identity = new AtomicReference<>("unset");
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.PUBLIC, (args, userId) -> {
            identity.set(userId);
            return "order-" + args.get("id");
        }));

        assertThat(router.invoke("order_query", Map.of("id", 42))).isEqualTo("order-42");
        assertThat(identity.get()).isNull();
    }

    @Test
    void authenticatedCapabilityRejectsAnonymousCalls() {
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.AUTHENTICATED,
                (args, userId) -> "order-" + args.get("id")));

        assertThatThrownBy(() -> router.invoke("order_query", Map.of("id", 42)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void permissionCapabilityWithoutAnAuthorizerFailsClosed() {
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.permission("order.read"),
                (args, userId) -> "order-" + args.get("id")));

        assertThatThrownBy(() -> router.invoke("order_query", Map.of("id", 42), "alice", null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void deniedPermissionFailsClosed() {
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.permission("order.read"),
                (args, userId) -> "order-" + args.get("id")));

        assertThatThrownBy(() -> router.invoke("order_query", Map.of("id", 42), "alice",
                (userId, permission) -> false))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void permittedInvocationReachesTheHandlerWithArgumentsAndIdentity() throws Exception {
        AtomicReference<Map<String, Object>> seenArgs = new AtomicReference<>();
        AtomicReference<String> seenUser = new AtomicReference<>();
        AtomicReference<String> checkedPermission = new AtomicReference<>();
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.permission("order.read"),
                (args, userId) -> {
                    seenArgs.set(args);
                    seenUser.set(userId);
                    return "order-" + args.get("id");
                }));

        Object result = router.invoke("order_query", Map.of("id", 42), "alice",
                (userId, permission) -> {
                    checkedPermission.set(permission);
                    return "alice".equals(userId) && "order.read".equals(permission);
                });

        assertThat(result).isEqualTo("order-42");
        assertThat(seenArgs.get()).containsExactlyEntriesOf(Map.of("id", 42));
        assertThat(seenUser.get()).isEqualTo("alice");
        assertThat(checkedPermission.get()).isEqualTo("order.read");
    }

    @Test
    void missingRequiredArgumentFailsBeforeTheHandlerRuns() {
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.PUBLIC, (args, userId) -> {
            ran.set(true);
            return null;
        }));

        assertThatThrownBy(() -> router.invoke("order_query", Map.of()))
                .isInstanceOf(McpMissingArgument.class);
        assertThat(ran.get()).isFalse();
    }
}
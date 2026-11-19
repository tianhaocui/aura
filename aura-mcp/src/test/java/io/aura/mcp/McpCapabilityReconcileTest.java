package io.aura.mcp;

import io.aura.capability.CapabilityRegistry;
import io.aura.contract.AccessPolicy;
import io.aura.contract.ApiParameter;
import io.aura.contract.Capability;
import io.aura.contract.CapabilityDefinition;
import io.aura.contract.ContractException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCapabilityReconcileTest {

    private static final ApiParameter ID = new ApiParameter("id", "long", "query", true, "Order ID");

    private static CapabilityDefinition orderQuery(AccessPolicy access) {
        return Capability.of("order.query", "Query an order", access,
                (args, userId) -> "order-" + args.get("id"), ID);
    }

    @Test
    void aFaithfulProjectionReconcilesClean() {
        CapabilityDefinition definition = orderQuery(AccessPolicy.permission("order.read"));
        McpRouter router = new McpRouter().tool(definition);
        CapabilityRegistry registry = new CapabilityRegistry().register(definition);

        assertThatCode(() -> router.reconcileCapabilities(registry)).doesNotThrowAnyException();
    }

    @Test
    void anUnprojectedCapabilityIsNotDrift() {
        CapabilityDefinition projected = orderQuery(AccessPolicy.PUBLIC);
        McpRouter router = new McpRouter().tool(projected);
        CapabilityRegistry registry = new CapabilityRegistry()
                .register(projected)
                .register(Capability.of("catalog.list", null, AccessPolicy.PUBLIC, (args, userId) -> List.of()));

        assertThatCode(() -> router.reconcileCapabilities(registry)).doesNotThrowAnyException();
    }

    @Test
    void aChangedParameterSchemaIsDrift() {
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.PUBLIC));
        CapabilityDefinition evolved = Capability.of("order.query", "Query an order", AccessPolicy.PUBLIC,
                (args, userId) -> null, new ApiParameter("id", "String", "query", true, "Order ID"));
        CapabilityRegistry registry = new CapabilityRegistry().register(evolved);

        assertThatThrownBy(() -> router.reconcileCapabilities(registry))
                .isInstanceOfSatisfying(ContractException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("AURA_MCP_PROJECTION_DRIFT");
                    assertThat(e.error().message()).contains("order_query").contains("inputSchema");
                });
    }

    @Test
    void aChangedAccessPolicyIsDrift() {
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.PUBLIC));
        CapabilityRegistry registry = new CapabilityRegistry()
                .register(orderQuery(AccessPolicy.permission("order.read")));

        assertThatThrownBy(() -> router.reconcileCapabilities(registry))
                .isInstanceOfSatisfying(ContractException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("AURA_MCP_PROJECTION_DRIFT");
                    assertThat(e.error().message()).contains("authenticated").contains("permission");
                });
    }

    @Test
    void aChangedDescriptionIsDrift() {
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.PUBLIC));
        CapabilityRegistry registry = new CapabilityRegistry().register(
                Capability.of("order.query", "A different description", AccessPolicy.PUBLIC,
                        (args, userId) -> null, ID));

        assertThatThrownBy(() -> router.reconcileCapabilities(registry))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().message()).contains("description"));
    }

    @Test
    void aBindingWithoutItsCapabilityIsDrift() {
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.PUBLIC));

        assertThatThrownBy(() -> router.reconcileCapabilities(new CapabilityRegistry()))
                .isInstanceOfSatisfying(ContractException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("AURA_MCP_PROJECTION_DRIFT");
                    assertThat(e.error().message()).contains("order.query").contains("not registered");
                });
    }

    @Test
    void anUndescribableEvolvedDefinitionIsDriftNotACrash() {
        McpRouter router = new McpRouter().tool(orderQuery(AccessPolicy.PUBLIC));
        CapabilityRegistry registry = new CapabilityRegistry().register(
                Capability.of("order.query", null, AccessPolicy.PUBLIC, (args, userId) -> null,
                        new ApiParameter("request", "CreateOrderRequest", "body", true, null)));

        assertThatThrownBy(() -> router.reconcileCapabilities(registry))
                .isInstanceOfSatisfying(ContractException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("AURA_MCP_PROJECTION_DRIFT");
                    assertThat(e.error().message()).contains("cannot render");
                });
    }
}
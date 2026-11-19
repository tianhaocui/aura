package io.aura.mcp;

import io.aura.contract.AccessPolicy;
import io.aura.contract.ApiParameter;
import io.aura.contract.Capability;
import io.aura.contract.CapabilityDefinition;
import io.aura.contract.ContractException;
import io.aura.contract.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema a tool advertised in tools/list is the contract its arguments are held to. The
 * boundary sits between authorization and the handler, on the one invoke chain both doors
 * share, so a value the schema says is impossible is refused by name — before any handler can
 * read it as something else.
 */
class McpSchemaValidationTest {

    private static ApiParameter idWithBounds() {
        return new ApiParameter("id", "long", "query", true, "Order ID",
                Map.of("type", "integer", "minimum", 1, "maximum", 100));
    }

    private static ApiParameter orderBody() {
        return new ApiParameter("order", "CreateOrderRequest", "body", true, "The order to create",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "item", Map.of("type", "string"),
                                "quantity", Map.of("type", "integer", "minimum", 1)),
                        "required", List.of("item")));
    }

    @Test
    void theBoundaryRefusesWhatTheSchemaSaysIsImpossibleBeforeTheHandlerRuns() {
        AtomicBoolean handlerRan = new AtomicBoolean(false);
        McpRouter router = new McpRouter().tool(Route.of("GET", "/order", (McpHandler) ctx -> {
            handlerRan.set(true);
            return "order";
        }).withParams(List.of(idWithBounds())));

        assertThatThrownBy(() -> router.invoke("get_order", Map.of("id", "not-a-number")))
                .isInstanceOfSatisfying(ContractException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("AURA_PARAM_SCHEMA_VIOLATION");
                    assertThat(e.error().message()).contains("id").contains("expected an integer");
                });
        assertThatThrownBy(() -> router.invoke("get_order", Map.of("id", -999)))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().message()).contains("below the declared minimum"));
        assertThat(handlerRan).isFalse();
    }

    @Test
    void whatTheSchemaAdmitsReachesTheHandlerUntouched() throws Exception {
        McpRouter router = new McpRouter().tool(Route.of("GET", "/order", (McpHandler) ctx ->
                "order-" + ctx.getLong("id")).withParams(List.of(idWithBounds())));

        assertThat(router.invoke("get_order", Map.of("id", "5"))).isEqualTo("order-5");
        assertThat(router.invoke("get_order", Map.of("id", 42))).isEqualTo("order-42");
    }

    @Test
    void theCapabilityDoorHoldsTheSameBoundary() throws Exception {
        CapabilityDefinition definition = Capability.of("order.query", "Query an order",
                AccessPolicy.PUBLIC, (args, userId) -> "order-" + args.get("id"),
                new ApiParameter("id", "long", "query", true, "Order ID",
                        Map.of("type", "integer", "maximum", 100)));
        McpRouter router = new McpRouter().tool(definition);

        assertThatThrownBy(() -> router.invoke("order_query", Map.of("id", 1000)))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_PARAM_SCHEMA_VIOLATION"));
        assertThat(router.invoke("order_query", Map.of("id", 7))).isEqualTo("order-7");
    }

    @Test
    void aNestedBodyHoldsItsOwnRequiredNamesAndConstraints() {
        AtomicBoolean handlerRan = new AtomicBoolean(false);
        McpRouter router = new McpRouter().tool(Route.of("POST", "/orders", (McpHandler) ctx -> {
            handlerRan.set(true);
            return "created";
        }).withParams(List.of(orderBody())));

        assertThatThrownBy(() -> router.invoke("post_orders", Map.of("order", Map.of("quantity", 2))))
                .isInstanceOfSatisfying(ContractException.class, e -> {
                    assertThat(e.error().code()).isEqualTo("AURA_PARAM_SCHEMA_VIOLATION");
                    assertThat(e.error().message()).contains("order.item");
                });
        assertThatThrownBy(() -> router.invoke("post_orders",
                Map.of("order", Map.of("item", "widget", "quantity", 0))))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().message()).contains("order.quantity"));
        assertThat(handlerRan).isFalse();
    }
}

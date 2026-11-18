package io.aura.capability;

import io.aura.contract.AccessPolicy;
import io.aura.contract.ApiParameter;
import io.aura.contract.Capability;
import io.aura.contract.CapabilityDefinition;
import io.aura.contract.CapabilityHandler;
import io.aura.contract.ContractException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityRegistryTest {

    private static final CapabilityHandler HANDLER = (args, userId) -> "ok";

    private static CapabilityDefinition orderQuery() {
        return Capability.of("order.query", "Query an order", AccessPolicy.permission("order.read"),
                HANDLER, new ApiParameter("id", "long", "query", true, "Order ID"));
    }

    @Test
    void registerGetContainsAndSnapshot() {
        CapabilityRegistry registry = new CapabilityRegistry();
        CapabilityDefinition definition = orderQuery();
        registry.register(definition);

        assertThat(registry.contains("order.query")).isTrue();
        assertThat(registry.get("order.query")).isSameAs(definition);
        assertThat(registry.snapshot()).containsExactlyEntriesOf(Map.of("order.query", definition));
    }

    @Test
    void duplicateNameFailsInsteadOfReplacing() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(orderQuery());
        CapabilityDefinition second = Capability.of("order.query", "Another order query",
                AccessPolicy.PUBLIC, HANDLER);

        assertThatThrownBy(() -> registry.register(second))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_CAPABILITY_DUPLICATE"));
        assertThat(registry.get("order.query").description()).isEqualTo("Query an order");
    }

    @Test
    void unknownNameFails() {
        assertThatThrownBy(() -> new CapabilityRegistry().get("order.missing"))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_CAPABILITY_UNKNOWN"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaIsADeclarationProjectionSortedByName() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(orderQuery());
        registry.register(Capability.of("catalog.list", "List the catalog", AccessPolicy.PUBLIC, HANDLER));
        registry.register(Capability.of("session.whoami", null, AccessPolicy.AUTHENTICATED, HANDLER));

        List<Map<String, Object>> capabilities =
                (List<Map<String, Object>>) registry.schema().get("capabilities");
        assertThat(capabilities).extracting(entry -> entry.get("name"))
                .containsExactly("catalog.list", "order.query", "session.whoami");

        Map<String, Object> orderQuery = capabilities.get(1);
        assertThat(orderQuery.get("description")).isEqualTo("Query an order");
        assertThat(orderQuery.get("access"))
                .isEqualTo(Map.of("policy", "PERMISSION", "permission", "order.read"));
        List<Map<String, Object>> params = (List<Map<String, Object>>) orderQuery.get("params");
        assertThat(params).singleElement().satisfies(param -> {
            assertThat(param.get("name")).isEqualTo("id");
            assertThat(param.get("type")).isEqualTo("long");
            assertThat(param.get("source")).isEqualTo("query");
            assertThat(param.get("required")).isEqualTo(true);
            assertThat(param.get("description")).isEqualTo("Order ID");
        });
        assertThat(capabilities.get(0).get("access")).isEqualTo(Map.of("policy", "PUBLIC"));
        assertThat(capabilities.get(2).get("access")).isEqualTo(Map.of("policy", "AUTHENTICATED"));
        assertThat(capabilities.get(2)).doesNotContainKey("description");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaRendersTheDeclaredParameterSchemaWithoutRederivingIt() {
        Map<String, Object> declared = Map.of("type", "string", "enum", List.of("NEW", "PAID"));
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(Capability.of("order.query", null, AccessPolicy.PUBLIC, HANDLER,
                new ApiParameter("status", "OrderStatus", "query", true, null, declared)));

        List<Map<String, Object>> capabilities =
                (List<Map<String, Object>>) registry.schema().get("capabilities");
        List<Map<String, Object>> params = (List<Map<String, Object>>) capabilities.get(0).get("params");
        assertThat(params.get(0).get("schema")).isEqualTo(declared);
        assertThat(params.get(0).get("type")).isEqualTo("OrderStatus");
    }
}
package io.aura.contract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityDefinitionTest {

    private static final CapabilityHandler HANDLER = (args, userId) -> "ok";

    @Test
    void ofDeclaresNameDescriptionParamsAccessAndHandler() {
        ApiParameter id = new ApiParameter("id", "long", "query", true, "Order ID");
        CapabilityDefinition definition = Capability.of("order.query", "Query an order",
                AccessPolicy.permission("order.read"), HANDLER, id);

        assertThat(definition.name()).isEqualTo("order.query");
        assertThat(definition.description()).isEqualTo("Query an order");
        assertThat(definition.params()).containsExactly(id);
        assertThat(definition.access()).isInstanceOf(AccessPolicy.Permission.class);
        assertThat(definition.handler()).isSameAs(HANDLER);
    }

    @Test
    void missingAccessPolicyFailsInsteadOfDefaultingToPublic() {
        assertThatThrownBy(() -> Capability.of("order.query", "Query an order", null, HANDLER))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_CAPABILITY_ACCESS_REQUIRED"));
    }

    @Test
    void invalidNamesFail() {
        for (String name : new String[]{null, " ", "order query", "order/query", "订单"}) {
            assertThatThrownBy(() -> Capability.of(name, null, AccessPolicy.PUBLIC, HANDLER))
                    .isInstanceOfSatisfying(ContractException.class, e ->
                            assertThat(e.error().code()).isEqualTo("AURA_CAPABILITY_NAME_INVALID"));
        }
    }

    @Test
    void missingHandlerFails() {
        assertThatThrownBy(() -> Capability.of("order.query", null, AccessPolicy.PUBLIC, null))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_CAPABILITY_HANDLER_MISSING"));
    }

    @Test
    void duplicateParameterNamesFail() {
        ApiParameter first = new ApiParameter("id", "long", "query", true, null);
        ApiParameter second = new ApiParameter("id", "String", "body", false, null);
        assertThatThrownBy(() -> Capability.of("order.query", null, AccessPolicy.PUBLIC, HANDLER,
                List.of(first, second)))
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_CAPABILITY_PARAM_DUPLICATE"));
    }

    @Test
    void paramsAreAnImmutableCopy() {
        List<ApiParameter> params = new ArrayList<>();
        params.add(new ApiParameter("id", "long", "query", true, null));
        CapabilityDefinition definition = Capability.of("order.query", null, AccessPolicy.PUBLIC,
                HANDLER, params);
        params.clear();

        assertThat(definition.params()).hasSize(1);
        assertThatThrownBy(() -> definition.params().add(
                new ApiParameter("extra", "String", "query", false, null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void declaredSchemaIsCarriedVerbatim() {
        Map<String, Object> schema = Map.of("type", "string", "enum", List.of("NEW", "PAID"));
        CapabilityDefinition definition = Capability.of("order.query", null, AccessPolicy.PUBLIC,
                HANDLER, new ApiParameter("status", "OrderStatus", "query", true, null, schema));

        assertThat(definition.params().get(0).schema()).isEqualTo(schema);
    }

    @Test
    void accessPolicyMapsToTheRouteVocabulary() {
        assertThat(AccessPolicy.PUBLIC.authenticated()).isFalse();
        assertThat(AccessPolicy.PUBLIC.permissionId()).isNull();
        assertThat(AccessPolicy.AUTHENTICATED.authenticated()).isTrue();
        assertThat(AccessPolicy.AUTHENTICATED.permissionId()).isNull();
        AccessPolicy permission = AccessPolicy.permission("order.read");
        assertThat(permission.authenticated()).isTrue();
        assertThat(permission.permissionId()).isEqualTo("order.read");
    }

    @Test
    void blankPermissionIdFails() {
        assertThatThrownBy(() -> AccessPolicy.permission(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
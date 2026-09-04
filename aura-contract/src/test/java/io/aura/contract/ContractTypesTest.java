package io.aura.contract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractTypesTest {
    @Test
    void metadataIsImmutableAndNormalizesIdentity() {
        var requirements = new ArrayList<>(List.of(ModuleRequirement.of(" db ")));
        var metadata = new ModuleMetadata(" orders ", " 1.2.0 ", SetSupport.copy(requirements), null)
                .capabilities(" persistence ");

        requirements.clear();

        assertThat(metadata.id()).isEqualTo("orders");
        assertThat(metadata.version()).isEqualTo("1.2.0");
        assertThat(metadata.requires()).extracting(ModuleRequirement::id).containsExactly("db");
        assertThat(metadata.capabilities()).containsExactly("persistence");
        assertThatThrownBy(() -> metadata.capabilities().add("cache"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void capabilityLookupProvidesOptionalAndRequiredAccess() {
        CapabilityLookup lookup = new CapabilityLookup() {
            @Override public <T> Optional<T> find(Class<T> type) {
                return type == String.class
                        ? Optional.of(type.cast("ready"))
                        : Optional.empty();
            }
        };

        assertThat(lookup.find(String.class)).contains("ready");
        assertThat(lookup.lookup(Integer.class)).isEmpty();
        assertThatThrownBy(() -> lookup.require(Integer.class))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void routeContributionAndErrorsDefensivelyCopyMetadata() {
        var routeMetadata = new HashMap<String, Object>();
        routeMetadata.put("auth", true);
        var route = new RouteContribution(" get ", "/orders", "handler", routeMetadata);
        routeMetadata.put("changed", true);

        var details = new HashMap<String, Object>();
        details.put("module", "orders");
        var error = new StructuredError(" AURA_ROUTE_FAILED ", "route failed", details);
        details.put("changed", true);

        assertThat(route.method()).isEqualTo("GET");
        assertThat(route.metadata()).containsEntry("auth", true).doesNotContainKey("changed");
        assertThat(error.code()).isEqualTo("AURA_ROUTE_FAILED");
        assertThat(error.details()).containsEntry("module", "orders").doesNotContainKey("changed");
        assertThat(new ContractException(error).error()).isEqualTo(error);
    }

    @Test
    void cancellationIsIdempotentAndObservable() {
        var token = new CancellationToken();

        assertThat(token.isCancelled()).isFalse();
        assertThat(token.cancel()).isTrue();
        assertThat(token.cancel()).isFalse();
        assertThat(token.isCancelled()).isTrue();
        assertThatThrownBy(token::throwIfCancelled).isInstanceOf(CancellationException.class);
    }

    @Test
    void apiRouteContractsAreImmutableAndNormalized() {
        var route = new ApiRoute(" get ", "/orders", "List orders", "List<Order>",
                List.of(new ApiParameter("page", "int", "query", false, "Page number")), "curl /orders");
        assertThat(route.method()).isEqualTo("GET");
        assertThat(route.params()).singleElement().extracting(ApiParameter::name).isEqualTo("page");
    }

    @Test
    void permissionRegistryRejectsDuplicatesAndPreservesRegistrationOrder() {
        PermissionRegistry registry = new PermissionRegistry()
                .register("order.read", "Read orders")
                .register("order.write", "Write orders");

        assertThat(registry.list()).extracting(Permission::id)
                .containsExactly("order.read", "order.write");
        assertThatThrownBy(() -> registry.register("order.read", "Duplicate"))
                .isInstanceOf(ContractException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void optionalMcpIntegrationUsesTheCoreFreeContract() {
        assertThat(McpRouterSpec.class.getPackageName()).isEqualTo("io.aura.contract");
    }

    @Test
    void runtimeStateHasStableLifecycleOrder() {
        assertThat(RuntimeState.values())
                .containsExactly(RuntimeState.NEW, RuntimeState.STARTING, RuntimeState.RUNNING,
                        RuntimeState.STOPPING, RuntimeState.STOPPED);
    }

    private static final class SetSupport {
        private static java.util.Set<ModuleRequirement> copy(List<ModuleRequirement> values) {
            return new java.util.LinkedHashSet<>(values);
        }
    }
}

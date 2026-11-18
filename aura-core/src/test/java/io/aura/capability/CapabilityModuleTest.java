package io.aura.capability;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.ModuleException;
import io.aura.contract.AccessPolicy;
import io.aura.contract.ApiParameter;
import io.aura.contract.Capability;
import io.aura.contract.ContractException;
import io.aura.contract.ModuleMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityModuleTest {

    /** A business module that contributes one capability the explicit way. */
    private static AuraModule orderModule(String capabilityName) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("orders", "1.0.0").requires("capability");
            }

            @Override public void install(ModuleContext context) {
                context.require(CapabilityRegistry.class).register(Capability.of(
                        capabilityName, "Query an order", AccessPolicy.permission("order.read"),
                        (args, userId) -> "order-" + args.get("id"),
                        new ApiParameter("id", "long", "query", true, "Order ID")));
            }
        };
    }

    @Test
    void preparePopulatesTheRegistryWithoutOpeningAListener() {
        AuraApp app = AuraApp.create().use(orderModule("order.query")).use(CapabilityModule.create());
        app.prepare();

        CapabilityRegistry registry = app.require(CapabilityRegistry.class);
        assertThat(registry.contains("order.query")).isTrue();
        assertThat(registry.get("order.query").access().permissionId()).isEqualTo("order.read");
    }

    @Test
    void theModuleOwnsTheOneRegistryItRegisters() {
        CapabilityRegistry registry = new CapabilityRegistry();
        CapabilityModule module = CapabilityModule.create(registry);
        assertThat(module.registry()).isSameAs(registry);

        AuraApp app = AuraApp.create().use(module);
        app.prepare();
        assertThat(app.require(CapabilityRegistry.class)).isSameAs(registry);
    }

    @Test
    void aSecondCapabilityModuleFailsDuringOrdering() {
        assertThatThrownBy(() -> AuraApp.create()
                .use(CapabilityModule.create()).use(CapabilityModule.create()).prepare())
                .isInstanceOf(ModuleException.class)
                .hasMessageContaining("Duplicate module id: capability");
    }

    @Test
    void duplicateCapabilityNamesAcrossModulesFailAtPrepare() {
        assertThatThrownBy(() -> AuraApp.create()
                .use(CapabilityModule.create())
                .use(orderModule("order.query"))
                .use(new AuraModule() {
                    @Override public ModuleMetadata descriptor() {
                        return ModuleMetadata.of("orders-v2", "1.0.0").requires("capability");
                    }

                    @Override public void install(ModuleContext context) {
                        context.require(CapabilityRegistry.class).register(Capability.of(
                                "order.query", null, AccessPolicy.PUBLIC, (args, userId) -> "other"));
                    }
                })
                .prepare())
                .isInstanceOfSatisfying(ContractException.class, e ->
                        assertThat(e.error().code()).isEqualTo("AURA_CAPABILITY_DUPLICATE"));
    }

    @Test
    void aBusinessModuleDeclaringTheRequirementWithoutTheModuleFailsDeterministically() {
        assertThatThrownBy(() -> AuraApp.create().use(orderModule("order.query")).prepare())
                .isInstanceOf(ModuleException.class)
                .hasMessageContaining("requires capability");
    }
}
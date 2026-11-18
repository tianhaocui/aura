package io.aura.capability;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;

import java.util.Objects;

/**
 * Owns the one shared {@link CapabilityRegistry} and registers it at install time.
 *
 * <p>A business module contributes capabilities by declaring {@code requires("capability")} in
 * its descriptor — which is what orders this module first — and then, in its own install:
 * {@code context.require(CapabilityRegistry.class).register(Capability.of(...))}.
 *
 * <p>Ownership is singular by construction: a second {@code CapabilityModule} fails with
 * {@code AURA_MODULE_DUPLICATE} during graph ordering, and the type registry refuses a second
 * {@code CapabilityRegistry} instance. The registry holds declarations only; it owns no
 * resources, so there is nothing to start or stop, and {@code app.prepare()} populates it
 * without opening a listener.
 */
public final class CapabilityModule implements AuraModule {
    private final CapabilityRegistry registry;

    private CapabilityModule(CapabilityRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public static CapabilityModule create() { return new CapabilityModule(new CapabilityRegistry()); }

    public static CapabilityModule create(CapabilityRegistry registry) {
        return new CapabilityModule(registry);
    }

    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("capability", "1.0.0").provides("capability.registry");
    }

    @Override public void install(ModuleContext context) {
        context.register(registry);
    }

    public CapabilityRegistry registry() { return registry; }
}
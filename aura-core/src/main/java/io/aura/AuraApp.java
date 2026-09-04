package io.aura;

import java.util.List;
import java.util.Optional;

/** Canonical Aura 1.0 application facade for explicit module composition. */
public final class AuraApp implements AutoCloseable {
    private final AuraRuntime runtime;

    private AuraApp(AuraConfig config) {
        this.runtime = new ModularRuntime(config == null ? new AuraConfig() : config);
    }

    public static AuraApp create() { return new AuraApp(new AuraConfig()); }
    public static AuraApp create(AuraConfig config) { return new AuraApp(config); }

    public AuraApp use(AuraModule module) {
        runtime.use(module);
        return this;
    }

    public AuraApp observe(AuraEventListener listener) {
        runtime.observe(listener);
        return this;
    }

    public AuraApp healthCheck(io.aura.contract.HealthCheck check) {
        runtime.healthCheck(check);
        return this;
    }

    public AuraApp authorize(java.util.function.BiPredicate<String, String> checker) {
        runtime.authorize(checker);
        return this;
    }

    public AuraApp authenticate(java.util.function.Function<io.aura.web.BaseContext, String> authenticator) {
        runtime.authenticate(authenticator);
        return this;
    }

    public AuraApp property(String key, String value) {
        String prefix = "aura.";
        if (key == null || !key.startsWith(prefix)) {
            throw new IllegalArgumentException("AuraApp properties must use the aura.<module>.* namespace");
        }
        runtime.property(key, value);
        return this;
    }

    public AuraApp start() {
        runtime.start();
        return this;
    }

    public AuraApp start(String... args) {
        runtime.start(args == null ? new String[0] : args);
        return this;
    }

    /** Installs modules for in-memory verification without opening listeners. */
    public AuraApp prepare() {
        runtime.prepareModules();
        return this;
    }

    public void stop() { runtime.stop(); }
    @Override public void close() { stop(); }

    public io.aura.contract.RuntimeState state() { return runtime.state(); }
    /** Stable runtime view for adapters; it does not expose legacy Builder APIs. */
    public AuraRuntime runtimeView() { return runtime; }
    public boolean isRunning() { return runtime.isRunning(); }
    public AuraConfig config() { return runtime.config(); }
    public io.aura.contract.PermissionRegistry permissions() { return runtime.permissions(); }
    public List<io.aura.contract.ModuleMetadata> modules() { return runtime.moduleDescriptors(); }
    public <T> Optional<T> find(Class<T> type) { return runtime.find(type); }
    public <T> T require(Class<T> type) {
        return runtime.require(type);
    }
}

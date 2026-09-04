package io.aura;

import java.util.List;
import java.util.Optional;

/** Canonical Aura 1.0 application facade for explicit module composition. */
public final class AuraApp implements AutoCloseable {
    private final Aura runtime;

    private AuraApp(AuraConfig config) {
        this.runtime = Aura.create(config == null ? new AuraConfig() : config);
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

    public AuraApp property(String key, String value) {
        String prefix = "aura.";
        if (key == null || !key.startsWith(prefix)) {
            throw new IllegalArgumentException("AuraApp properties must use the aura.<module>.* namespace");
        }
        runtime.set(key, value);
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

    public Aura.Lifecycle lifecycle() { return runtime.lifecycle(); }
    public AuraConfig config() { return runtime.config(); }
    public io.aura.contract.PermissionRegistry permissions() { return runtime.permissions(); }
    public List<io.aura.contract.ModuleMetadata> modules() { return runtime.moduleDescriptors(); }
    public <T> Optional<T> find(Class<T> type) { return Optional.ofNullable(runtime.getBean(type)); }
    public <T> T require(Class<T> type) {
        T value = runtime.getBean(type);
        if (value == null) throw new IllegalStateException("Aura capability is not installed: " + type.getName());
        return value;
    }
    /**
     * @deprecated Use {@link #prepare()}, {@link #find(Class)}, or {@link #require(Class)}.
     * This escape hatch will be removed after the 1.0 runtime split.
     */
    @Deprecated
    public Aura runtime() { return runtime; }
}

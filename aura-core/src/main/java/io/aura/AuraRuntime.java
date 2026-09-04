package io.aura;

import io.aura.contract.ModuleMetadata;
import io.aura.contract.PermissionRegistry;
import io.aura.contract.RuntimeState;

import java.util.List;
import java.util.Optional;

/** Replaceable 1.0 runtime surface used by AuraApp and adapters. */
public interface AuraRuntime extends AutoCloseable {
    RuntimeState state();
    AuraConfig config();
    AuraRuntime use(AuraModule module);
    AuraRuntime observe(AuraEventListener listener);
    AuraRuntime healthCheck(io.aura.contract.HealthCheck check);
    AuraRuntime authorize(java.util.function.BiPredicate<String, String> checker);
    AuraRuntime authenticate(java.util.function.Function<io.aura.web.BaseContext, String> authenticator);
    AuraRuntime property(String key, String value);
    void prepareModules();
    void start();
    void start(String[] args);
    void stop();
    List<ModuleMetadata> moduleDescriptors();
    PermissionRegistry permissions();
    <T> Optional<T> find(Class<T> type);

    default boolean isRunning() { return state() == RuntimeState.RUNNING; }

    default <T> T require(Class<T> type) {
        return find(type).orElseThrow(() ->
                new IllegalStateException("Aura capability is not installed: " + type.getName()));
    }

    @Override default void close() { stop(); }
}

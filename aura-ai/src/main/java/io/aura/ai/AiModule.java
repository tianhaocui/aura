package io.aura.ai;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.ModuleDescriptor;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Explicit Aura module that contributes one provider-neutral AiClient. */
public final class AiModule implements AuraModule {
    private final AiClient client;

    private AiModule(AiClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public static AiModule create(AiProvider provider) {
        return new AiModule(new AiClient(provider));
    }

    public static AiModule create(AiProvider provider, ExecutorService executor) {
        return new AiModule(new AiClient(provider, executor));
    }

    @Override public ModuleDescriptor descriptor() {
        return ModuleDescriptor.of("ai", "1.0.0").build();
    }

    @Override public void install(ModuleContext context) {
        context.register(client);
    }

    @Override public void stop(ModuleContext context) {
        client.close();
    }

    public AiClient client() { return client; }
}

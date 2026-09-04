package io.aura;

/** A self-contained, explicitly installed Aura capability or business module. */
public interface AuraModule {
    io.aura.contract.ModuleMetadata descriptor();

    void install(ModuleContext context);

    default void start(ModuleContext context) throws Exception { }

    default void stop(ModuleContext context) throws Exception { }
}

package io.aura;

public interface AuraStarter {
    void start(AuraWebRuntime app);
    void stop();
    default void reloadRoutes(AuraWebRuntime app) {
        throw new UnsupportedOperationException("Hot reload not supported by this starter");
    }
}

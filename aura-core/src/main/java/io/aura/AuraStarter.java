package io.aura;

public interface AuraStarter {
    void start(Aura app);
    void stop();
    default void reloadRoutes(Aura app) {
        throw new UnsupportedOperationException("Hot reload not supported by this starter");
    }
}

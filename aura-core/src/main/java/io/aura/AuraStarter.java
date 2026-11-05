package io.aura;

public interface AuraStarter {
    void start(AuraWebRuntime app);
    void stop();
    /**
     * The listening port the adapter actually bound, or {@code -1} when it has none.
     * Distinct from the configured port when that port is {@code 0}.
     */
    default int boundPort() { return -1; }
    default void reloadRoutes(AuraWebRuntime app) {
        throw new UnsupportedOperationException("Hot reload not supported by this starter");
    }
}

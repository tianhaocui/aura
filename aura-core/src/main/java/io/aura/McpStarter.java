package io.aura;

public interface McpStarter {
    void start(AuraMcpRuntime app);
    void startStdio(AuraMcpRuntime app);
    void stop();
}

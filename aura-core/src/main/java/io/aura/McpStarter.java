package io.aura;

public interface McpStarter {
    void start(Aura app);
    void startStdio(Aura app);
    void stop();
}

package io.aura.db;

@FunctionalInterface
public interface ThrowingRunnable {
    void run() throws Exception;
}

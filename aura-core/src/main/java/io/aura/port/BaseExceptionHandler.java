package io.aura.port;

@FunctionalInterface
public interface BaseExceptionHandler<T extends Exception> {
    void handle(T exception, BaseContext ctx);
}

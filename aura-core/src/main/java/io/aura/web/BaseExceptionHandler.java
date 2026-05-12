package io.aura.web;

@FunctionalInterface
public interface BaseExceptionHandler<T extends Exception> {
    void handle(T exception, BaseContext ctx);
}

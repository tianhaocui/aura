package io.aura.web;

@FunctionalInterface
public interface ExceptionHandler<T extends Exception> {
    void handle(T exception, Context ctx);
}

package io.aura.db;

@FunctionalInterface
public interface ThrowingSupplier<T> {
    T get() throws Exception;
}

package io.aura.db;

@FunctionalInterface
public interface ThrowingTransactionFunction<T> {
    T apply(TransactionContext context) throws Exception;
}

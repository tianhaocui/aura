package io.aura.db;

/** Handle for a manually controlled transaction. */
public interface TransactionHandle extends AutoCloseable {
    TransactionContext context();
    boolean isActive();
    void commit();
    void rollback();

    @Override
    void close();
}

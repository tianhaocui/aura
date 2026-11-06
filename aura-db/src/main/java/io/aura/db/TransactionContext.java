package io.aura.db;

import java.sql.Connection;

/** The database and JDBC connection bound to the current transaction. */
public final class TransactionContext {
    private final Db db;
    private final Connection connection;

    TransactionContext(Db db, Connection connection) {
        this.db = db;
        this.connection = connection;
    }

    public Db db() {
        return db;
    }

    public Connection connection() {
        return connection;
    }
}

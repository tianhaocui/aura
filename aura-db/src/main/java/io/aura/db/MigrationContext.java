package io.aura.db;

import java.util.Objects;
import javax.sql.DataSource;

/** Resources supplied to a migration adapter during application startup. */
public record MigrationContext(Db db, DataSource dataSource, String databaseName) {
    public MigrationContext {
        db = Objects.requireNonNull(db, "db");
        dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("databaseName must not be blank");
        }
        databaseName = databaseName.trim();
    }
}

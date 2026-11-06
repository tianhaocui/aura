package io.aura.db;

import java.util.List;

/** Supplies versioned SQL inputs to the lightweight {@link SqlSchemaMigrator}. */
@FunctionalInterface
public interface SqlMigrationSource {
    List<SqlMigration> migrations();
}

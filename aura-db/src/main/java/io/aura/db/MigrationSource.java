package io.aura.db;

import java.util.List;

/** Supplies migrations in any storage format; the runner sorts them by version. */
@FunctionalInterface
public interface MigrationSource {
    List<Migration> migrations();
}

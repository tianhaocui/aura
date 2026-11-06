package io.aura.db;

import java.util.List;

record SqlMigrationResult(List<String> applied, List<String> skipped) {
    SqlMigrationResult {
        applied = List.copyOf(applied);
        skipped = List.copyOf(skipped);
    }

    int appliedCount() {
        return applied.size();
    }
}

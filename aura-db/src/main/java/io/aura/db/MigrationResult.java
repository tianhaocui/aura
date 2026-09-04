package io.aura.db;

import java.util.List;

public record MigrationResult(List<String> applied, List<String> skipped) {
    public MigrationResult {
        applied = List.copyOf(applied);
        skipped = List.copyOf(skipped);
    }

    public int appliedCount() {
        return applied.size();
    }
}

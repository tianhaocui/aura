package io.aura.db;

/** Provider-neutral summary returned after a schema migrator completes successfully. */
public record SchemaMigrationResult(String migrator, int appliedCount) {
    public SchemaMigrationResult {
        if (migrator == null || migrator.isBlank()) {
            throw new IllegalArgumentException("migrator must not be blank");
        }
        if (appliedCount < 0) throw new IllegalArgumentException("appliedCount must not be negative");
        migrator = migrator.trim();
    }
}

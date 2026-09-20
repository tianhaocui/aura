package io.aura.db;

import java.util.Objects;

/** One versioned SQL input consumed only by {@link SqlSchemaMigrator}. */
public record SqlMigration(String version, String name, String sql, String checksum) {
    public SqlMigration {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        Objects.requireNonNull(sql, "sql");
        if (checksum == null || checksum.isBlank()) checksum = MigrationChecksums.sha256(sql);
    }

    public SqlMigration(String version, String name, String sql) {
        this(version, name, sql, MigrationChecksums.sha256(sql));
    }
}

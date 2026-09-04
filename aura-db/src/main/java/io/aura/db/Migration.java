package io.aura.db;

import java.util.Objects;

/** A single ordered schema migration. */
public record Migration(String version, String name, String sql, String checksum) {
    public Migration {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        Objects.requireNonNull(sql, "sql");
        if (checksum == null || checksum.isBlank()) checksum = MigrationChecksums.sha256(sql);
    }

    public Migration(String version, String name, String sql) {
        this(version, name, sql, MigrationChecksums.sha256(sql));
    }
}

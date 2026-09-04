package io.aura;

import java.util.Objects;

/** A required module identified by stable id and optional version range text. */
public record ModuleRequirement(String id, String versionRange) {
    public ModuleRequirement {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("module requirement id must not be blank");
        id = id.trim();
        versionRange = versionRange == null || versionRange.isBlank() ? null : versionRange.trim();
    }

    public static ModuleRequirement of(String id) { return new ModuleRequirement(id, null); }

    public io.aura.contract.ModuleRequirement contract() {
        return new io.aura.contract.ModuleRequirement(id, versionRange);
    }
}

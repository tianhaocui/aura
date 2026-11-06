package io.aura.contract;

/** A module dependency identified by stable id and an optional version range. */
public record ModuleRequirement(String id, String versionRange) {
    public ModuleRequirement {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("module requirement id must not be blank");
        }
        id = id.trim();
        versionRange = versionRange == null || versionRange.isBlank() ? null : versionRange.trim();
    }

    public static ModuleRequirement of(String id) {
        return new ModuleRequirement(id, null);
    }
}

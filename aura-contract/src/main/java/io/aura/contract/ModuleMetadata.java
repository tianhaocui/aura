package io.aura.contract;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable identity, dependency, and capability metadata for an Aura module. */
public record ModuleMetadata(String id, String version, Set<ModuleRequirement> requires,
                             Set<String> capabilities) {
    public ModuleMetadata {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("module id must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("module version must not be blank");
        }
        id = id.trim();
        version = version.trim();
        requires = immutableSet(requires, "module requirement");
        capabilities = immutableStrings(capabilities, "capability");
    }

    public ModuleMetadata(String id, String version) {
        this(id, version, Set.of(), Set.of());
    }

    public static ModuleMetadata of(String id, String version) {
        return new ModuleMetadata(id, version);
    }

    public ModuleMetadata requires(ModuleRequirement... requirements) {
        var values = new LinkedHashSet<>(requires);
        if (requirements != null) {
            for (ModuleRequirement requirement : requirements) {
                if (requirement == null) {
                    throw new IllegalArgumentException("module requirement must not be null");
                }
                values.add(requirement);
            }
        }
        return new ModuleMetadata(id, version, values, capabilities);
    }

    public ModuleMetadata requires(String... ids) {
        if (ids == null) return this;
        ModuleRequirement[] requirements = new ModuleRequirement[ids.length];
        for (int i = 0; i < ids.length; i++) requirements[i] = ModuleRequirement.of(ids[i]);
        return requires(requirements);
    }

    public ModuleMetadata requires(String id, String versionRange) {
        return requires(new ModuleRequirement(id, versionRange));
    }

    public ModuleMetadata capabilities(String... names) {
        var values = new LinkedHashSet<>(capabilities);
        if (names != null) {
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("capability name must not be blank");
                }
                values.add(name.trim());
            }
        }
        return new ModuleMetadata(id, version, requires, values);
    }

    public ModuleMetadata provides(String... names) {
        return capabilities(names);
    }

    private static Set<ModuleRequirement> immutableSet(Set<ModuleRequirement> values, String label) {
        var copy = new LinkedHashSet<ModuleRequirement>();
        if (values != null) {
            for (ModuleRequirement value : values) {
                if (value == null) {
                    throw new IllegalArgumentException(label + " must not be null");
                }
                copy.add(value);
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Set<String> immutableStrings(Set<String> values, String label) {
        var copy = new LinkedHashSet<String>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(label + " name must not be blank");
                }
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(copy);
    }
}

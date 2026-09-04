package io.aura;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;

/** Stable metadata used to validate and order explicitly installed modules. */
public record ModuleDescriptor(String id, String version, Set<ModuleRequirement> requires, Set<String> provides) {
    public ModuleDescriptor(String id, String version, Set<ModuleRequirement> requires) {
        this(id, version, requires, Set.of());
    }

    public ModuleDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("module id must not be blank");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("module version must not be blank");
        id = id.trim();
        version = version.trim();
        requires = requires == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(requires));
        provides = provides == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(provides));
    }

    public static Builder of(String id, String version) { return new Builder(id, version); }

    public io.aura.contract.ModuleMetadata contract() {
        return new io.aura.contract.ModuleMetadata(id, version,
                requires.stream().map(ModuleRequirement::contract).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                provides);
    }

    public static final class Builder {
        private final String id;
        private final String version;
        private final Set<ModuleRequirement> requires = new LinkedHashSet<>();
        private final Set<String> provides = new LinkedHashSet<>();

        private Builder(String id, String version) {
            this.id = id;
            this.version = version;
        }

        public Builder requires(String... ids) {
            if (ids != null) Arrays.stream(ids).map(ModuleRequirement::of).forEach(requires::add);
            return this;
        }

        public Builder requires(String id, String versionRange) {
            requires.add(new ModuleRequirement(id, versionRange));
            return this;
        }

        public Builder provides(String... capabilities) {
            if (capabilities != null) {
                for (String capability : capabilities) {
                    if (capability == null || capability.isBlank()) {
                        throw new IllegalArgumentException("capability must not be blank");
                    }
                    provides.add(capability.trim());
                }
            }
            return this;
        }

        public ModuleDescriptor build() { return new ModuleDescriptor(id, version, requires, provides); }
    }
}

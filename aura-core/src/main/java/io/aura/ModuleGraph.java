package io.aura;

import io.aura.contract.ModuleRequirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Pure module dependency graph validation and deterministic topological ordering. */
final class ModuleGraph {
    private ModuleGraph() { }

    static List<AuraModule> order(List<AuraModule> modules) {
        return order(modules, AuraModule::descriptor);
    }

    static List<AuraModule> order(List<AuraModule> modules,
                                  Function<AuraModule, io.aura.contract.ModuleMetadata> descriptors) {
        Map<String, AuraModule> byId = new LinkedHashMap<>();
        for (AuraModule module : modules) {
            io.aura.contract.ModuleMetadata descriptor = descriptors.apply(module);
            if (byId.putIfAbsent(descriptor.id(), module) != null) {
                throw new ModuleException("AURA_MODULE_DUPLICATE", descriptor.id(),
                        "Duplicate module id: " + descriptor.id());
            }
            try {
                SemVer.parse(descriptor.version());
            } catch (IllegalArgumentException invalid) {
                throw new ModuleException("AURA_MODULE_VERSION_INVALID", descriptor.id(),
                        "Module " + descriptor.id() + " declares an invalid version: " + invalid.getMessage(),
                        invalid);
            }
        }
        List<AuraModule> sorted = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (AuraModule module : modules) {
            orderModule(module, byId, visiting, visited, sorted, descriptors);
        }
        return sorted;
    }

    private static void orderModule(AuraModule module, Map<String, AuraModule> byId,
                                    Set<String> visiting, Set<String> visited,
                                    List<AuraModule> sorted,
                                    Function<AuraModule, io.aura.contract.ModuleMetadata> descriptors) {
        io.aura.contract.ModuleMetadata descriptor = descriptors.apply(module);
        String id = descriptor.id();
        if (visited.contains(id)) return;
        if (!visiting.add(id)) {
            throw new ModuleException("AURA_MODULE_DEPENDENCY_CYCLE", id,
                    "Circular module dependency involving " + String.join(" -> ", visiting) + " -> " + id);
        }
        for (ModuleRequirement requirement : descriptor.requires()) {
            AuraModule dependency = byId.get(requirement.id());
            if (dependency == null) {
                throw new ModuleException("AURA_MODULE_DEPENDENCY_MISSING", id,
                        "Module " + id + " requires " + requirement.id());
            }
            if (requirement.versionRange() != null) {
                boolean matches;
                try {
                    matches = versionMatches(descriptors.apply(dependency).version(), requirement.versionRange());
                } catch (IllegalArgumentException invalid) {
                    throw new ModuleException("AURA_MODULE_VERSION_INVALID", id,
                            "Module " + id + " requires " + requirement.id() + " " + requirement.versionRange()
                                    + " but the version constraint could not be evaluated: " + invalid.getMessage(),
                            invalid);
                }
                if (!matches) {
                    throw new ModuleException("AURA_MODULE_VERSION_MISMATCH", id,
                            "Module " + id + " requires " + requirement.id() + " " + requirement.versionRange()
                                    + " but found " + descriptors.apply(dependency).version());
                }
            }
            orderModule(dependency, byId, visiting, visited, sorted, descriptors);
        }
        visiting.remove(id);
        visited.add(id);
        sorted.add(module);
    }

    /**
     * Matches a module version against a constraint. Supported forms: {@code *},
     * {@code MAJOR.*}, {@code MAJOR.MINOR.*}, exact {@code X.Y.Z}, caret {@code ^X.Y.Z},
     * tilde {@code ~X.Y.Z}, and bracket ranges such as {@code [1.0,2.0)}.
     * Invalid versions or constraints throw {@link IllegalArgumentException} instead of
     * silently degrading to {@code 0.0.0}.
     */
    static boolean versionMatches(String version, String range) {
        SemVer actual = SemVer.parse(version);
        if (range == null || range.isBlank() || "*".equals(range.trim())) return true;
        String wanted = range.trim();
        if (wanted.startsWith("[") || wanted.startsWith("(")) {
            int comma = wanted.indexOf(',');
            if (comma < 1 || !wanted.endsWith("]") && !wanted.endsWith(")")) {
                throw new IllegalArgumentException("Invalid version range: " + range);
            }
            String lower = wanted.substring(1, comma).trim();
            String upper = wanted.substring(comma + 1, wanted.length() - 1).trim();
            boolean lowerOk = lower.isEmpty() || actual.compareTo(SemVer.parse(lower))
                    >= (wanted.charAt(0) == '[' ? 0 : 1);
            boolean upperOk = upper.isEmpty() || actual.compareTo(SemVer.parse(upper))
                    <= (wanted.charAt(wanted.length() - 1) == ']' ? 0 : -1);
            return lowerOk && upperOk;
        }
        if (wanted.startsWith("^")) {
            SemVer lower = SemVer.parse(wanted.substring(1));
            if (actual.compareTo(lower) < 0) return false;
            if (lower.major > 0) return actual.major == lower.major;
            if (lower.minor > 0) return actual.major == 0 && actual.minor == lower.minor;
            return actual.major == 0 && actual.minor == 0 && actual.patch == lower.patch;
        }
        if (wanted.startsWith("~")) {
            SemVer lower = SemVer.parse(wanted.substring(1));
            return actual.compareTo(lower) >= 0 && actual.major == lower.major && actual.minor == lower.minor;
        }
        if (wanted.endsWith(".*")) {
            String prefix = wanted.substring(0, wanted.length() - 2);
            String[] components = prefix.split("\\.", -1);
            if (components.length > 2) {
                throw new IllegalArgumentException("Invalid version range: " + range);
            }
            SemVer bound = SemVer.parse(prefix);
            if (actual.major != bound.major) return false;
            return components.length == 1 || actual.minor == bound.minor;
        }
        return actual.compareTo(SemVer.parse(wanted)) == 0;
    }

    /**
     * Strict release-version parser: {@code MAJOR[.MINOR[.PATCH]]} with ASCII-digit
     * components and no leading zeros. Omitted minor/patch default to zero so range
     * bounds like {@code [1.0,2.0)} keep their meaning. Prerelease and build metadata
     * are not supported and are rejected rather than silently stripped.
     */
    private static final class SemVer implements Comparable<SemVer> {
        private final int major;
        private final int minor;
        private final int patch;

        private SemVer(int major, int minor, int patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        private static SemVer parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("version must not be blank");
            }
            String[] parts = value.trim().split("\\.", -1);
            if (parts.length > 3) {
                throw new IllegalArgumentException(
                        "Invalid version '" + value + "': expected MAJOR[.MINOR[.PATCH]]");
            }
            int[] numbers = new int[3];
            for (int i = 0; i < parts.length; i++) {
                numbers[i] = component(value, parts[i]);
            }
            return new SemVer(numbers[0], numbers[1], numbers[2]);
        }

        private static int component(String value, String part) {
            if (part.isEmpty() || !part.chars().allMatch(c -> c >= '0' && c <= '9')
                    || (part.length() > 1 && part.charAt(0) == '0')) {
                throw new IllegalArgumentException("Invalid version '" + value
                        + "': only numeric MAJOR[.MINOR[.PATCH]] release versions are supported");
            }
            try {
                return Integer.parseInt(part);
            } catch (NumberFormatException overflow) {
                throw new IllegalArgumentException(
                        "Invalid version '" + value + "': component out of range", overflow);
            }
        }

        @Override public int compareTo(SemVer other) {
            int result = Integer.compare(major, other.major);
            if (result != 0) return result;
            result = Integer.compare(minor, other.minor);
            return result != 0 ? result : Integer.compare(patch, other.patch);
        }
    }
}

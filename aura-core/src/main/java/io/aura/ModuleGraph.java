package io.aura;

import io.aura.contract.ModuleRequirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure module dependency graph validation and deterministic topological ordering. */
final class ModuleGraph {
    private ModuleGraph() { }

    static List<AuraModule> order(List<AuraModule> modules) {
        Map<String, AuraModule> byId = new LinkedHashMap<>();
        for (AuraModule module : modules) {
            io.aura.contract.ModuleMetadata descriptor = module.descriptor();
            if (byId.putIfAbsent(descriptor.id(), module) != null) {
                throw new ModuleException("AURA_MODULE_DUPLICATE", descriptor.id(),
                        "Duplicate module id: " + descriptor.id());
            }
        }
        List<AuraModule> sorted = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (AuraModule module : modules) orderModule(module, byId, visiting, visited, sorted);
        return sorted;
    }

    private static void orderModule(AuraModule module, Map<String, AuraModule> byId,
                                    Set<String> visiting, Set<String> visited,
                                    List<AuraModule> sorted) {
        String id = module.descriptor().id();
        if (visited.contains(id)) return;
        if (!visiting.add(id)) {
            throw new ModuleException("AURA_MODULE_DEPENDENCY_CYCLE", id,
                    "Circular module dependency involving " + String.join(" -> ", visiting) + " -> " + id);
        }
        for (ModuleRequirement requirement : module.descriptor().requires()) {
            AuraModule dependency = byId.get(requirement.id());
            if (dependency == null) {
                throw new ModuleException("AURA_MODULE_DEPENDENCY_MISSING", id,
                        "Module " + id + " requires " + requirement.id());
            }
            if (requirement.versionRange() != null
                    && !versionMatches(dependency.descriptor().version(), requirement.versionRange())) {
                throw new ModuleException("AURA_MODULE_VERSION_MISMATCH", id,
                        "Module " + id + " requires " + requirement.id() + " " + requirement.versionRange()
                                + " but found " + dependency.descriptor().version());
            }
            orderModule(dependency, byId, visiting, visited, sorted);
        }
        visiting.remove(id);
        visited.add(id);
        sorted.add(module);
    }

    static boolean versionMatches(String version, String range) {
        if (range == null || range.isBlank() || "*".equals(range.trim())) return true;
        String wanted = range.trim();
        SemVer actual = SemVer.parse(version);
        if (wanted.startsWith("[") || wanted.startsWith("(")) {
            int comma = wanted.indexOf(',');
            if (comma < 2 || !wanted.endsWith("]") && !wanted.endsWith(")")) return false;
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
            return actual.compareTo(lower) >= 0 && actual.major == lower.major;
        }
        if (wanted.startsWith("~")) {
            SemVer lower = SemVer.parse(wanted.substring(1));
            return actual.compareTo(lower) >= 0 && actual.major == lower.major && actual.minor == lower.minor;
        }
        if (wanted.endsWith(".*")) return actual.major == SemVer.parse(wanted.substring(0, wanted.length() - 2)).major;
        return actual.compareTo(SemVer.parse(wanted)) == 0;
    }

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
            if (value == null || value.isBlank()) return new SemVer(0, 0, 0);
            String[] parts = value.trim().split("\\.");
            return new SemVer(number(parts, 0), number(parts, 1), number(parts, 2));
        }

        private static int number(String[] parts, int index) {
            if (index >= parts.length || parts[index].isBlank() || "x".equalsIgnoreCase(parts[index])) return 0;
            try { return Integer.parseInt(parts[index].replaceAll("[^0-9].*", "")); }
            catch (NumberFormatException ignored) { return 0; }
        }

        @Override public int compareTo(SemVer other) {
            int result = Integer.compare(major, other.major);
            if (result != 0) return result;
            result = Integer.compare(minor, other.minor);
            return result != 0 ? result : Integer.compare(patch, other.patch);
        }
    }
}

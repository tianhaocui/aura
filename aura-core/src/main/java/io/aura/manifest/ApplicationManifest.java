package io.aura.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.aura.AuraRuntime;
import io.aura.capability.CapabilityRegistry;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.ModuleRequirement;
import io.aura.contract.Permission;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The static application manifest: one declaration projection of the explicit definition model,
 * aggregated from the module graph, the capability registry and the permission registry.
 *
 * <p>It records declarations only — never runtime state (no port, session, caller, approval or
 * invocation record). Rendering is deterministic: fixed field order, sorted collections, no
 * timestamps, so regenerating from the same definitions is byte-identical.
 *
 * <p>{@link #reconcile} compares a committed manifest against a freshly aggregated one. The
 * committed file is the independent declaration side: it was produced at build time from the
 * source composition and is guarded by CI, so the check verifies the application is complete,
 * not merely that generation is self-consistent. JSON numbers parse back as doubles, so numeric
 * comparison is by value, never by Java type.
 */
public final class ApplicationManifest {

    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public static Map<String, Object> generate(AuraRuntime runtime) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION);

        List<Map<String, Object>> modules = new ArrayList<>();
        runtime.moduleDescriptors().stream()
                .sorted(Comparator.comparing(ModuleMetadata::id))
                .forEach(descriptor -> modules.add(module(descriptor)));
        manifest.put("modules", modules);

        manifest.put("capabilities", runtime.find(CapabilityRegistry.class)
                .map(registry -> registry.schema().get("capabilities"))
                .orElse(List.of()));

        List<Map<String, Object>> permissions = new ArrayList<>();
        runtime.permissions().snapshot().values().stream()
                .sorted(Comparator.comparing(Permission::id))
                .forEach(permission -> permissions.add(permission(permission)));
        manifest.put("permissions", permissions);
        return manifest;
    }

    public static String toJson(Map<String, Object> manifest) {
        return GSON.toJson(manifest);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromJson(String json) {
        return GSON.fromJson(json, Map.class);
    }

    /** Every difference between the declared manifest and the runtime aggregation, by name and field path. */
    public static List<String> reconcile(Map<String, Object> declared, Map<String, Object> actual) {
        List<String> differences = new ArrayList<>();
        if (declared == null || actual == null) {
            // A malformed side is a named difference, not an NPE pointing at a stack frame.
            if (declared == null) {
                differences.add("manifest: the declared side is not an object: null");
            }
            if (actual == null) {
                differences.add("manifest: the runtime side is not an object: null");
            }
            return differences;
        }
        Set<String> keys = new LinkedHashSet<>(declared.keySet());
        keys.addAll(actual.keySet());
        for (String key : keys) {
            if (!declared.containsKey(key)) {
                differences.add(key + ": present at runtime but not declared");
                continue;
            }
            if (!actual.containsKey(key)) {
                differences.add(key + ": declared but absent at runtime");
                continue;
            }
            switch (key) {
                case "modules" -> reconcileEntries("module", "id", declared.get(key), actual.get(key), differences);
                case "capabilities" -> reconcileEntries("capability", "name", declared.get(key), actual.get(key), differences);
                case "permissions" -> reconcileEntries("permission", "id", declared.get(key), actual.get(key), differences);
                default -> compare(key, declared.get(key), actual.get(key), differences);
            }
        }
        return differences;
    }

    private static Map<String, Object> module(ModuleMetadata descriptor) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", descriptor.id());
        entry.put("version", descriptor.version());
        List<Map<String, Object>> requires = new ArrayList<>();
        descriptor.requires().stream()
                .sorted(Comparator.comparing(ModuleRequirement::id))
                .forEach(requirement -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", requirement.id());
                    if (requirement.versionRange() != null) view.put("versionRange", requirement.versionRange());
                    requires.add(view);
                });
        entry.put("requires", requires);
        entry.put("provides", descriptor.capabilities().stream().sorted().toList());
        return entry;
    }

    private static Map<String, Object> permission(Permission permission) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", permission.id());
        if (permission.description() != null) entry.put("description", permission.description());
        return entry;
    }

    private static void reconcileEntries(String section, String keyField, Object declaredList,
                                         Object actualList, List<String> differences) {
        Map<String, Map<String, Object>> declared = byKey(section, keyField, declaredList, differences);
        Map<String, Map<String, Object>> actual = byKey(section, keyField, actualList, differences);
        if (declared == null || actual == null) return;
        for (String name : declared.keySet()) {
            if (!actual.containsKey(name)) {
                differences.add(section + " '" + name + "': declared but not registered");
            }
        }
        for (String name : actual.keySet()) {
            if (!declared.containsKey(name)) {
                differences.add(section + " '" + name + "': registered but not declared");
            }
        }
        for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
            Map<String, Object> runtimeEntry = actual.get(entry.getKey());
            if (runtimeEntry != null) {
                compare(section + " '" + entry.getKey() + "'", entry.getValue(), runtimeEntry, differences);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byKey(String section, String keyField,
                                                          Object list, List<String> differences) {
        if (!(list instanceof List<?> entries)) {
            differences.add(section + ": expected a list, found " + (list == null ? "null" : list.getClass().getSimpleName()));
            return null;
        }
        Map<String, Map<String, Object>> keyed = new LinkedHashMap<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map) || !(map.get(keyField) instanceof String name)) {
                differences.add(section + ": entry without a string '" + keyField + "': " + entry);
                return null;
            }
            if (keyed.containsKey(name)) {
                // A contract file that names an entry twice is ambiguous whichever one wins:
                // the duplicate is reported by name and the section is not reconciled on the
                // accident of which copy put() kept last.
                differences.add(section + " '" + name + "': declared twice —"
                        + " a manifest must name each entry once");
                return null;
            }
            keyed.put(name, (Map<String, Object>) map);
        }
        return keyed;
    }

    private static void compare(String path, Object declared, Object actual, List<String> differences) {
        if (declared instanceof Map<?, ?> declaredMap && actual instanceof Map<?, ?> actualMap) {
            for (Object key : declaredMap.keySet()) {
                if (!actualMap.containsKey(key)) {
                    differences.add(path + "/" + key + ": declared but absent at runtime");
                }
            }
            for (Object key : actualMap.keySet()) {
                if (!declaredMap.containsKey(key)) {
                    differences.add(path + "/" + key + ": present at runtime but not declared");
                }
            }
            for (Object key : declaredMap.keySet()) {
                if (actualMap.containsKey(key)) {
                    compare(path + "/" + key, declaredMap.get(key), actualMap.get(key), differences);
                }
            }
        } else if (declared instanceof List<?> declaredList && actual instanceof List<?> actualList) {
            if (declaredList.size() != actualList.size()) {
                differences.add(path + ": declared " + declaredList.size() + " entries, runtime " + actualList.size());
            }
            for (int i = 0; i < Math.min(declaredList.size(), actualList.size()); i++) {
                compare(path + "[" + i + "]", declaredList.get(i), actualList.get(i), differences);
            }
        } else if (declared instanceof Number declaredNumber && actual instanceof Number actualNumber) {
            if (new BigDecimal(declaredNumber.toString()).compareTo(new BigDecimal(actualNumber.toString())) != 0) {
                differences.add(path + ": declared " + declared + ", runtime " + actual);
            }
        } else if (!Objects.equals(declared, actual)) {
            differences.add(path + ": declared " + declared + ", runtime " + actual);
        }
    }

    private ApplicationManifest() { }
}
package io.aura.capability;

import io.aura.contract.AccessPolicy;
import io.aura.contract.ApiParameter;
import io.aura.contract.CapabilityDefinition;
import io.aura.contract.ContractException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one registry of explicitly declared capabilities. Registration is putIfAbsent: a second
 * definition under the same name fails instead of silently replacing the first.
 *
 * <p>{@link #schema()} is a declaration projection — names, parameters, access policies — and
 * never runtime state (no caller, session, approval or invocation record). Parameters are
 * rendered verbatim from {@link ApiParameter}; the registry invents no schema of its own.
 */
public final class CapabilityRegistry {
    private final Map<String, CapabilityDefinition> definitions = new ConcurrentHashMap<>();

    public CapabilityRegistry register(CapabilityDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definitions.putIfAbsent(definition.name(), definition) != null) {
            throw new ContractException("AURA_CAPABILITY_DUPLICATE",
                    "Capability already registered: " + definition.name());
        }
        return this;
    }

    public boolean contains(String name) { return definitions.containsKey(name); }

    public CapabilityDefinition get(String name) {
        CapabilityDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new ContractException("AURA_CAPABILITY_UNKNOWN", "Unknown capability: " + name);
        }
        return definition;
    }

    public Map<String, CapabilityDefinition> snapshot() {
        return Map.copyOf(new LinkedHashMap<>(definitions));
    }

    public Map<String, Object> schema() {
        List<Map<String, Object>> capabilities = new ArrayList<>();
        definitions.values().stream()
                .sorted(Comparator.comparing(CapabilityDefinition::name))
                .forEach(definition -> capabilities.add(describe(definition)));
        return Map.of("capabilities", capabilities);
    }

    private static Map<String, Object> describe(CapabilityDefinition definition) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", definition.name());
        if (definition.description() != null) entry.put("description", definition.description());
        entry.put("access", access(definition.access()));
        List<Map<String, Object>> params = new ArrayList<>();
        for (ApiParameter param : definition.params()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", param.name());
            view.put("type", param.type());
            view.put("source", param.source());
            view.put("required", param.required());
            if (param.description() != null) view.put("description", param.description());
            if (!param.schema().isEmpty()) view.put("schema", param.schema());
            params.add(view);
        }
        entry.put("params", params);
        return entry;
    }

    private static Map<String, Object> access(AccessPolicy policy) {
        if (policy instanceof AccessPolicy.Permission permission) {
            // The manifest renders this map in its iteration order, and Map.of salts that
            // order per JVM run, so the declaration order is fixed here to make
            // regeneration byte-identical instead of lucky.
            Map<String, Object> access = new LinkedHashMap<>();
            access.put("policy", "PERMISSION");
            access.put("permission", permission.id());
            return access;
        }
        return Map.of("policy", policy.authenticated() ? "AUTHENTICATED" : "PUBLIC");
    }
}
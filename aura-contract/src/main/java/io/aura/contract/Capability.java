package io.aura.contract;

import java.util.List;

/**
 * The explicit registration door: a capability exists because module code wrote
 * {@code Capability.of(...)} and registered it — never because a scanner found an annotation.
 */
public final class Capability {

    public static CapabilityDefinition of(String name, String description, AccessPolicy access,
                                          CapabilityHandler handler, ApiParameter... params) {
        return new CapabilityDefinition(name, description,
                params == null ? List.of() : List.of(params), access, handler);
    }

    public static CapabilityDefinition of(String name, String description, AccessPolicy access,
                                          CapabilityHandler handler, List<ApiParameter> params) {
        return new CapabilityDefinition(name, description, params, access, handler);
    }

    private Capability() { }
}
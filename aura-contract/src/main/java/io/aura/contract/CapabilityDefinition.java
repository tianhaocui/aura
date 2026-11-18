package io.aura.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The authoritative, explicit definition of one business capability — the single source every
 * projection (MCP tool, manifest entry, future HTTP route) is derived from.
 *
 * <p>{@code name} is the stable business identity ({@code order.query}). It is not an MCP tool
 * name and carries no protocol naming rules; a projection maps it explicitly and publishes the
 * mapping. {@code params} are the one schema authority: a declared {@link ApiParameter#schema()}
 * wins everywhere, and no projection may re-derive a different shape.
 *
 * <p>Nothing here is runtime state: no session, no caller, no invocation record.
 */
public record CapabilityDefinition(String name, String description, List<ApiParameter> params,
                                   AccessPolicy access, CapabilityHandler handler) {
    public CapabilityDefinition {
        if (name == null || name.isBlank()) {
            throw new ContractException("AURA_CAPABILITY_NAME_INVALID", "Capability name must not be blank");
        }
        name = name.trim();
        if (!name.matches("[A-Za-z0-9._-]+")) {
            throw new ContractException("AURA_CAPABILITY_NAME_INVALID",
                    "Capability name '" + name + "' may contain only letters, digits, '.', '_' and '-'");
        }
        if (access == null) {
            throw new ContractException("AURA_CAPABILITY_ACCESS_REQUIRED",
                    "Capability " + name + " must declare an AccessPolicy (PUBLIC, AUTHENTICATED or"
                            + " permission(id)); access is never inferred from an absent permission");
        }
        if (handler == null) {
            throw new ContractException("AURA_CAPABILITY_HANDLER_MISSING",
                    "Capability " + name + " has no handler");
        }
        params = params == null ? List.of() : List.copyOf(params);
        Set<String> seen = new HashSet<>();
        for (ApiParameter param : params) {
            if (!seen.add(param.name())) {
                throw new ContractException("AURA_CAPABILITY_PARAM_DUPLICATE",
                        "Capability " + name + " declares parameter '" + param.name() + "' twice");
            }
        }
    }
}
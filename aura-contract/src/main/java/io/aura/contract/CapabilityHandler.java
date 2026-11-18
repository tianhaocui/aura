package io.aura.contract;

import java.util.Map;

/**
 * Executes one capability. Authorization has already happened when this runs: the transport
 * resolved {@code userId} (null only for a capability that declared {@link AccessPolicy#PUBLIC})
 * and enforced the declared policy fail-closed. A handler never decides its own access.
 */
@FunctionalInterface
public interface CapabilityHandler {
    Object handle(Map<String, Object> arguments, String userId) throws Exception;
}
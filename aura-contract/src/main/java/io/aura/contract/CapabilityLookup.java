package io.aura.contract;

import java.util.NoSuchElementException;
import java.util.Optional;

/** Looks up capabilities without exposing the registry implementation. */
public interface CapabilityLookup {
    <T> Optional<T> find(Class<T> type);

    default <T> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new NoSuchElementException("Capability not found: " + type.getName()));
    }

    default <T> Optional<T> lookup(Class<T> type) {
        return find(type);
    }
}

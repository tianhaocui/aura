package io.aura.contract;

import java.util.NoSuchElementException;
import java.util.Optional;

/** Looks up capabilities without exposing the registry implementation. */
public interface CapabilityLookup {
    <T> Optional<T> find(Class<T> type);

    /** Looks up one explicitly named capability without exposing the registry. */
    default <T> Optional<T> find(String name, Class<T> type) {
        return Optional.empty();
    }

    default <T> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new NoSuchElementException("Capability not found: " + type.getName()));
    }

    default <T> Optional<T> lookup(Class<T> type) {
        return find(type);
    }

    default <T> T require(String name, Class<T> type) {
        return find(name, type).orElseThrow(() ->
                new NoSuchElementException("Named capability not found: " + name + " (" + type.getName() + ")"));
    }
}

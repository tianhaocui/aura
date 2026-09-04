package io.aura.contract;

/** Stable permission identifier contributed by a capability or business module. */
public record Permission(String id, String description) {
    public Permission {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("permission id must not be blank");
        id = id.trim();
    }
}

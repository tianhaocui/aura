package io.aura;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RouteEntry(String method, String path, Object handler, String description,
                         Map<String, Object> metadata) {
    public RouteEntry {
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public RouteEntry(String method, String path, Object handler, String description) {
        this(method, path, handler, description, Map.of());
    }

    /** Optional structured JSON schema carried from a route contribution. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> responseSchema() {
        Object value = metadata.get("responseSchema");
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        return (Map<String, Object>) map;
    }
}

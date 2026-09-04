package io.aura;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, dependency-free runtime event for observability adapters. */
public record AuraEvent(String type, Instant timestamp, Map<String, Object> attributes) {
    public AuraEvent {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("event type must not be blank");
        timestamp = timestamp == null ? Instant.now() : timestamp;
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static AuraEvent of(String type, Map<String, ?> attributes) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (attributes != null) values.putAll(attributes);
        return new AuraEvent(type, Instant.now(), values);
    }
}

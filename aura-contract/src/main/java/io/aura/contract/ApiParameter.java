package io.aura.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable AI-facing description of one API parameter. */
public record ApiParameter(String name, String type, String source, boolean required, String description,
                           Map<String, Object> schema) {
    public ApiParameter(String name, String type, String source, boolean required, String description) {
        this(name, type, source, required, description, Map.of());
    }

    public ApiParameter {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("parameter name must not be blank");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("parameter type must not be blank");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("parameter source must not be blank");
        name = name.trim();
        type = type.trim();
        source = source.trim();
        schema = immutableSchema(schema);
    }

    private static Map<String, Object> immutableSchema(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(copyMap(value));
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("schema key must not be blank");
            }
            copy.put(key, immutableValue(item));
        });
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            nested.forEach((key, item) -> {
                if (!(key instanceof String name) || name.isBlank()) {
                    throw new IllegalArgumentException("schema key must not be blank");
                }
                normalized.put(name, immutableValue(item));
            });
            return Collections.unmodifiableMap(normalized);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream().map(ApiParameter::immutableValue).toList());
        }
        return value;
    }
}

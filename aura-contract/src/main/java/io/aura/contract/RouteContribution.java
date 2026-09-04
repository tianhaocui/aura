package io.aura.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Framework-neutral description of one route contributed by a module. */
public record RouteContribution(String method, String path, Object handler,
                                Map<String, Object> metadata) {
    public RouteContribution {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("route method must not be blank");
        }
        if (path == null || path.isBlank() || !path.trim().startsWith("/")) {
            throw new IllegalArgumentException("route path must start with '/'");
        }
        method = method.trim().toUpperCase(java.util.Locale.ROOT);
        path = path.trim();
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public RouteContribution(String method, String path) {
        this(method, path, null, Map.of());
    }

    public RouteContribution(String method, String path, Object handler) {
        this(method, path, handler, Map.of());
    }

    public String description() {
        Object value = metadata.get("description");
        return value == null ? null : value.toString();
    }

    public String returnType() {
        Object value = metadata.get("returnType");
        return value == null ? null : value.toString();
    }

    /** Optional explicit parameters for handlers whose signature is not reflectively discoverable. */
    public java.util.List<ApiParameter> parameters() {
        Object value = metadata.get("parameters");
        if (!(value instanceof java.util.Collection<?> values)) return java.util.List.of();
        java.util.List<ApiParameter> result = new java.util.ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof ApiParameter parameter)) {
                throw new IllegalArgumentException("route metadata parameters must contain ApiParameter values");
            }
            result.add(parameter);
        }
        return java.util.List.copyOf(result);
    }
}

package io.aura.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Framework-neutral description of one route contributed by a module. */
public record RouteContribution(String method, String path, Object handler,
                                Map<String, Object> metadata) {
    public static final String AUTHENTICATED = "authenticated";
    public static final String PERMISSION = "x-aura-permission";
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

    /** Returns a copy that requires a successfully authenticated request. */
    public RouteContribution authenticated() {
        return withMetadata(AUTHENTICATED, true);
    }

    /** Returns a copy that requires authentication and one application permission. */
    public RouteContribution permission(String permission) {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("route permission must not be blank");
        }
        return withMetadata(PERMISSION, permission.trim()).withMetadata(AUTHENTICATED, true);
    }

    private RouteContribution withMetadata(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.put(key, value);
        return new RouteContribution(method, path, handler, copy);
    }

    /** Optional structured JSON schema for the route response. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> responseSchema() {
        Object value = metadata.get("responseSchema");
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        return (Map<String, Object>) map;
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

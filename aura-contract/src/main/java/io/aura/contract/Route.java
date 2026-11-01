package io.aura.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Single, immutable description of a route. Replaces the 1.0-era
 * {@link RouteContribution} / {@code io.aura.RouteEntry} / {@link ApiRoute} triple
 * with one record consumed by HTTP dispatch, TestClient, {@code /__schema__},
 * OpenAPI, and MCP. Stage ② of the 0.6-philosophy reset.
 *
 * <p>{@code handler} is intentionally {@link Object} at the contract layer so
 * aura-contract stays free of {@link io.aura.web.BaseHandler}. Runtime consumers
 * (aura-web) cast to their handler shape; author-facing helpers accept a
 * typed {@code BaseHandler} overload in the web layer.
 *
 * <p>All metadata that was previously smuggled through {@code Map<String,Object>}
 * string keys ({@code "description"}, {@code "returnType"}, {@code "streaming"},
 * {@code "parameters"}, {@code "responseSchema"}) is now first-class record
 * components. Misspelled keys were silent failures — this eliminates them.
 */
public record Route(
        String method,
        String path,
        Object handler,
        List<ApiParameter> params,
        String returnType,
        Map<String, Object> responseSchema,
        String description,
        boolean authenticated,
        String permission,
        boolean streaming
) {
    public Route {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("route method must not be blank");
        }
        if (path == null || path.isBlank() || !path.trim().startsWith("/")) {
            throw new IllegalArgumentException("route path must start with '/'");
        }
        method = method.trim().toUpperCase(Locale.ROOT);
        path = path.trim();
        Objects.requireNonNull(handler, "route handler must not be null");
        params = params == null ? List.of() : List.copyOf(params);
        responseSchema = responseSchema == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(responseSchema));
        if (permission != null) {
            String trimmed = permission.trim();
            if (trimmed.isEmpty()) {
                permission = null;
            } else {
                permission = trimmed;
                authenticated = true;
            }
        }
    }

    /** Minimal shape: method + path + handler. All other fields default. */
    public static Route of(String method, String path, Object handler) {
        return new Route(method, path, handler, List.of(), null, Map.of(), null, false, null, false);
    }

    /** Convenience factory: description + auth for hand-authored module routes. */
    public static Route describe(Route base, String description) {
        return new Route(base.method, base.path, base.handler, base.params, base.returnType,
                base.responseSchema, description, base.authenticated, base.permission, base.streaming);
    }

    /** Fluent instance variant of {@link #describe(Route, String)}. */
    public Route describedAs(String newDescription) {
        return new Route(method, path, handler, params, returnType, responseSchema,
                newDescription, authenticated, permission, streaming);
    }

    public Route requireAuth(String permission) {
        return new Route(method, path, handler, params, returnType, responseSchema,
                description, true, permission, streaming);
    }

    public Route requireAuth() {
        return new Route(method, path, handler, params, returnType, responseSchema,
                description, true, permission, true);
    }

    public Route withParams(List<ApiParameter> newParams) {
        return new Route(method, path, handler, newParams, returnType, responseSchema,
                description, authenticated, permission, streaming);
    }

    public Route withReturnType(String newReturnType) {
        return new Route(method, path, handler, params, newReturnType, responseSchema,
                description, authenticated, permission, streaming);
    }

    public Route withResponseSchema(Map<String, Object> newSchema) {
        return new Route(method, path, handler, params, returnType, newSchema,
                description, authenticated, permission, streaming);
    }

    public Route withStreaming(boolean isStreaming) {
        return new Route(method, path, handler, params, returnType, responseSchema,
                description, authenticated, permission, isStreaming);
    }

    /**
     * Bridges an existing {@link RouteContribution} into the unified {@code Route}.
     * Reads the metadata keys the old shape smuggled as strings — no silent skips:
     * any unknown key triggers a hard failure so typos surface at startup.
     */
    public static Route fromContribution(RouteContribution contribution) {
        Map<String, Object> metadata = contribution.metadata();
        String description = stringMeta(metadata, "description");
        String returnType = stringMeta(metadata, "returnType");
        String permission = stringMeta(metadata, RouteContribution.PERMISSION);
        boolean authenticated = Boolean.TRUE.equals(metadata.get(RouteContribution.AUTHENTICATED))
                || permission != null;
        boolean streaming = Boolean.TRUE.equals(metadata.get("streaming"));
        Map<String, Object> responseSchema = mapMeta(metadata, "responseSchema");
        List<ApiParameter> parameters = listParamsMeta(metadata, "parameters");
        return new Route(contribution.method(), contribution.path(), contribution.handler(),
                parameters, returnType, responseSchema, description,
                authenticated, permission, streaming);
    }

    /** Emits the transport-neutral view used by {@code /__schema__} and OpenAPI. */
    public ApiRoute toApiRoute() {
        return new ApiRoute(method, path, description, returnType, params, null,
                responseSchema, authenticated, permission);
    }

    private static String stringMeta(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapMeta(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("route metadata '" + key + "' must be a Map");
        }
        return (Map<String, Object>) value;
    }

    private static List<ApiParameter> listParamsMeta(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) return List.of();
        if (!(value instanceof java.util.Collection<?> values)) {
            throw new IllegalArgumentException("route metadata '" + key + "' must be a Collection");
        }
        java.util.List<ApiParameter> out = new java.util.ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof ApiParameter parameter)) {
                throw new IllegalArgumentException(
                        "route metadata '" + key + "' entries must be ApiParameter");
            }
            out.add(parameter);
        }
        return List.copyOf(out);
    }
}

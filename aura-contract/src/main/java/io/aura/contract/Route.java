package io.aura.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single, immutable description of a route — the only shape a module can
 * contribute. Consumed directly by HTTP dispatch, TestClient, {@code /__schema__},
 * OpenAPI, and MCP.
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
    /** The method a WebSocket endpoint is declared under — one contract, one door. */
    public static final String WEBSOCKET = "WS";

    public Route {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("route method must not be blank");
        }
        if (path == null || path.isBlank() || !path.trim().startsWith("/")) {
            throw new IllegalArgumentException("route path must start with '/'");
        }
        method = method.trim().toUpperCase(Locale.ROOT);
        path = path.trim();
        // A contribution with no handler is the one assembly mistake whose repair is not in the
        // stack trace: the caller has to learn which route, and that a handler is mandatory by
        // construction, from the failure itself.
        if (handler == null) {
            throw new ContractException("AURA_ROUTE_HANDLER_MISSING",
                    "Route " + method + " " + path + " has no handler; "
                            + "Route.of(method, path, handler) requires one");
        }
        // A handshake that upgrades is not readable as one request and one response,
        // whatever the caller happened to pass for the streaming flag.
        if (WEBSOCKET.equals(method)) streaming = true;
        params = params == null ? List.of() : List.copyOf(params);
        responseSchema = responseSchema == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(responseSchema));
        if (permission != null) {
            String trimmed = permission.trim();
            if (trimmed.isEmpty()) {
                // A present-but-blank permission is a mistyped constant. Dropping it
                // would silently publish an unprotected route, so it fails at startup.
                throw new IllegalArgumentException("route permission must not be blank");
            }
            permission = trimmed;
            authenticated = true;
        }
    }

    /** Minimal shape: method + path + handler. All other fields default. */
    public static Route of(String method, String path, Object handler) {
        return new Route(method, path, handler, List.of(), null, Map.of(), null, false, null, false);
    }

    /**
     * A WebSocket endpoint described as the same contract as an HTTP route. The handler is
     * the web layer's own configuration shape, carried as {@code Object} so this module stays
     * free of it; the route it produces is authenticated, permissioned, and published exactly
     * like any other.
     */
    public static Route webSocket(String path, Object configure) {
        return new Route(WEBSOCKET, path, configure, List.of(), null, Map.of(), null, false, null, true);
    }

    public boolean isWebSocket() {
        return WEBSOCKET.equals(method);
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

    /** Rebinds the dispatch handler, keeping every declared fact (auth wrapping). */
    public Route withHandler(Object newHandler) {
        return new Route(method, path, newHandler, params, returnType, responseSchema,
                description, authenticated, permission, streaming);
    }

    /** Re-expresses the route under a router-group prefix. */
    public Route withPath(String newPath) {
        return new Route(method, newPath, handler, params, returnType, responseSchema,
                description, authenticated, permission, streaming);
    }

    public Route requireAuth(String permission) {
        return new Route(method, path, handler, params, returnType, responseSchema,
                description, true, permission, streaming);
    }

    public Route requireAuth() {
        return new Route(method, path, handler, params, returnType, responseSchema,
                description, true, permission, streaming);
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

}

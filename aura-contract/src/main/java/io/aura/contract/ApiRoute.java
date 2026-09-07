package io.aura.contract;

import java.util.List;
import java.util.Map;

/** Stable, transport-neutral description shared by schema and adapter generators. */
public record ApiRoute(String method, String path, String description, String returnType,
                       List<ApiParameter> params, String example,
                       Map<String, Object> responseSchema) {
    public ApiRoute(String method, String path, String description, String returnType,
                    List<ApiParameter> params, String example) {
        this(method, path, description, returnType, params, example, Map.of());
    }

    public ApiRoute {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("route method must not be blank");
        if (path == null || path.isBlank()) throw new IllegalArgumentException("route path must not be blank");
        method = method.trim().toUpperCase(java.util.Locale.ROOT);
        path = path.trim();
        params = params == null ? List.of() : List.copyOf(params);
        responseSchema = responseSchema == null ? Map.of() : Map.copyOf(responseSchema);
    }
}

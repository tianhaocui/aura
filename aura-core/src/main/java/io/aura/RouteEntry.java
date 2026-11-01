package io.aura;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Legacy 1.0 accumulation shape for module routes.
 *
 * <p>Stage ②-B replaced every Web consumer with {@link io.aura.contract.Route}, whose
 * fields are typed instead of smuggled through {@code metadata} string keys
 * ({@code "description"}, {@code "returnType"}, {@code "streaming"}, {@code "parameters"},
 * {@code "responseSchema"}). Nothing inside Aura reads this record any more; it survives only as
 * published API until Stage ④ drops it from the public surface.
 *
 * @deprecated use {@link io.aura.contract.Route}; this record is removed in Stage ④.
 */
@Deprecated(forRemoval = true, since = "1.0")
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

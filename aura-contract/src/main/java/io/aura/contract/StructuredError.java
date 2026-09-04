package io.aura.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable, serializable error data that adapters can map to their own protocols. */
public record StructuredError(String code, String message, Map<String, Object> details) {
    public StructuredError {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("error code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("error message must not be blank");
        }
        code = code.trim();
        details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public StructuredError(String code, String message) {
        this(code, message, Map.of());
    }
}

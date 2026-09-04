package io.aura.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One model-requested tool invocation with parsed JSON arguments. */
public record AiToolCall(String id, String name, Map<String, Object> arguments) {
    public AiToolCall {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool call name must not be blank");
        name = name.trim();
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}

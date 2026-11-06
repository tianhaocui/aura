package io.aura.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Provider-neutral output from a chat completion. */
public final class AiResponse {
    private final String content;
    private final String model;
    private final Map<String, Object> usage;
    private final Object raw;
    private final List<AiToolCall> toolCalls;

    public AiResponse(String content) {
        this(content, null, Map.of(), null);
    }

    public AiResponse(String content, String model, Map<String, ?> usage, Object raw) {
        this(content, model, usage, raw, List.of());
    }

    public AiResponse(String content, String model, Map<String, ?> usage, Object raw,
                       List<AiToolCall> toolCalls) {
        if (content == null) throw new IllegalArgumentException("content must not be null");
        this.content = content;
        this.model = model;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (usage != null) usage.forEach(copy::put);
        this.usage = Collections.unmodifiableMap(copy);
        this.raw = raw;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AiResponse text(String content) { return new AiResponse(content); }
    public String content() { return content; }
    public String model() { return model; }
    public Map<String, Object> usage() { return usage; }
    public Object raw() { return raw; }
    public List<AiToolCall> toolCalls() { return toolCalls; }
}

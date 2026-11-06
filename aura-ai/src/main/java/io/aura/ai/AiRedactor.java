package io.aura.ai;

/** Redacts secrets before provider calls and telemetry. Implementations must be deterministic. */
@FunctionalInterface
public interface AiRedactor {
    String redact(String value);

    /** Recursively redacts strings and values stored under sensitive field names. */
    default Object redactValue(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return redact(text);
        if (value instanceof java.util.Map<?, ?> map) {
            java.util.Map<Object, Object> redacted = new java.util.LinkedHashMap<>();
            map.forEach((key, nested) -> redacted.put(key,
                    key instanceof String name && isSensitiveKey(name)
                            ? "<redacted>" : redactValue(nested)));
            return java.util.Collections.unmodifiableMap(redacted);
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.stream().map(this::redactValue).toList();
        }
        if (value instanceof Object[] array) {
            return java.util.Arrays.stream(array).map(this::redactValue).toArray();
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[-_.\\s]", "");
        return normalized.endsWith("password") || normalized.endsWith("passwd")
                || normalized.endsWith("token") || normalized.endsWith("secret")
                || normalized.endsWith("apikey") || normalized.endsWith("authorization");
    }

    static AiRedactor none() { return value -> value == null ? null : value; }

    static AiRedactor defaultRedactor() {
        return value -> {
            if (value == null || value.isEmpty()) return value;
            String redacted = value.replaceAll(
                    "(?i)([\"']?authorization[\"']?\\s*[:=]\\s*[\"']?)(?:bearer|basic)\\s+[^\"'\\s,;&]+",
                    "$1<redacted>");
            redacted = redacted.replaceAll(
                    "(?i)\\b(?:bearer|basic)\\s+[a-z0-9._~+/=-]+",
                    "<redacted>");
            redacted = redacted.replaceAll(
                    "(?i)(password|token|secret|api[-_]?key|authorization)(\\s*[:=]\\s*)[^\\s,;&]+",
                    "$1$2<redacted>");
            return redacted.replaceAll(
                    "(?i)([\\\"']?(?:password|token|secret|api[-_]?key|authorization)[\\\"']?\\s*:\\s*[\\\"']?)[^\\\"'\\s,;&]+([\\\"']?)",
                    "$1<redacted>$2");
        };
    }
}

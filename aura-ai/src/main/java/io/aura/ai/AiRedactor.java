package io.aura.ai;

/** Redacts secrets before provider calls and telemetry. Implementations must be deterministic. */
@FunctionalInterface
public interface AiRedactor {
    String redact(String value);

    static AiRedactor none() { return value -> value == null ? null : value; }

    static AiRedactor defaultRedactor() {
        return value -> {
            if (value == null || value.isEmpty()) return value;
            String redacted = value.replaceAll(
                    "(?i)(password|token|secret|api[-_]?key|authorization)(\\s*[:=]\\s*)[^\\s,;&]+",
                    "$1$2<redacted>");
            return redacted.replaceAll(
                    "(?i)([\\\"']?(?:password|token|secret|api[-_]?key|authorization)[\\\"']?\\s*:\\s*[\\\"']?)[^\\\"'\\s,;&]+([\\\"']?)",
                    "$1<redacted>$2");
        };
    }
}

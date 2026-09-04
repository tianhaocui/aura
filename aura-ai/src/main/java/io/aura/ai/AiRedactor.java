package io.aura.ai;

/** Redacts secrets before provider calls and telemetry. Implementations must be deterministic. */
@FunctionalInterface
public interface AiRedactor {
    String redact(String value);

    static AiRedactor none() { return value -> value == null ? null : value; }
}

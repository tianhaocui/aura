package io.aura.ai;

/** Optional per-request budget enforced by an AI provider or client wrapper. */
public record AiBudget(long maxInputCharacters, long maxOutputCharacters, long maxCostMicros) {
    public AiBudget {
        if (maxInputCharacters < 0 || maxOutputCharacters < 0 || maxCostMicros < 0) {
            throw new IllegalArgumentException("AI budget limits must not be negative");
        }
    }

    public static AiBudget unlimited() { return new AiBudget(0, 0, 0); }
}

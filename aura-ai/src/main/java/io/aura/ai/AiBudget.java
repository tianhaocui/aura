package io.aura.ai;

/** Immutable per-request limits enforced by {@link AiClient}. Zero means unlimited. */
public record AiBudget(long maxInputCharacters, long maxOutputCharacters,
                       long maxInputTokens, long maxOutputTokens,
                       long maxTotalTokens, long maxCostMicros) {
    /** Compatibility constructor for the original character/cost budget. */
    public AiBudget(long maxInputCharacters, long maxOutputCharacters, long maxCostMicros) {
        this(maxInputCharacters, maxOutputCharacters, 0, 0, 0, maxCostMicros);
    }

    public AiBudget {
        if (maxInputCharacters < 0 || maxOutputCharacters < 0
                || maxInputTokens < 0 || maxOutputTokens < 0
                || maxTotalTokens < 0 || maxCostMicros < 0) {
            throw new IllegalArgumentException("AI budget limits must not be negative");
        }
    }

    public static AiBudget unlimited() { return new AiBudget(0, 0, 0, 0, 0, 0); }
    public static Builder builder() { return new Builder(); }

    public boolean requiresUsage() {
        return maxInputTokens > 0 || maxOutputTokens > 0 || maxTotalTokens > 0 || maxCostMicros > 0;
    }

    public static final class Builder {
        private long inputCharacters;
        private long outputCharacters;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private long costMicros;

        public Builder maxInputCharacters(long value) { inputCharacters = value; return this; }
        public Builder maxOutputCharacters(long value) { outputCharacters = value; return this; }
        public Builder maxInputTokens(long value) { inputTokens = value; return this; }
        public Builder maxOutputTokens(long value) { outputTokens = value; return this; }
        public Builder maxTotalTokens(long value) { totalTokens = value; return this; }
        public Builder maxCostMicros(long value) { costMicros = value; return this; }
        public AiBudget build() {
            return new AiBudget(inputCharacters, outputCharacters, inputTokens,
                    outputTokens, totalTokens, costMicros);
        }
    }
}

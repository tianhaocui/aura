package io.aura.ai;

/** Immutable cumulative provider usage observed by one {@link AiClient}. */
public record AiUsageTotals(long requests, long inputTokens, long outputTokens,
                            long totalTokens, long costMicros, long unknownUsage) {
    public AiUsageTotals {
        if (requests < 0 || inputTokens < 0 || outputTokens < 0 || totalTokens < 0
                || costMicros < 0 || unknownUsage < 0) {
            throw new IllegalArgumentException("AI usage totals must not be negative");
        }
    }

    public static AiUsageTotals empty() {
        return new AiUsageTotals(0, 0, 0, 0, 0, 0);
    }
}

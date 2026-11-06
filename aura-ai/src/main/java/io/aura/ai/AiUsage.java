package io.aura.ai;

import java.util.Map;
import java.math.BigDecimal;

/** Normalized provider usage. A value of {@code -1} means the provider did not report it. */
public record AiUsage(long inputTokens, long outputTokens, long totalTokens, long costMicros) {
    public AiUsage {
        if (inputTokens < -1 || outputTokens < -1 || totalTokens < -1 || costMicros < -1) {
            throw new IllegalArgumentException("AI usage values must be non-negative or -1 when unknown");
        }
    }

    public static AiUsage unknown() { return new AiUsage(-1, -1, -1, -1); }

    public static AiUsage from(Map<String, ?> usage) {
        if (usage == null || usage.isEmpty()) return unknown();
        long input = number(usage, "input_tokens", "prompt_tokens");
        long output = number(usage, "output_tokens", "completion_tokens");
        long total = number(usage, "total_tokens");
        if (total < 0 && input >= 0 && output >= 0) total = Math.addExact(input, output);
        long cost = number(usage, "cost_micros");
        return new AiUsage(input, output, total, cost);
    }

    public boolean hasTokenUsage() { return inputTokens >= 0 || outputTokens >= 0 || totalTokens >= 0; }
    public boolean hasCost() { return costMicros >= 0; }

    private static long number(Map<String, ?> usage, String... keys) {
        for (String key : keys) {
            Object value = usage.get(key);
            if (value == null) continue;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return nonNegative(((Number) value).longValue(), key);
            }
            if (value instanceof Number number) {
                try {
                    return nonNegative(new BigDecimal(number.toString()).longValueExact(), key);
                } catch (ArithmeticException | NumberFormatException error) {
                    throw new IllegalArgumentException("AI usage " + key + " must be an integer", error);
                }
            }
            try { return nonNegative(Long.parseLong(value.toString()), key); }
            catch (NumberFormatException error) {
                throw new IllegalArgumentException("AI usage " + key + " must be an integer", error);
            }
        }
        return -1;
    }

    private static long nonNegative(long value, String key) {
        if (value < 0) throw new IllegalArgumentException("AI usage " + key + " must not be negative");
        return value;
    }
}

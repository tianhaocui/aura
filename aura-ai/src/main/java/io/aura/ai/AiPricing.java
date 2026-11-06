package io.aura.ai;

import java.math.BigInteger;

/** Explicit model pricing used to derive cost when a provider reports tokens but no cost. */
public record AiPricing(long inputMicrosPerMillionTokens, long outputMicrosPerMillionTokens) {
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000L);

    public AiPricing {
        if (inputMicrosPerMillionTokens < 0 || outputMicrosPerMillionTokens < 0) {
            throw new IllegalArgumentException("AI pricing rates must not be negative");
        }
    }

    public static AiPricing of(long inputMicrosPerMillionTokens,
                               long outputMicrosPerMillionTokens) {
        return new AiPricing(inputMicrosPerMillionTokens, outputMicrosPerMillionTokens);
    }

    /** Returns a conservative micro-unit estimate, or -1 when token usage is unavailable. */
    public long estimateCostMicros(AiUsage usage) {
        if (usage == null || usage.inputTokens() < 0 || usage.outputTokens() < 0) return -1;
        BigInteger input = BigInteger.valueOf(usage.inputTokens())
                .multiply(BigInteger.valueOf(inputMicrosPerMillionTokens));
        BigInteger output = BigInteger.valueOf(usage.outputTokens())
                .multiply(BigInteger.valueOf(outputMicrosPerMillionTokens));
        BigInteger total = input.add(output);
        if (total.signum() == 0) return 0;
        BigInteger roundedUp = total.add(MILLION.subtract(BigInteger.ONE)).divide(MILLION);
        return roundedUp.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE : roundedUp.longValue();
    }
}

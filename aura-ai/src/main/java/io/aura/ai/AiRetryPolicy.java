package io.aura.ai;

import java.time.Duration;

/** Bounded retry policy for provider calls; streaming calls are intentionally not retried. */
public record AiRetryPolicy(int maxAttempts, Duration backoff) {
    public AiRetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (backoff == null || backoff.isNegative()) throw new IllegalArgumentException("backoff must not be negative");
    }

    public static AiRetryPolicy none() { return new AiRetryPolicy(1, Duration.ZERO); }
    public static AiRetryPolicy fixed(int maxAttempts, Duration backoff) {
        return new AiRetryPolicy(maxAttempts, backoff);
    }

    public boolean shouldRetry(Throwable error) {
        if (error instanceof AiProviderException providerError) {
            return providerError.status() == 429 || providerError.status() >= 500;
        }
        return error instanceof java.io.IOException;
    }
}

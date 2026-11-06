package io.aura.workflow;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

public final class RetryPolicy {
    private static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO, error -> true);
    private final int maxAttempts;
    private final Duration delay;
    private final Predicate<Throwable> retryOn;

    private RetryPolicy(int maxAttempts, Duration delay, Predicate<Throwable> retryOn) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.delay = Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) throw new IllegalArgumentException("delay cannot be negative");
        this.retryOn = Objects.requireNonNull(retryOn, "retryOn");
    }

    public static RetryPolicy none() { return NONE; }
    public static RetryPolicy of(int maxAttempts) { return new RetryPolicy(maxAttempts, Duration.ZERO, error -> true); }
    public static RetryPolicy fixed(int maxAttempts, Duration delay) {
        return new RetryPolicy(maxAttempts, delay, error -> true);
    }
    public RetryPolicy retryOn(Predicate<Throwable> predicate) {
        return new RetryPolicy(maxAttempts, delay, predicate);
    }
    public int maxAttempts() { return maxAttempts; }
    public Duration delay() { return delay; }
    public boolean shouldRetry(Throwable error) { return retryOn.test(error); }
}

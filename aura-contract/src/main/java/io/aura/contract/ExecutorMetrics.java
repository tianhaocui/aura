package io.aura.contract;

/** Dependency-free snapshot for one bounded executor or an opaque external executor. */
public record ExecutorMetrics(String name, boolean observable, int workerLimit,
                              int active, int queued, int queueCapacity,
                              long completed, long rejected, long timedOut) {
    public ExecutorMetrics {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("executor name must not be blank");
        if (workerLimit < 0 || active < 0 || queued < 0 || queueCapacity < 0
                || completed < 0 || rejected < 0 || timedOut < 0) {
            throw new IllegalArgumentException("executor metrics cannot be negative");
        }
    }

    public static ExecutorMetrics opaque(String name, long rejected, long timedOut) {
        return new ExecutorMetrics(name, false, 0, 0, 0, 0, 0, rejected, timedOut);
    }
}

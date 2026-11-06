package io.aura;

import io.aura.contract.HealthCheck;
import io.aura.contract.HealthStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, fail-closed health probe shared by legacy and modular runtimes. */
final class HealthProbe {
    private static final int MAX_CONCURRENT_CHECKS = 16;

    private HealthProbe() { }

    /**
     * Runs the checks on an executor owned by this probe so a slow check cannot starve a
     * different runtime's health endpoint. The queue is unbounded on purpose: the deadline,
     * not rejection, decides that a check failed.
     */
    static HealthStatus check(List<HealthCheck> checks, int timeoutSeconds) {
        if (checks.isEmpty()) return HealthStatus.UP;
        int workers = Math.min(checks.size(), MAX_CONCURRENT_CHECKS);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "aura-health-check");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        CompletionService<HealthStatus> completed = new ExecutorCompletionService<>(executor);
        List<Future<HealthStatus>> futures = new ArrayList<>(checks.size());
        try {
            for (HealthCheck check : checks) {
                futures.add(completed.submit(check::check));
            }

            for (int remainingChecks = checks.size(); remainingChecks > 0; remainingChecks--) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) return HealthStatus.DOWN;

                Future<HealthStatus> future = completed.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (future == null) return HealthStatus.DOWN;

                HealthStatus status = future.get();
                if (status != HealthStatus.UP) return HealthStatus.DOWN;
            }
            return HealthStatus.UP;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return HealthStatus.DOWN;
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
    }
}

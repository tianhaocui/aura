package io.aura;

import io.aura.contract.HealthCheck;
import io.aura.contract.HealthStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, fail-closed health probe shared by legacy and modular runtimes. */
final class HealthProbe {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            16, 16, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            task -> {
                Thread thread = new Thread(task, "aura-health-check");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private HealthProbe() { }

    static HealthStatus check(List<HealthCheck> checks, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        CompletionService<HealthStatus> completed = new ExecutorCompletionService<>(EXECUTOR);
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
        }
    }
}

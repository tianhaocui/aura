package io.aura;

import io.aura.contract.HealthCheck;
import io.aura.contract.HealthStatus;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, fail-closed health probe shared by legacy and modular runtimes. */
final class HealthProbe {
    private static final long TIMEOUT_MILLIS = 1_000L;
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            0, 16, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            task -> {
                Thread thread = new Thread(task, "aura-health-check");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private HealthProbe() { }

    static HealthStatus check(List<HealthCheck> checks) {
        for (HealthCheck check : checks) {
            Future<HealthStatus> future = null;
            try {
                future = EXECUTOR.submit(check::check);
                HealthStatus status = future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (status != HealthStatus.UP) return HealthStatus.DOWN;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return HealthStatus.DOWN;
            } finally {
                if (future != null) future.cancel(true);
            }
        }
        return HealthStatus.UP;
    }
}

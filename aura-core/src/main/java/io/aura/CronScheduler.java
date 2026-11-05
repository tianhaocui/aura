package io.aura;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Runs cron contributions on their wall-clock fire times. Each contribution is armed for its next
 * fire, re-armed when it has run, and the pool that holds it is shut down when the runtime stops.
 * A task that throws is reported and its next occurrence stays armed.
 */
final class CronScheduler implements AutoCloseable {
    private static final long CLOSE_AWAIT_MILLIS = 5_000L;
    private static final long MIN_ARM_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final ScheduledExecutorService executor;
    private final Clock clock;
    private final BiConsumer<CronContribution, Throwable> onTaskFailure;
    private final List<Slot> slots;
    private volatile boolean closed;

    private CronScheduler(List<CronContribution> contributions, ScheduledExecutorService executor,
                          Clock clock, BiConsumer<CronContribution, Throwable> onTaskFailure) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.onTaskFailure = java.util.Objects.requireNonNull(onTaskFailure, "onTaskFailure");
        List<Slot> armed = new ArrayList<>();
        for (CronContribution contribution : contributions) armed.add(new Slot(contribution));
        this.slots = List.copyOf(armed);
    }

    static CronScheduler start(List<CronContribution> contributions, ScheduledExecutorService executor,
                               Clock clock, BiConsumer<CronContribution, Throwable> onTaskFailure) {
        CronScheduler scheduler = new CronScheduler(contributions, executor, clock, onTaskFailure);
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (Slot slot : scheduler.slots) slot.arm(now);
        return scheduler;
    }

    /** The pool cron tasks run on: one bounded set of daemon threads, named so a leak is visible. */
    static ScheduledExecutorService newPool(int threads) {
        ThreadFactory named = new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger sequence =
                    new java.util.concurrent.atomic.AtomicInteger();
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "aura-cron-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(Math.max(1, threads), named);
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    @Override public void close() {
        closed = true;
        for (Slot slot : slots) {
            ScheduledFuture<?> future = slot.future;
            if (future != null) future.cancel(false);
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(CLOSE_AWAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                org.slf4j.LoggerFactory.getLogger(CronScheduler.class)
                        .warn("Aura cron tasks were still running when the schedule was closed");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Slot {
        private final CronContribution contribution;
        private volatile ScheduledFuture<?> future;

        private Slot(CronContribution contribution) { this.contribution = contribution; }

        private void arm(ZonedDateTime now) {
            if (closed) return;
            long delayNanos = Duration.between(now, contribution.schedule().next(now)).toNanos();
            // A zero-delay arm runs fire() again the instant it finishes, so if a schedule ever
            // resolved to a past instant the slot would spin its thread and re-run the task
            // instead of waiting for the next occurrence. One second keeps a real fire time and
            // bounds any regression at one retry per second.
            if (delayNanos < MIN_ARM_DELAY_NANOS) delayNanos = MIN_ARM_DELAY_NANOS;
            try {
                future = executor.schedule(this::fire, delayNanos, TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException stopping) {
                // close() already shut the pool down; this schedule ends here.
            }
        }

        private void fire() {
            if (closed) return;
            try {
                contribution.task().run();
            } catch (RuntimeException | Error failure) {
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                onTaskFailure.accept(contribution, failure);
            }
            arm(ZonedDateTime.now(clock));
        }
    }
}

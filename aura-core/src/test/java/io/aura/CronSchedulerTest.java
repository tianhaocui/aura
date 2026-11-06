package io.aura;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CronSchedulerTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZonedDateTime NOW =
            ZonedDateTime.of(LocalDateTime.of(2026, 11, 3, 14, 7, 0), SHANGHAI);
    private static final Clock FIXED = Clock.fixed(NOW.toInstant(), SHANGHAI);

    @Test
    void armsEveryContributionForItsOwnNextFireTime() {
        Recorder executor = new Recorder();

        CronScheduler.start(List.of(contribution("0 9 * * *"), contribution("*/15 * * * *")),
                executor, FIXED, (task, failure) -> { });

        assertThat(executor.delays()).containsExactly(
                Duration.ofHours(18).plusMinutes(53), Duration.ofMinutes(8));
    }

    @Test
    void aFiredTaskRunsOnceAndIsReArmedForALaterOccurrence() {
        Recorder executor = new Recorder();
        AtomicInteger runs = new AtomicInteger();

        CronScheduler.start(List.of(contribution("*/15 * * * *", runs::incrementAndGet)),
                executor, FIXED, (task, failure) -> { });
        executor.fire(0);

        assertThat(runs).hasValue(1);
        assertThat(executor.delays()).containsExactly(Duration.ofMinutes(8), Duration.ofMinutes(8));
    }

    @Test
    void armingInsideTheRepeatedHourWaitsForThatMinuteInsteadOfFiringAtOnce() {
        ZoneId newYork = ZoneId.of("America/New_York");
        // 01:30 on the 2026-11-01 fall-back day, on the second pass through that hour. Resolved
        // without care for the overlap, the next fire is 01:35 EDT -- an hour gone by -- and a
        // zero-delay arm re-runs the task as fast as the pool can hand it back.
        ZonedDateTime repeatedHour =
                ZonedDateTime.of(LocalDateTime.of(2026, 11, 1, 1, 30), newYork)
                        .withLaterOffsetAtOverlap();
        Recorder executor = new Recorder();
        AtomicInteger runs = new AtomicInteger();

        CronScheduler.start(List.of(contribution("35 * * * *", runs::incrementAndGet)), executor,
                Clock.fixed(repeatedHour.toInstant(), newYork), (task, failure) -> { });
        executor.fire(0);

        assertThat(runs).hasValue(1);
        assertThat(executor.delays()).containsExactly(Duration.ofMinutes(5), Duration.ofMinutes(5));
    }

    @Test
    void aFailingTaskIsReportedWithItsContributionAndTheNextOccurrenceStaysArmed() {
        Recorder executor = new Recorder();
        IllegalStateException failure = new IllegalStateException("reminder query failed");
        List<CronContribution> reported = new ArrayList<>();
        CronContribution contribution = contribution("*/15 * * * *", () -> { throw failure; });

        CronScheduler.start(List.of(contribution), executor, FIXED, (task, error) -> {
            assertThat(error).isSameAs(failure);
            reported.add(task);
        });
        executor.fire(0);

        assertThat(reported).containsExactly(contribution);
        assertThat(executor.delays()).hasSize(2);
    }

    @Test
    void closingCancelsTheArmedTaskAndStopsItFromRunningOrReArming() {
        Recorder executor = new Recorder();
        AtomicInteger runs = new AtomicInteger();
        CronScheduler scheduler = CronScheduler.start(
                List.of(contribution("*/15 * * * *", runs::incrementAndGet)),
                executor, FIXED, (task, failure) -> { });

        scheduler.close();
        executor.fire(0);

        assertThat(runs).hasValue(0);
        assertThat(executor.delays()).hasSize(1);
        assertThat(executor.shutdown).isTrue();
    }

    @Test
    void thePoolRunsOnNamedDaemonThreadsSoALeakedSchedulerIsVisible() throws Exception {
        ScheduledExecutorService pool = CronScheduler.newPool(1);
        try {
            Thread running = pool.submit(() -> Thread.currentThread()).get();
            assertThat(running.getName()).startsWith("aura-cron-");
            assertThat(running.isDaemon()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closingTheRealPoolEndsItsThreadsWithoutRunningAnything() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ScheduledExecutorService pool = CronScheduler.newPool(1);
        // The yearly schedule keeps the fire time far enough away that only a broken close
        // could let the task run.
        CronScheduler scheduler = CronScheduler.start(
                List.of(contribution("0 0 1 1 *", runs::incrementAndGet)),
                pool, Clock.systemDefaultZone(), (task, failure) -> { });

        scheduler.close();

        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runs).hasValue(0);
        assertThat(awaitNoCronThreads()).isEmpty();
    }

    /**
     * A worker can still be listed by {@link Thread#getAllStackTraces()} for a moment after
     * the pool reports termination, while it unwinds its run loop. Wait for that, rather
     * than failing on the first snapshot.
     */
    private static List<String> awaitNoCronThreads() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<String> live = liveCronThreads();
        while (!live.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            live = liveCronThreads();
        }
        return live;
    }

    private static List<String> liveCronThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("aura-cron-"))
                .toList();
    }

    private static CronContribution contribution(String expression, Runnable task) {
        return new CronContribution(CronSchedule.parse(expression), task, "reminder");
    }

    private CronContribution contribution(String expression) {
        return contribution(expression, () -> { });
    }

    /** A scheduled executor that only records arming, so no test waits on real time. */
    private static final class Recorder implements ScheduledExecutorService {
        private final List<Runnable> commands = new ArrayList<>();
        private final List<Duration> delays = new ArrayList<>();
        private boolean shutdown;

        List<Duration> delays() { return List.copyOf(delays); }

        void fire(int index) { commands.get(index).run(); }

        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            commands.add(command);
            delays.add(Duration.ofNanos(unit.toNanos(delay)));
            return new NoopFuture();
        }

        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("cron arms a Runnable, never a Callable");
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("cron re-arms instead of fixed-rate firing");
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("cron re-arms instead of fixed-delay firing");
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public <T> Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }
        @Override public Future<?> submit(Runnable task) { throw new UnsupportedOperationException(); }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout,
                TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public void execute(Runnable command) { throw new UnsupportedOperationException(); }

        private static final class NoopFuture implements ScheduledFuture<Object> {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return true; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return false; }
            @Override public Object get() { return null; }
            @Override public Object get(long timeout, TimeUnit unit) { return null; }
            @Override public long getDelay(TimeUnit unit) { return 0; }
            @Override public int compareTo(java.util.concurrent.Delayed other) { return 0; }
        }
    }
}

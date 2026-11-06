package io.aura.workflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowClockTest {
    @Test
    void retryBackoffRunsOnVirtualTimeWithoutRealSleeping() {
        MutableClock clock = new MutableClock();
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition<Object> workflow = Workflow.<Object>builder("virtual-retry", clock)
                .step("unstable", context -> {
                    if (attempts.incrementAndGet() < 3) throw new IllegalStateException("unavailable");
                    return "ok";
                }, StepMode.IDEMPOTENT, RetryPolicy.fixed(3, Duration.ofMinutes(30)))
                .build();

        long testStart = System.nanoTime();
        WorkflowResult result = workflow.execute(new Object());
        long elapsedMillis = (System.nanoTime() - testStart) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(1_000);
        assertThat(attempts).hasValue(3);
        assertThat(result.succeeded()).isTrue();
        StepExecution step = result.execution().step("unstable");
        assertThat(step.startedAt()).isEqualTo(Instant.EPOCH);
        assertThat(step.finishedAt()).isEqualTo(Instant.EPOCH.plus(Duration.ofHours(1)));
        assertThat(result.execution().finishedAt()).isEqualTo(Instant.EPOCH.plus(Duration.ofHours(1)));
    }

    @Test
    void exhaustedRetryBudgetAccumulatesEveryBackoffDelay() {
        MutableClock clock = new MutableClock();
        WorkflowDefinition<Object> workflow = Workflow.<Object>builder("virtual-exhaust", clock)
                .step("unstable", context -> { throw new IllegalStateException("always down"); },
                        StepMode.IDEMPOTENT, RetryPolicy.fixed(4, Duration.ofDays(1)))
                .build();

        WorkflowResult result = workflow.execute(new Object());

        assertThat(result.execution().status()).isEqualTo(WorkflowExecution.Status.FAILED);
        assertThat(result.execution().step("unstable").attempts()).isEqualTo(4);
        assertThat(result.execution().step("unstable").finishedAt())
                .isEqualTo(Instant.EPOCH.plus(Duration.ofDays(3)));
        assertThat(result.execution().finishedAt())
                .isEqualTo(Instant.EPOCH.plus(Duration.ofDays(3)));
    }

    @Test
    void leaseExpiryAndTakeoverFollowTheInjectedClock() {
        MutableClock clock = new MutableClock();
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore(clock);
        Duration oneDay = Duration.ofDays(1);
        WorkflowLease first = store.tryAcquire("orders", "event-1", "owner-1", oneDay).orElseThrow();
        assertThat(store.currentLease("orders", "event-1")).contains(first);

        clock.advance(oneDay);

        assertThat(store.currentLease("orders", "event-1")).isEmpty();
        assertThat(store.renew(first, oneDay)).isEmpty();
        WorkflowLease takeover = store.tryAcquire("orders", "event-1", "owner-2", oneDay).orElseThrow();
        assertThat(takeover.token()).isNotEqualTo(first.token());
        WorkflowExecution stale = new WorkflowExecution("orders", null, "event-1", clock);
        assertThatThrownBy(() -> store.saveLeased(stale.snapshot(), first))
                .isInstanceOf(WorkflowException.class)
                .extracting("code").isEqualTo(WorkflowException.LEASE_LOST);
        assertThat(store.currentLease("orders", "event-1")).contains(takeover);

        clock.advance(oneDay.minus(Duration.ofMinutes(1)));
        assertThat(store.currentLease("orders", "event-1")).contains(takeover);
        clock.advance(Duration.ofMinutes(1));
        assertThat(store.currentLease("orders", "event-1")).isEmpty();
    }

    @Test
    void advanceMovesWallAndMonotonicReadingsTogether() {
        MutableClock clock = new MutableClock();
        long nanosBefore = clock.nanos();

        clock.advance(Duration.ofSeconds(90));

        assertThat(clock.now()).isEqualTo(Instant.EPOCH.plus(Duration.ofSeconds(90)));
        assertThat(clock.nanos() - nanosBefore).isEqualTo(Duration.ofSeconds(90).toNanos());
    }

    @Test
    void virtualSleepConsumesNoRealTimeButAdvancesEveryReading() throws Exception {
        MutableClock clock = new MutableClock();

        long testStart = System.nanoTime();
        clock.sleep(Duration.ofDays(365));
        long elapsedMillis = (System.nanoTime() - testStart) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(100);
        assertThat(clock.now()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(365)));
        assertThat(clock.nanos()).isEqualTo(Duration.ofDays(365).toNanos());
    }
}

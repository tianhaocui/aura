package io.aura.workflow;

import java.time.Duration;
import java.time.Instant;

/** Test clock that stays frozen until advanced; sleeps are virtual and consume no real time. */
public final class MutableClock implements WorkflowClock {
    private Instant wall;
    private long monotonicNanos;

    public MutableClock() { this(Instant.EPOCH); }

    public MutableClock(Instant start) {
        this.wall = java.util.Objects.requireNonNull(start, "start");
    }

    @Override public synchronized Instant now() { return wall; }

    @Override public synchronized long nanos() { return monotonicNanos; }

    @Override public void sleep(Duration delay) { advance(delay); }

    /** Wall and monotonic readings move together so lease expiry and renewal deadlines stay consistent. */
    public synchronized void advance(Duration duration) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        wall = wall.plus(duration);
        monotonicNanos += duration.toNanos();
    }
}

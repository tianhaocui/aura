package io.aura.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Time source for workflow timing: wall readings for timestamps, monotonic readings for deadlines. */
public interface WorkflowClock {
    Instant now();
    long nanos();
    void sleep(Duration delay) throws InterruptedException;

    WorkflowClock SYSTEM = new WorkflowClock() {
        @Override public Instant now() { return Instant.now(); }
        @Override public long nanos() { return System.nanoTime(); }
        @Override public void sleep(Duration delay) throws InterruptedException {
            long nanos = delay.toNanos();
            long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
            int restNanos = (int) (nanos - TimeUnit.MILLISECONDS.toNanos(millis));
            Thread.sleep(millis, restNanos);
        }
    };
}

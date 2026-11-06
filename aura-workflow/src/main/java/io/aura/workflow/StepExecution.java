package io.aura.workflow;

import java.time.Instant;

public final class StepExecution {
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, COMPENSATED, CANCELLED }

    private final String stepId;
    private final StepMode mode;
    private final RetryPolicy retryPolicy;
    private Status status = Status.PENDING;
    private int attempts;
    private Object result;
    private Throwable error;
    private Instant startedAt;
    private Instant finishedAt;
    private final WorkflowClock clock;

    StepExecution(String stepId, StepMode mode, RetryPolicy retryPolicy) {
        this(stepId, mode, retryPolicy, WorkflowClock.SYSTEM);
    }

    StepExecution(String stepId, StepMode mode, RetryPolicy retryPolicy, WorkflowClock clock) {
        this.stepId = stepId;
        this.mode = mode;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    private StepExecution(String stepId, StepMode mode, RetryPolicy retryPolicy,
                          Status status, int attempts, Object result, Throwable error,
                          Instant startedAt, Instant finishedAt, WorkflowClock clock) {
        this.stepId = stepId;
        this.mode = mode;
        this.retryPolicy = retryPolicy;
        this.status = status;
        this.attempts = attempts;
        this.result = result;
        this.startedAt = startedAt;
        this.error = error;
        this.finishedAt = finishedAt;
        this.clock = clock;
    }

    StepExecution copy() {
        return new StepExecution(stepId, mode, retryPolicy, status, attempts, result,
                error, startedAt, finishedAt, clock);
    }

    void start() { status = Status.RUNNING; startedAt = clock.now(); }
    void success(Object value) { result = value; status = Status.SUCCEEDED; finishedAt = clock.now(); }
    void fail(Throwable cause) { error = cause; status = Status.FAILED; finishedAt = clock.now(); }
    void cancel(Throwable cause) { error = cause; status = Status.CANCELLED; finishedAt = clock.now(); }
    void compensated() { status = Status.COMPENSATED; }
    void attempt() { attempts++; }
    public String stepId() { return stepId; }
    public StepMode mode() { return mode; }
    public RetryPolicy retryPolicy() { return retryPolicy; }
    public Status status() { return status; }
    public int attempts() { return attempts; }
    public Object result() { return result; }
    public Throwable error() { return error; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
}

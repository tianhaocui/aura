package io.aura.workflow;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class WorkflowExecution {
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private final String executionId = UUID.randomUUID().toString();
    private final String workflowId;
    private final String variant;
    private final Map<String, StepExecution> steps = new LinkedHashMap<>();
    private Status status = Status.PENDING;
    private Throwable error;
    private final Instant startedAt = Instant.now();
    private Instant finishedAt;

    WorkflowExecution(String workflowId, String variant) {
        this.workflowId = workflowId;
        this.variant = variant;
    }
    void start() { status = Status.RUNNING; }
    void success() { status = Status.SUCCEEDED; finishedAt = Instant.now(); }
    void fail(Throwable cause) { status = Status.FAILED; error = cause; finishedAt = Instant.now(); }
    void cancel(Throwable cause) { status = Status.CANCELLED; error = cause; finishedAt = Instant.now(); }
    void add(StepExecution step) { steps.put(step.stepId(), step); }
    public String executionId() { return executionId; }
    public String workflowId() { return workflowId; }
    public String variant() { return variant; }
    public Status status() { return status; }
    public Throwable error() { return error; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public Map<String, StepExecution> steps() { return Collections.unmodifiableMap(steps); }
    public java.util.List<StepExecution> stepExecutions() { return java.util.List.copyOf(steps.values()); }
    public StepExecution step(String id) { return steps.get(id); }
}

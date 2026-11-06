package io.aura.workflow;

import java.util.Objects;

public final class WorkflowStepSpec<C> {
    private final String id;
    private final WorkflowStep<C> step;
    private final StepMode mode;
    private final RetryPolicy retryPolicy;
    private final WorkflowCompensation<C> compensation;

    WorkflowStepSpec(String id, WorkflowStep<C> step, StepMode mode, RetryPolicy retryPolicy,
                     WorkflowCompensation<C> compensation) {
        this.id = Objects.requireNonNull(id, "id");
        this.step = Objects.requireNonNull(step, "step");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.compensation = compensation;
    }
    public String id() { return id; }
    public WorkflowStep<C> step() { return step; }
    public StepMode mode() { return mode; }
    public RetryPolicy retryPolicy() { return retryPolicy; }
    public WorkflowCompensation<C> compensation() { return compensation; }
}

package io.aura.workflow;

import java.util.Objects;

public final class WorkflowResult {
    private final WorkflowExecution execution;
    private final Object value;

    WorkflowResult(WorkflowExecution execution, Object value) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.value = value;
    }
    public WorkflowExecution execution() { return execution; }
    public Object value() { return value; }
    public Object result() { return value; }
    public boolean succeeded() { return execution.status() == WorkflowExecution.Status.SUCCEEDED; }
    public boolean failed() { return !succeeded(); }
}

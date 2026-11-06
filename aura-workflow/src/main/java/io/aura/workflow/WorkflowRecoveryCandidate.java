package io.aura.workflow;

/** Persisted non-terminal execution requiring explicit business reconciliation. */
public record WorkflowRecoveryCandidate(WorkflowExecutionSnapshot snapshot, boolean currentlyLeased) {
    public WorkflowRecoveryCandidate {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        if (snapshot.status() != WorkflowExecution.Status.PENDING
                && snapshot.status() != WorkflowExecution.Status.RUNNING) {
            throw new IllegalArgumentException("recovery candidate must be non-terminal");
        }
    }
}

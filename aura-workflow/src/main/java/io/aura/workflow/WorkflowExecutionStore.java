package io.aura.workflow;

import java.util.List;
import java.util.Optional;

/** Persistence extension point for workflow execution state. */
public interface WorkflowExecutionStore {
    void save(WorkflowExecution execution);
    Optional<WorkflowExecution> find(String executionId);
    List<WorkflowExecution> list(String workflowId);

    /** Returns the retained execution for a workflow/key pair, when supported by the store. */
    default Optional<WorkflowExecution> findByIdempotencyKey(String workflowId, String idempotencyKey) {
        return Optional.empty();
    }
}

package io.aura.workflow;

import java.util.List;
import java.util.Optional;

/** Persistence extension point for workflow execution state. */
public interface WorkflowExecutionStore {
    /** Optional process-local inspection hook. Implementations must not persist this mutable object. */
    default void retainLocal(WorkflowExecution execution) { }

    /** Persists the immutable, redacted execution projection. */
    void saveSnapshot(WorkflowExecutionSnapshot snapshot);
    /** Optional read path for durable, redacted execution projections. */
    default Optional<WorkflowExecutionSnapshot> findSnapshot(String executionId) {
        return Optional.empty();
    }
    /** Optional listing path for durable execution projections. */
    default List<WorkflowExecutionSnapshot> listSnapshots(String workflowId) {
        return List.of();
    }
    Optional<WorkflowExecution> find(String executionId);
    List<WorkflowExecution> list(String workflowId);

    /** Returns the retained execution for a workflow/key pair, when supported by the store. */
    default Optional<WorkflowExecution> findByIdempotencyKey(String workflowId, String idempotencyKey) {
        return Optional.empty();
    }
}

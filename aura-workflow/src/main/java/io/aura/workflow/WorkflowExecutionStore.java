package io.aura.workflow;

import java.util.List;
import java.util.Optional;

/** Persistence extension point for workflow execution state. */
public interface WorkflowExecutionStore {
    void save(WorkflowExecution execution);

    /**
     * Optional durable projection hook. Implementations should persist this
     * snapshot rather than retaining a mutable execution reference.
     */
    default void saveSnapshot(WorkflowExecutionSnapshot snapshot) { }
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

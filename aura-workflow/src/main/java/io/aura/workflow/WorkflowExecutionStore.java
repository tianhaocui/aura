package io.aura.workflow;

import java.util.List;
import java.util.Optional;

/** Persistence extension point for workflow execution state. */
public interface WorkflowExecutionStore {
    void save(WorkflowExecution execution);
    Optional<WorkflowExecution> find(String executionId);
    List<WorkflowExecution> list(String workflowId);
}

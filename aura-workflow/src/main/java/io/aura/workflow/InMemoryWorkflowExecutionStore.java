package io.aura.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded-process default store for tests and single-process applications. */
public final class InMemoryWorkflowExecutionStore implements WorkflowExecutionStore {
    private final ConcurrentHashMap<String, WorkflowExecution> executions = new ConcurrentHashMap<>();

    @Override public void save(WorkflowExecution execution) {
        if (execution == null) throw new IllegalArgumentException("execution must not be null");
        executions.put(execution.executionId(), execution);
    }

    @Override public Optional<WorkflowExecution> find(String executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override public List<WorkflowExecution> list(String workflowId) {
        List<WorkflowExecution> result = new ArrayList<>();
        executions.values().stream()
                .filter(execution -> workflowId == null || workflowId.equals(execution.workflowId()))
                .sorted(java.util.Comparator.comparing(WorkflowExecution::startedAt))
                .forEach(result::add);
        return List.copyOf(result);
    }
}

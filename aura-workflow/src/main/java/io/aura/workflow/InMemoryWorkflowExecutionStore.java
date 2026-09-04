package io.aura.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Bounded-process default store for tests and single-process applications. */
public final class InMemoryWorkflowExecutionStore implements WorkflowExecutionStore {
    private final ConcurrentHashMap<String, WorkflowExecution> executions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final int maxExecutions;

    public InMemoryWorkflowExecutionStore() { this(10_000); }

    public InMemoryWorkflowExecutionStore(int maxExecutions) {
        if (maxExecutions < 1) throw new IllegalArgumentException("maxExecutions must be positive");
        this.maxExecutions = maxExecutions;
    }

    public int maxExecutions() { return maxExecutions; }

    @Override public void save(WorkflowExecution execution) {
        if (execution == null) throw new IllegalArgumentException("execution must not be null");
        if (executions.putIfAbsent(execution.executionId(), execution) == null) {
            insertionOrder.offer(execution.executionId());
        } else {
            executions.put(execution.executionId(), execution);
        }
        while (executions.size() > maxExecutions) {
            String oldest = insertionOrder.poll();
            if (oldest == null || executions.remove(oldest) == null) break;
        }
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

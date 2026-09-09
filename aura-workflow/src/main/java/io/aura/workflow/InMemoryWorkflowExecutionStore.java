package io.aura.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Bounded-process default store for tests and single-process applications. */
public final class InMemoryWorkflowExecutionStore implements WorkflowExecutionStore {
    private final ConcurrentHashMap<String, WorkflowExecution> executions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkflowExecutionSnapshot> snapshots = new ConcurrentHashMap<>();
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
        String key = key(execution.workflowId(), execution.idempotencyKey());
        if (key != null) {
            String existing = idempotencyKeys.putIfAbsent(key, execution.executionId());
            if (existing != null && !existing.equals(execution.executionId())) {
                throw new WorkflowException(WorkflowException.IDEMPOTENCY_CONFLICT,
                        "Idempotency key is already used for workflow " + execution.workflowId());
            }
        }
        if (executions.putIfAbsent(execution.executionId(), execution) == null) {
            insertionOrder.offer(execution.executionId());
        } else {
            executions.put(execution.executionId(), execution);
        }
        saveSnapshot(execution.snapshot());
        while (executions.size() > maxExecutions) {
            String oldest = insertionOrder.poll();
            if (oldest == null) break;
            WorkflowExecution removed = executions.remove(oldest);
            snapshots.remove(oldest);
            if (removed != null && removed.idempotencyKey() != null) {
                idempotencyKeys.remove(key(removed.workflowId(), removed.idempotencyKey()), removed.executionId());
            }
        }
    }

    @Override public void saveSnapshot(WorkflowExecutionSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        snapshots.put(snapshot.executionId(), snapshot);
    }

    public Optional<WorkflowExecutionSnapshot> findSnapshot(String executionId) {
        return Optional.ofNullable(snapshots.get(executionId));
    }

    @Override public Optional<WorkflowExecution> find(String executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override public Optional<WorkflowExecution> findByIdempotencyKey(String workflowId, String idempotencyKey) {
        String executionId = idempotencyKeys.get(key(workflowId, idempotencyKey));
        return executionId == null ? Optional.empty() : find(executionId);
    }

    @Override public List<WorkflowExecution> list(String workflowId) {
        List<WorkflowExecution> result = new ArrayList<>();
        executions.values().stream()
                .filter(execution -> workflowId == null || workflowId.equals(execution.workflowId()))
                .sorted(java.util.Comparator.comparing(WorkflowExecution::startedAt))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private static String key(String workflowId, String idempotencyKey) {
        if (workflowId == null || idempotencyKey == null || idempotencyKey.isBlank()) return null;
        return workflowId + "\u0000" + idempotencyKey;
    }
}

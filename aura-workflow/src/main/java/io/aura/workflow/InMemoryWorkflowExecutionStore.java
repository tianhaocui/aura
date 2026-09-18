package io.aura.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Bounded-process default store for tests and single-process applications. */
public final class InMemoryWorkflowExecutionStore implements DurableWorkflowExecutionStore {
    private final ConcurrentHashMap<String, WorkflowExecution> executions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkflowExecutionSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, WorkflowLease> leases = new ConcurrentHashMap<>();
    private final int maxExecutions;

    public InMemoryWorkflowExecutionStore() { this(10_000); }

    public InMemoryWorkflowExecutionStore(int maxExecutions) {
        if (maxExecutions < 1) throw new IllegalArgumentException("maxExecutions must be positive");
        this.maxExecutions = maxExecutions;
    }

    public int maxExecutions() { return maxExecutions; }

    @Override public synchronized void retainLocal(WorkflowExecution execution) {
        if (execution == null) throw new IllegalArgumentException("execution must not be null");
        WorkflowExecution stored = execution.copy();
        if (!snapshots.containsKey(stored.executionId())) return;
        String key = key(stored.workflowId(), stored.idempotencyKey());
        if (key != null) {
            String existing = idempotencyKeys.putIfAbsent(key, stored.executionId());
            if (existing != null && !existing.equals(stored.executionId())) {
                throw new WorkflowException(WorkflowException.IDEMPOTENCY_CONFLICT,
                        "Idempotency key is already used for workflow " + stored.workflowId());
            }
        }
        executions.put(stored.executionId(), stored);
    }

    @Override public synchronized void saveSnapshot(WorkflowExecutionSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        String key = key(snapshot.workflowId(), snapshot.idempotencyKey());
        if (key != null) {
            String existing = idempotencyKeys.putIfAbsent(key, snapshot.executionId());
            if (existing != null && !existing.equals(snapshot.executionId())) {
                throw new WorkflowException(WorkflowException.IDEMPOTENCY_CONFLICT,
                        "Idempotency key is already used for workflow " + snapshot.workflowId());
            }
        }
        if (snapshots.putIfAbsent(snapshot.executionId(), snapshot) == null) {
            insertionOrder.offer(snapshot.executionId());
        } else {
            snapshots.put(snapshot.executionId(), snapshot);
        }
        while (snapshots.size() > maxExecutions) {
            String oldest = insertionOrder.poll();
            if (oldest == null) break;
            WorkflowExecutionSnapshot removedSnapshot = snapshots.remove(oldest);
            executions.remove(oldest);
            if (removedSnapshot != null && removedSnapshot.idempotencyKey() != null) {
                idempotencyKeys.remove(key(
                        removedSnapshot.workflowId(), removedSnapshot.idempotencyKey()), oldest);
            }
        }
    }

    @Override public Optional<WorkflowExecutionSnapshot> findSnapshot(String executionId) {
        return Optional.ofNullable(snapshots.get(executionId));
    }

    @Override public List<WorkflowExecutionSnapshot> listSnapshots(String workflowId) {
        return snapshots.values().stream()
                .filter(snapshot -> workflowId == null || workflowId.equals(snapshot.workflowId()))
                .sorted(java.util.Comparator.comparing(WorkflowExecutionSnapshot::startedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .toList();
    }

    @Override public Optional<WorkflowExecution> find(String executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override public Optional<WorkflowExecution> findByIdempotencyKey(String workflowId, String idempotencyKey) {
        String executionId = idempotencyKeys.get(key(workflowId, idempotencyKey));
        return executionId == null ? Optional.empty() : find(executionId);
    }

    @Override public Optional<WorkflowExecutionSnapshot> findSnapshotByIdempotencyKey(
            String workflowId, String idempotencyKey) {
        String executionId = idempotencyKeys.get(key(workflowId, idempotencyKey));
        return executionId == null ? Optional.empty() : findSnapshot(executionId);
    }

    @Override public List<WorkflowExecution> list(String workflowId) {
        List<WorkflowExecution> result = new ArrayList<>();
        executions.values().stream()
                .filter(execution -> workflowId == null || workflowId.equals(execution.workflowId()))
                .sorted(java.util.Comparator.comparing(WorkflowExecution::startedAt))
                .forEach(result::add);
        return List.copyOf(result);
    }

    @Override public synchronized Optional<WorkflowLease> tryAcquire(
            String workflowId, String idempotencyKey, String ownerId, java.time.Duration leaseTtl) {
        validateLeaseRequest(workflowId, idempotencyKey, ownerId, leaseTtl);
        java.time.Instant now = java.time.Instant.now();
        leases.entrySet().removeIf(entry -> entry.getValue().expiredAt(now));
        String key = key(workflowId, idempotencyKey);
        WorkflowLease existing = leases.get(key);
        if (existing != null && !existing.expiredAt(now)) return Optional.empty();
        if (leases.size() >= maxExecutions && existing == null) {
            throw new WorkflowException(WorkflowException.LEASE_CAPACITY_EXHAUSTED,
                    "Workflow lease registry capacity is exhausted");
        }
        WorkflowLease lease = new WorkflowLease(workflowId, idempotencyKey, ownerId,
                java.util.UUID.randomUUID().toString(), now.plus(leaseTtl));
        leases.put(key, lease);
        return Optional.of(lease);
    }

    @Override public synchronized void saveLeased(
            WorkflowExecutionSnapshot snapshot, WorkflowLease lease) {
        if (snapshot == null || lease == null) {
            throw new IllegalArgumentException("leased snapshot and lease are required");
        }
        if (!lease.workflowId().equals(snapshot.workflowId())
                || !lease.idempotencyKey().equals(snapshot.idempotencyKey())) {
            throw new IllegalArgumentException(
                    "leased snapshot must match the lease identity");
        }
        String leaseKey = key(lease.workflowId(), lease.idempotencyKey());
        WorkflowLease current = leases.get(leaseKey);
        java.time.Instant now = java.time.Instant.now();
        if (current == null || current.expiredAt(now)
                || !current.token().equals(lease.token())
                || !current.ownerId().equals(lease.ownerId())) {
            if (current != null && current.expiredAt(now)) leases.remove(leaseKey, current);
            throw new WorkflowException(WorkflowException.LEASE_LOST,
                    "Workflow execution lease was lost before persistence");
        }
        saveSnapshot(snapshot);
    }

    @Override public synchronized Optional<WorkflowLease> renew(
            WorkflowLease lease, java.time.Duration leaseTtl) {
        if (lease == null) return Optional.empty();
        validateLeaseRequest(lease.workflowId(), lease.idempotencyKey(), lease.ownerId(), leaseTtl);
        String key = key(lease.workflowId(), lease.idempotencyKey());
        WorkflowLease current = leases.get(key);
        java.time.Instant now = java.time.Instant.now();
        if (current == null || current.expiredAt(now)
                || !current.token().equals(lease.token())
                || !current.ownerId().equals(lease.ownerId())) {
            if (current != null && current.expiredAt(now)) leases.remove(key, current);
            return Optional.empty();
        }
        WorkflowLease renewed = new WorkflowLease(lease.workflowId(), lease.idempotencyKey(),
                lease.ownerId(), lease.token(), now.plus(leaseTtl));
        leases.put(key, renewed);
        return Optional.of(renewed);
    }

    @Override public synchronized void release(WorkflowLease lease) {
        if (lease == null) return;
        String key = key(lease.workflowId(), lease.idempotencyKey());
        WorkflowLease current = leases.get(key);
        if (current != null && current.token().equals(lease.token())
                && current.ownerId().equals(lease.ownerId())) leases.remove(key, current);
    }

    @Override public synchronized Optional<WorkflowLease> currentLease(
            String workflowId, String idempotencyKey) {
        String key = key(workflowId, idempotencyKey);
        if (key == null) return Optional.empty();
        WorkflowLease current = leases.get(key);
        if (current != null && current.expiredAt(java.time.Instant.now())) {
            leases.remove(key, current);
            return Optional.empty();
        }
        return Optional.ofNullable(current);
    }

    private static void validateLeaseRequest(String workflowId, String idempotencyKey,
                                             String ownerId, java.time.Duration leaseTtl) {
        if (workflowId == null || workflowId.isBlank()) throw new IllegalArgumentException("workflowId must not be blank");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId must not be blank");
        if (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
    }

    private static String key(String workflowId, String idempotencyKey) {
        if (workflowId == null || idempotencyKey == null || idempotencyKey.isBlank()) return null;
        return workflowId + "\u0000" + idempotencyKey;
    }
}

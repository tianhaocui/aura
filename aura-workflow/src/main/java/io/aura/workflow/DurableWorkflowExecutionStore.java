package io.aura.workflow;

import java.time.Duration;
import java.util.Optional;

/**
 * Store extension for atomic cross-process ownership. Implementations must make
 * acquire/renew/release compare-and-set operations durable and process-safe.
 */
public interface DurableWorkflowExecutionStore extends WorkflowExecutionStore {
    /**
     * Atomically validates the unexpired fencing token, verifies that the
     * snapshot identity matches the lease, reserves the workflow/idempotency
     * key, and persists the redacted snapshot. Implementations must never
     * persist arbitrary step results or raw Throwable instances.
     */
    void saveLeased(WorkflowExecutionSnapshot snapshot, WorkflowLease lease);

    @Override void saveSnapshot(WorkflowExecutionSnapshot snapshot);

    @Override Optional<WorkflowExecutionSnapshot> findSnapshot(String executionId);

    @Override java.util.List<WorkflowExecutionSnapshot> listSnapshots(String workflowId);

    Optional<WorkflowExecutionSnapshot> findSnapshotByIdempotencyKey(
            String workflowId, String idempotencyKey);

    /**
     * Returns empty only when another owner currently holds the lease.
     * Capacity or backend failures must throw a stable WorkflowException.
     */
    Optional<WorkflowLease> tryAcquire(String workflowId, String idempotencyKey,
                                       String ownerId, Duration leaseTtl);

    Optional<WorkflowLease> renew(WorkflowLease lease, Duration leaseTtl);

    void release(WorkflowLease lease);

    Optional<WorkflowLease> currentLease(String workflowId, String idempotencyKey);
}

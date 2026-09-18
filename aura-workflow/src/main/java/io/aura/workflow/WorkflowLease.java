package io.aura.workflow;

import java.time.Instant;

/** Opaque durable lease proving ownership of one workflow/idempotency key. */
public record WorkflowLease(String workflowId, String idempotencyKey, String ownerId,
                            String token, Instant expiresAt) {
    public WorkflowLease {
        if (workflowId == null || workflowId.isBlank()) throw new IllegalArgumentException("workflowId must not be blank");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId must not be blank");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("lease token must not be blank");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt must not be null");
    }

    public boolean expiredAt(Instant instant) {
        return !expiresAt.isAfter(instant);
    }
}

package io.aura.workflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, transport-neutral execution view intended for persistence and
 * AI diagnostics. It deliberately excludes arbitrary step results and raw
 * exception messages, which may contain business secrets.
 */
public record WorkflowExecutionSnapshot(
        String executionId,
        String workflowId,
        String variant,
        String idempotencyKey,
        WorkflowExecution.Status status,
        Instant startedAt,
        Instant finishedAt,
        String errorType,
        String errorCode,
        List<StepSnapshot> steps) {

    public WorkflowExecutionSnapshot {
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("executionId must not be blank");
        if (workflowId == null || workflowId.isBlank()) throw new IllegalArgumentException("workflowId must not be blank");
        status = status == null ? WorkflowExecution.Status.PENDING : status;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static WorkflowExecutionSnapshot from(WorkflowExecution execution) {
        if (execution == null) throw new IllegalArgumentException("execution must not be null");
        Throwable error = execution.error();
        String errorCode = error instanceof WorkflowException workflowError ? workflowError.code() : null;
        List<StepSnapshot> steps = execution.stepExecutions().stream()
                .map(StepSnapshot::from)
                .toList();
        return new WorkflowExecutionSnapshot(execution.executionId(), execution.workflowId(), execution.variant(),
                execution.idempotencyKey(), execution.status(), execution.startedAt(), execution.finishedAt(),
                error == null ? null : error.getClass().getSimpleName(), errorCode, steps);
    }

    /** JSON-friendly representation with no arbitrary business objects. */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", executionId);
        result.put("workflowId", workflowId);
        if (variant != null) result.put("variant", variant);
        if (idempotencyKey != null) result.put("idempotencyKey", idempotencyKey);
        result.put("status", status.name());
        result.put("startedAt", startedAt == null ? null : startedAt.toString());
        result.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        if (errorType != null) result.put("errorType", errorType);
        if (errorCode != null) result.put("errorCode", errorCode);
        result.put("steps", steps.stream().map(StepSnapshot::toMap).toList());
        return java.util.Collections.unmodifiableMap(result);
    }

    public record StepSnapshot(String stepId, StepExecution.Status status, int attempts,
                               Instant startedAt, Instant finishedAt) {
        public StepSnapshot {
            if (stepId == null || stepId.isBlank()) throw new IllegalArgumentException("stepId must not be blank");
            status = status == null ? StepExecution.Status.PENDING : status;
            if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        }

        static StepSnapshot from(StepExecution execution) {
            return new StepSnapshot(execution.stepId(), execution.status(), execution.attempts(),
                    execution.startedAt(), execution.finishedAt());
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stepId", stepId);
            result.put("status", status.name());
            result.put("attempts", attempts);
            result.put("startedAt", startedAt == null ? null : startedAt.toString());
            result.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            return java.util.Collections.unmodifiableMap(result);
        }
    }
}

package io.aura.workflow;

public class WorkflowException extends RuntimeException {
    public static final String MISSING_STEP = "MISSING_STEP";
    public static final String DUPLICATE_STEP = "DUPLICATE_STEP";
    public static final String DEPENDENCY_CYCLE = "DEPENDENCY_CYCLE";
    public static final String ORDER_CONFLICT = "ORDER_CONFLICT";
    public static final String UNKNOWN_VARIANT = "UNKNOWN_VARIANT";
    public static final String STEP_FAILED = "STEP_FAILED";
    public static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    public static final String COMPENSATION_FAILED = "COMPENSATION_FAILED";
    public static final String REGISTRATION_CONFLICT = "REGISTRATION_CONFLICT";
    public static final String UNKNOWN_WORKFLOW = "UNKNOWN_WORKFLOW";
    public static final String EXECUTOR_SATURATED = "EXECUTOR_SATURATED";
    public static final String CANCELLED = "CANCELLED";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String IDEMPOTENCY_RETAINED = "IDEMPOTENCY_RETAINED";
    public static final String LEASE_UNAVAILABLE = "LEASE_UNAVAILABLE";
    public static final String LEASE_LOST = "LEASE_LOST";
    public static final String LEASE_CAPACITY_EXHAUSTED = "LEASE_CAPACITY_EXHAUSTED";
    public static final String STORE_FAILURE = "STORE_FAILURE";

    private final String code;
    private final java.util.Map<String, Object> details;

    public WorkflowException(String code, String message) {
        this(code, message, null, java.util.Map.of());
    }
    public WorkflowException(String code, String message, Throwable cause) {
        this(code, message, cause, java.util.Map.of());
    }
    public WorkflowException(String code, String message, java.util.Map<String, ?> details) {
        this(code, message, null, details);
    }
    public WorkflowException(String code, String message, Throwable cause,
                             java.util.Map<String, ?> details) {
        super(message, cause);
        this.code = code;
        this.details = details == null ? java.util.Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
    }
    public String code() { return code; }
    public java.util.Map<String, Object> details() { return details; }
}

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

    private final String code;

    public WorkflowException(String code, String message) { super(message); this.code = code; }
    public WorkflowException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}

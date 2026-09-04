package io.aura.workflow;

/** Execution metadata. It never causes the runtime to repeat a step by itself. */
public enum StepMode {
    NORMAL(false, false),
    IDEMPOTENT(true, false),
    COMPENSATABLE(false, true),
    IDEMPOTENT_COMPENSATABLE(true, true);

    private final boolean idempotent;
    private final boolean compensatable;

    StepMode(boolean idempotent, boolean compensatable) {
        this.idempotent = idempotent;
        this.compensatable = compensatable;
    }

    public boolean idempotent() { return idempotent; }
    public boolean compensatable() { return compensatable; }
}

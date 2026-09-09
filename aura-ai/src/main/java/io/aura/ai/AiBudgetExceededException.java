package io.aura.ai;

/** Stable budget failure that callers can branch on without parsing messages. */
public final class AiBudgetExceededException extends RuntimeException {
    public static final String EXCEEDED = "AI_BUDGET_EXCEEDED";
    public static final String USAGE_MISSING = "AI_BUDGET_USAGE_MISSING";

    private final String code;
    private final String dimension;
    private final long limit;
    private final long actual;

    AiBudgetExceededException(String code, String dimension, long limit, long actual) {
        super(code + ": " + dimension + " limit " + limit + ", actual " + actual);
        this.code = code;
        this.dimension = dimension;
        this.limit = limit;
        this.actual = actual;
    }

    static AiBudgetExceededException exceeded(String dimension, long limit, long actual) {
        return new AiBudgetExceededException(EXCEEDED, dimension, limit, actual);
    }

    static AiBudgetExceededException missing(String dimension, long limit) {
        return new AiBudgetExceededException(USAGE_MISSING, dimension, limit, -1);
    }

    public String code() { return code; }
    public String dimension() { return dimension; }
    public long limit() { return limit; }
    public long actual() { return actual; }
}

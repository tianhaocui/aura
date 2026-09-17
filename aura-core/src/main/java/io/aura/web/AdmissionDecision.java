package io.aura.web;

import java.util.concurrent.atomic.AtomicBoolean;

/** Admission result plus an idempotent lease released when the exchange completes. */
public final class AdmissionDecision implements AutoCloseable {
    private static final Runnable NOOP = () -> { };

    private final boolean allowed;
    private final int status;
    private final String code;
    private final int retryAfterSeconds;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AdmissionDecision(boolean allowed, int status, String code,
                              int retryAfterSeconds, Runnable release) {
        this.allowed = allowed;
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        this.release = release == null ? NOOP : release;
    }

    public static AdmissionDecision allow() {
        return new AdmissionDecision(true, 200, null, 0, NOOP);
    }

    public static AdmissionDecision allow(Runnable release) {
        return new AdmissionDecision(true, 200, null, 0, release);
    }

    public static AdmissionDecision reject(int status, String code, int retryAfterSeconds) {
        if (status < 400 || status > 599) throw new IllegalArgumentException("rejection status must be 4xx or 5xx");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("rejection code must not be blank");
        if (retryAfterSeconds < 0) throw new IllegalArgumentException("retryAfterSeconds cannot be negative");
        return new AdmissionDecision(false, status, code, retryAfterSeconds, NOOP);
    }

    public boolean allowed() { return allowed; }
    public int status() { return status; }
    public String code() { return code; }
    public int retryAfterSeconds() { return retryAfterSeconds; }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) release.run();
    }
}

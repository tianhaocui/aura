package io.aura.web;

/** Terminal decision of the guard chain; each transport renders it in its own way. */
public record GuardOutcome(boolean proceed, int status, String body) {

    /** The request may continue down the chain. */
    public static GuardOutcome next() {
        return new GuardOutcome(true, 0, null);
    }

    /** Rejection using the JSON error shape every built-in rejection already served. */
    public static GuardOutcome reject(int status, String message) {
        return payload(status, "{\"error\":\"" + message + "\"}");
    }

    /** Rejection with a caller-supplied JSON body, e.g. an admission policy error code. */
    public static GuardOutcome payload(int status, String jsonBody) {
        return new GuardOutcome(false, status, jsonBody);
    }

    /** A terminal response with no body, e.g. a CORS preflight. */
    public static GuardOutcome stop(int status) {
        return new GuardOutcome(false, status, null);
    }
}

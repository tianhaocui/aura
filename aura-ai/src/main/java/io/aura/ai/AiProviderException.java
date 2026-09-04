package io.aura.ai;

/** Stable failure returned by an HTTP AI provider without exposing the response body by default. */
public final class AiProviderException extends Exception {
    private final String code;
    private final int status;

    public AiProviderException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
}

package io.aura.ai;

/** Stable failure returned by an HTTP AI provider without exposing the response body by default. */
public final class AiProviderException extends Exception {
    private final String code;
    private final int status;
    private final AiUsage usage;

    public AiProviderException(String code, int status, String message) {
        this(code, status, message, null);
    }

    /**
     * Creates a failure that also reports the provider-side usage already consumed.
     * {@link AiClient} charges it to the request budget before deciding whether to retry.
     */
    public AiProviderException(String code, int status, String message, AiUsage usage) {
        super(message);
        this.code = code;
        this.status = status;
        this.usage = usage;
    }

    public String code() { return code; }
    public int status() { return status; }
    public AiUsage usage() { return usage; }
}

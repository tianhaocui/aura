package io.aura.ai;

/** Stable failure raised when a streaming provider violates the callback lifecycle. */
public final class AiProviderContractException extends RuntimeException {
    public static final String CODE = "AI_PROVIDER_STREAM_CONTRACT";
    private final String code = CODE;

    public AiProviderContractException(String message) { super(message); }
    public AiProviderContractException(String message, Throwable cause) { super(message, cause); }
    public String code() { return code; }
}

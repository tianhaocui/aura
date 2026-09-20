package io.aura.ai;

/** Stable local failure when a provider response violates the requested JSON schema. */
public final class AiStructuredOutputException extends RuntimeException {
    public static final String CODE = "AI_STRUCTURED_OUTPUT_INVALID";
    private final String code = CODE;

    public AiStructuredOutputException(String message) { super(message); }
    public AiStructuredOutputException(String message, Throwable cause) { super(message, cause); }
    public String code() { return code; }
}

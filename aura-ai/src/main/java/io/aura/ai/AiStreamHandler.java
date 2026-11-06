package io.aura.ai;

/**
 * Receives incremental output from a streaming chat call. Aura emits {@code onStart} once,
 * followed by tokens and exactly one of {@code onComplete} or {@code onError}.
 */
@FunctionalInterface
public interface AiStreamHandler {
    void onToken(String token);

    default void onStart() { }
    default void onComplete(AiResponse response) { }
    default void onError(Throwable error) { }
}

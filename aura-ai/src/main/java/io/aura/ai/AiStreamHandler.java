package io.aura.ai;

/** Receives incremental output from a streaming chat call. */
@FunctionalInterface
public interface AiStreamHandler {
    void onToken(String token);

    default void onStart() { }
    default void onComplete(AiResponse response) { }
    default void onError(Throwable error) { }
}

package io.aura.ai;

/** Service-provider interface implemented by concrete AI integrations. */
public interface AiProvider {
    /** Stable provider identifier, used by {@link AiClient#load(String)}. */
    default String name() { return getClass().getName(); }

    AiResponse chat(AiRequest request) throws Exception;

    /** Default streaming adapter for providers that only support complete responses. */
    default void stream(AiRequest request, AiStreamHandler handler) throws Exception {
        request.cancellationToken().throwIfCancelled();
        AiResponse response = chat(request);
        request.cancellationToken().throwIfCancelled();
        handler.onToken(response.content());
        handler.onComplete(response);
    }
}

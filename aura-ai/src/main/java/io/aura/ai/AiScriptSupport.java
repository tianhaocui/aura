package io.aura.ai;

/** Shared outcome handling for the in-memory test providers. */
final class AiScriptSupport {
    private AiScriptSupport() { }

    static AiResponse response(Object outcome) {
        if (outcome instanceof AiResponse response) return response;
        throw new IllegalStateException("scripted outcome must be an AiResponse, got "
                + (outcome == null ? "null" : outcome.getClass().getName()));
    }

    static Exception failure(Throwable error) {
        if (error instanceof Exception checked) return checked;
        if (error instanceof Error fatal) throw fatal;
        return new IllegalStateException(error);
    }

    /** Emits a complete response as whitespace-suffixed tokens, mirroring a real SSE feed. */
    static void emit(AiResponse response, AiStreamHandler handler) {
        handler.onStart();
        for (String token : response.content().split("(?<=\\s)")) {
            if (!token.isEmpty()) handler.onToken(token);
        }
        handler.onComplete(response);
    }
}

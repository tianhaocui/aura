package io.aura.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory provider whose consecutive calls follow a scripted sequence, so retry, fallback and
 * budget behaviour is verifiable in a single test.
 *
 * <p>Each script entry is either an {@link AiResponse} to return or a {@link Throwable} to throw.
 * Calling past the end of the script fails the test rather than silently repeating the last reply.</p>
 */
public final class ScriptedAiProvider implements AiProvider {
    private final Deque<Object> script = new ArrayDeque<>();
    private final List<AiRequest> received = new CopyOnWriteArrayList<>();

    private ScriptedAiProvider() { }

    public static ScriptedAiProvider create() { return new ScriptedAiProvider(); }

    public static ScriptedAiProvider of(Object... outcomes) {
        ScriptedAiProvider provider = new ScriptedAiProvider();
        for (Object outcome : outcomes) {
            if (outcome instanceof AiResponse response) provider.script.add(response);
            else if (outcome instanceof Throwable error) provider.script.add(error);
            else throw new IllegalArgumentException("script entry must be AiResponse or Throwable: "
                    + (outcome == null ? "null" : outcome.getClass().getName()));
        }
        return provider;
    }

    public ScriptedAiProvider then(AiResponse response) {
        script.add(Objects.requireNonNull(response, "response"));
        return this;
    }

    public ScriptedAiProvider then(String content) {
        return then(AiResponse.text(content));
    }

    public ScriptedAiProvider thenThrow(Exception failure) {
        script.add(Objects.requireNonNull(failure, "failure"));
        return this;
    }

    @Override public AiResponse chat(AiRequest request) throws Exception {
        Object outcome = next(request);
        if (outcome instanceof Throwable error) throw AiScriptSupport.failure(error);
        return AiScriptSupport.response(outcome);
    }

    /** Streams the next scripted reply as tokens; a scripted failure reaches {@code onError}. */
    @Override public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
        request.cancellationToken().throwIfCancelled();
        Object outcome = next(request);
        request.cancellationToken().throwIfCancelled();
        if (outcome instanceof Throwable error) {
            handler.onError(error);
            throw AiScriptSupport.failure(error);
        }
        AiScriptSupport.emit(AiScriptSupport.response(outcome), handler);
    }

    public List<AiRequest> received() { return List.copyOf(received); }
    public int callCount() { return received.size(); }
    public int remaining() { return script.size(); }

    private Object next(AiRequest request) {
        received.add(request);
        Object outcome;
        // AiClient runs providers on pool threads, so the deque needs a lock even in single-call tests.
        synchronized (script) {
            if (script.isEmpty()) {
                throw new IllegalStateException("ScriptedAiProvider exhausted after " + received.size()
                        + " call(s); the script has no outcome left");
            }
            outcome = script.poll();
        }
        return outcome;
    }
}

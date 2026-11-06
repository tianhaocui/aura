package io.aura.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Deterministic in-memory provider used to verify AI code without network or real latency.
 *
 * <p>Every factory returns a provider that answers the same way for every request; use
 * {@link ScriptedAiProvider} when consecutive calls must behave differently.</p>
 */
public final class FakeAiProvider implements AiProvider {
    private final Function<AiRequest, Object> behaviour;
    private final List<AiRequest> received = new CopyOnWriteArrayList<>();

    private FakeAiProvider(Function<AiRequest, Object> behaviour) {
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
    }

    public static FakeAiProvider returning(String content) {
        return returning(AiResponse.text(content));
    }

    public static FakeAiProvider returning(AiResponse response) {
        Objects.requireNonNull(response, "response");
        return new FakeAiProvider(request -> response);
    }

    public static FakeAiProvider returning(String content, Map<String, ?> usage) {
        return returning(new AiResponse(content, "fake", usage, null));
    }

    /** Answers each request from its own prompt, for assertion-free reply shaping. */
    public static FakeAiProvider answering(Function<AiRequest, String> reply) {
        Objects.requireNonNull(reply, "reply");
        return new FakeAiProvider(request -> AiResponse.text(reply.apply(request)));
    }

    /** Fails every call, keeping the thrown type so retry policies classify it correctly. */
    public static FakeAiProvider failing(Exception failure) {
        Objects.requireNonNull(failure, "failure");
        return new FakeAiProvider(request -> failure);
    }

    @Override public AiResponse chat(AiRequest request) throws Exception {
        Object outcome = drive(request);
        if (outcome instanceof Throwable error) throw AiScriptSupport.failure(error);
        return AiScriptSupport.response(outcome);
    }

    @Override public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
        request.cancellationToken().throwIfCancelled();
        Object outcome = drive(request);
        request.cancellationToken().throwIfCancelled();
        if (outcome instanceof Throwable error) {
            handler.onError(error);
            throw AiScriptSupport.failure(error);
        }
        AiScriptSupport.emit(AiScriptSupport.response(outcome), handler);
    }

    public List<AiRequest> received() { return List.copyOf(received); }
    public int callCount() { return received.size(); }

    public AiRequest lastRequest() {
        if (received.isEmpty()) throw new IllegalStateException("FakeAiProvider received no request");
        return received.get(received.size() - 1);
    }

    private Object drive(AiRequest request) {
        received.add(request);
        return behaviour.apply(request);
    }
}

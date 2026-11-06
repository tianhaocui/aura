package io.aura.ai;

import com.alibaba.fastjson2.JSON;
import io.aura.contract.CancellationToken;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Controlled RAG request builder that keeps retrieval composition and redaction fixed. */
public final class RagRequestBuilder {
    static final String PROMPT_PREFIX = "Untrusted retrieval data (JSON):\n";
    private final RagPipeline.RagContext context;
    private final AiRequest.Builder delegate;

    RagRequestBuilder(RagPipeline.RagContext context, String systemPrompt, String prompt) {
        this.context = Objects.requireNonNull(context, "context");
        this.delegate = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .prompt(prompt)
                .redactor(new JsonAwareRedactor(prompt));
    }

    public RagPipeline.RagContext context() { return context; }
    public RagRequestBuilder model(String value) { delegate.model(value); return this; }
    public RagRequestBuilder parameter(String name, Object value) {
        delegate.parameter(name, value); return this;
    }
    public RagRequestBuilder parameters(Map<String, ?> values) {
        delegate.parameters(values); return this;
    }
    public RagRequestBuilder timeout(Duration value) { delegate.timeout(value); return this; }
    public RagRequestBuilder cancellationToken(CancellationToken value) {
        delegate.cancellationToken(value); return this;
    }
    public RagRequestBuilder budget(AiBudget value) { delegate.budget(value); return this; }
    public RagRequestBuilder tool(AiToolDefinition value) { delegate.tool(value); return this; }
    public RagRequestBuilder tools(List<AiToolDefinition> values) {
        delegate.tools(values); return this;
    }
    public RagRequestBuilder toolChoice(String value) { delegate.toolChoice(value); return this; }
    public RagRequestBuilder retry(AiRetryPolicy value) { delegate.retry(value); return this; }
    public RagRequestBuilder pricing(AiPricing value) { delegate.pricing(value); return this; }
    public RagRequestBuilder structuredOutput(StructuredOutputSpec value) {
        delegate.structuredOutput(value); return this;
    }
    public AiRequest build() { return delegate.build(); }

    private static final class JsonAwareRedactor implements AiRedactor {
        private final AiRedactor delegate = AiRedactor.defaultRedactor();
        private final String canonicalPrompt;

        private JsonAwareRedactor(String canonicalPrompt) {
            this.canonicalPrompt = canonicalPrompt;
        }

        @Override public String redact(String value) {
            if (!Objects.equals(value, canonicalPrompt)) {
                return delegate.redact(value);
            }
            String encoded = value.substring(PROMPT_PREFIX.length());
            final Map<String, Object> payload;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = JSON.parseObject(encoded, Map.class);
                payload = parsed;
            } catch (RuntimeException parseFailure) {
                throw new IllegalArgumentException("RAG prompt must contain valid JSON", parseFailure);
            }
            Object contextValue = payload.get("context");
            Object questionValue = payload.get("question");
            if (!(contextValue instanceof java.util.Collection<?> contextValues)
                    || contextValues.stream().anyMatch(item -> !(item instanceof String))
                    || !(questionValue instanceof String)) {
                throw new IllegalArgumentException("RAG prompt has an invalid context/question shape");
            }
            return PROMPT_PREFIX + AiJson.stringify(delegate.redactValue(payload));
        }

        @Override public Object redactValue(Object value) {
            return delegate.redactValue(value);
        }
    }
}

package io.aura.ai;

import io.aura.contract.CancellationToken;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/** Provider-neutral input for one chat completion. */
public final class AiRequest {
    private final String prompt;
    private final String systemPrompt;
    private final String model;
    private final Map<String, Object> parameters;
    private final Duration timeout;
    private final CancellationToken cancellationToken;
    private final AiBudget budget;
    private final AiRedactor redactor;
    private final List<AiToolDefinition> tools;
    private final String toolChoice;
    private final AiRetryPolicy retryPolicy;
    private final AiPricing pricing;
    private final StructuredOutputSpec structuredOutput;

    public AiRequest(String prompt) {
        this(builder().prompt(prompt));
    }

    public AiRequest(String prompt, Duration timeout) {
        this(builder().prompt(prompt).timeout(timeout));
    }

    private AiRequest(Builder builder) {
        this.prompt = requireText(builder.prompt, "prompt");
        this.systemPrompt = builder.systemPrompt;
        this.model = builder.model;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(builder.parameters));
        this.timeout = validateTimeout(builder.timeout);
        this.cancellationToken = Objects.requireNonNull(builder.cancellationToken, "cancellationToken");
        this.budget = Objects.requireNonNull(builder.budget, "budget");
        this.redactor = Objects.requireNonNull(builder.redactor, "redactor");
        this.tools = List.copyOf(builder.tools);
        this.toolChoice = builder.toolChoice;
        this.retryPolicy = Objects.requireNonNull(builder.retryPolicy, "retryPolicy");
        this.pricing = builder.pricing;
        this.structuredOutput = builder.structuredOutput;
        if (budget.maxInputCharacters() > 0 && prompt.length() > budget.maxInputCharacters()) {
            throw new IllegalArgumentException("prompt exceeds configured AI input budget");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String prompt() { return prompt; }
    /** Prompt after applying the request redactor; providers should use this for transport. */
    public String redactedPrompt() { return redactor.redact(prompt); }
    public String systemPrompt() { return systemPrompt; }
    public String redactedSystemPrompt() { return redactor.redact(systemPrompt); }
    public String model() { return model; }
    public Map<String, Object> parameters() { return parameters; }
    public Duration timeout() { return timeout; }
    public CancellationToken cancellationToken() { return cancellationToken; }
    public AiBudget budget() { return budget; }
    public AiRedactor redactor() { return redactor; }
    public List<AiToolDefinition> tools() { return tools; }
    public String toolChoice() { return toolChoice; }
    public AiRetryPolicy retryPolicy() { return retryPolicy; }
    public AiPricing pricing() { return pricing; }
    public StructuredOutputSpec structuredOutput() { return structuredOutput; }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static Duration validateTimeout(Duration value) {
        if (value == null) return Duration.ZERO;
        if (value.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        return value;
    }

    public static final class Builder {
        private String prompt;
        private String systemPrompt;
        private String model;
        private final Map<String, Object> parameters = new LinkedHashMap<>();
        private Duration timeout = Duration.ZERO;
        private CancellationToken cancellationToken = new CancellationToken();
        private AiBudget budget = AiBudget.unlimited();
        private AiRedactor redactor = AiRedactor.defaultRedactor();
        private final List<AiToolDefinition> tools = new java.util.ArrayList<>();
        private String toolChoice;
        private AiRetryPolicy retryPolicy = AiRetryPolicy.none();
        private AiPricing pricing;
        private StructuredOutputSpec structuredOutput;

        public Builder prompt(String value) { this.prompt = value; return this; }
        public Builder systemPrompt(String value) { this.systemPrompt = value; return this; }
        public Builder model(String value) { this.model = value; return this; }
        public Builder parameter(String name, Object value) {
            parameters.put(requireText(name, "parameter name"), value); return this;
        }
        public Builder parameters(Map<String, ?> values) {
            if (values != null) values.forEach(this::parameter); return this;
        }
        public Builder timeout(Duration value) { this.timeout = value; return this; }
        public Builder cancellationToken(CancellationToken value) { this.cancellationToken = value; return this; }
        public Builder budget(AiBudget value) { this.budget = value; return this; }
        public Builder redactor(AiRedactor value) { this.redactor = value; return this; }
        public Builder tool(AiToolDefinition value) { tools.add(Objects.requireNonNull(value, "tool")); return this; }
        public Builder tools(List<AiToolDefinition> values) {
            tools.clear();
            if (values != null) values.forEach(this::tool);
            return this;
        }
        public Builder toolChoice(String value) { this.toolChoice = value; return this; }
        public Builder retry(AiRetryPolicy value) { this.retryPolicy = Objects.requireNonNull(value, "retryPolicy"); return this; }
        public Builder pricing(AiPricing value) { this.pricing = value; return this; }
        public Builder structuredOutput(StructuredOutputSpec value) {
            this.structuredOutput = Objects.requireNonNull(value, "structuredOutput");
            return this;
        }
        public AiRequest build() { return new AiRequest(this); }
    }
}

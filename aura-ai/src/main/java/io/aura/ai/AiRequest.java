package io.aura.ai;

import io.aura.contract.CancellationToken;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/** Provider-neutral input for one chat completion. */
public final class AiRequest {
    private final String prompt;
    private final String redactedPrompt;
    private final String systemPrompt;
    private final String redactedSystemPrompt;
    private final String model;
    private final Map<String, Object> parameters;
    private final Map<String, Object> redactedParameters;
    private final Duration timeout;
    private final CancellationToken cancellationToken;
    private final AiBudget budget;
    private final List<AiToolDefinition> tools;
    private final List<AiToolDefinition> redactedTools;
    private final String toolChoice;
    private final AiRetryPolicy retryPolicy;
    private final AiPricing pricing;
    private final StructuredOutputSpec structuredOutput;
    private final long inputCharacters;

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
        this.parameters = AiJson.immutableMap(builder.parameters, "AI request parameters");
        this.timeout = validateTimeout(builder.timeout);
        this.cancellationToken = Objects.requireNonNull(builder.cancellationToken, "cancellationToken");
        this.budget = Objects.requireNonNull(builder.budget, "budget");
        AiRedactor redactor = Objects.requireNonNull(builder.redactor, "redactor");
        this.tools = List.copyOf(builder.tools);
        this.redactedPrompt = requireRedacted(redactor.redact(prompt), "prompt");
        this.redactedSystemPrompt = systemPrompt == null ? null
                : requireRedacted(redactor.redact(systemPrompt), "system prompt");
        this.redactedParameters = redactedMap(redactor.redactValue(parameters), "redacted AI parameters");
        this.redactedTools = tools.stream().map(tool -> new AiToolDefinition(
                tool.name(), tool.description() == null ? null : redactor.redact(tool.description()),
                redactedMap(redactor.redactValue(tool.inputSchema()),
                        "redacted tool input schema"))).toList();
        this.toolChoice = builder.toolChoice;
        this.retryPolicy = Objects.requireNonNull(builder.retryPolicy, "retryPolicy");
        this.pricing = builder.pricing;
        this.structuredOutput = builder.structuredOutput;
        this.inputCharacters = calculateInputCharacters();
        enforceInputBudget();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String prompt() { return prompt; }
    /** Prompt after applying the request redactor; providers should use this for transport. */
    public String redactedPrompt() { return redactedPrompt; }
    public String systemPrompt() { return systemPrompt; }
    public String redactedSystemPrompt() { return redactedSystemPrompt; }
    public String model() { return model; }
    public Map<String, Object> parameters() { return parameters; }
    /** Immutable redacted parameters; providers should use this for transport. */
    public Map<String, Object> redactedParameters() { return redactedParameters; }
    public Duration timeout() { return timeout; }
    public CancellationToken cancellationToken() { return cancellationToken; }
    public AiBudget budget() { return budget; }
    public List<AiToolDefinition> tools() { return tools; }
    /** Immutable redacted tool definitions; providers should use this for transport. */
    public List<AiToolDefinition> redactedTools() { return redactedTools; }
    public String toolChoice() { return toolChoice; }
    public AiRetryPolicy retryPolicy() { return retryPolicy; }
    public AiPricing pricing() { return pricing; }
    public StructuredOutputSpec structuredOutput() { return structuredOutput; }
    /** Provider-neutral size of all request inputs before redaction and provider envelope overhead. */
    public long inputCharacters() { return inputCharacters; }

    void enforceInputBudget() {
        long limit = budget.maxInputCharacters();
        if (limit > 0 && inputCharacters > limit) {
            throw AiBudgetExceededException.exceeded("inputCharacters", limit, inputCharacters);
        }
    }

    private long calculateInputCharacters() {
        long total = Math.max(prompt.length(), length(redactedPrompt));
        total = add(total, Math.max(length(systemPrompt), length(redactedSystemPrompt)));
        total = addLength(total, model);
        total = addLength(total, toolChoice);
        if (!parameters.isEmpty() || !redactedParameters.isEmpty()) {
            total = add(total, Math.max(AiJson.length(parameters), AiJson.length(redactedParameters)));
        }
        for (int index = 0; index < tools.size(); index++) {
            AiToolDefinition tool = tools.get(index);
            AiToolDefinition redactedTool = redactedTools.get(index);
            total = addLength(total, tool.name());
            total = add(total, Math.max(length(tool.description()), length(redactedTool.description())));
            total = add(total, Math.max(
                    AiJson.length(tool.inputSchema()), AiJson.length(redactedTool.inputSchema())));
        }
        if (structuredOutput != null) {
            total = addLength(total, structuredOutput.name());
            total = add(total, AiJson.length(structuredOutput.schema()));
            total = add(total, Boolean.toString(structuredOutput.strict()).length());
        }
        return total;
    }

    private static long addLength(long current, String value) {
        return value == null ? current : add(current, value.length());
    }

    private static int length(String value) { return value == null ? 0 : value.length(); }

    private static String requireRedacted(String value, String name) {
        if (value == null) throw new IllegalArgumentException("redacted " + name + " must not be null");
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactedMap(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must remain a JSON object");
        }
        return AiJson.immutableMap((Map<String, ?>) map, name);
    }

    private static long add(long current, long addition) {
        return Long.MAX_VALUE - current < addition ? Long.MAX_VALUE : current + addition;
    }

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
        private final Map<String, Object> parameters = new java.util.LinkedHashMap<>();
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

package io.aura.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiBudgetEnforcementTest {
    @Test
    void inputCharacterBudgetCountsEveryProviderNeutralInput() {
        assertInputBudgetExceeded(() -> AiRequest.builder()
                .prompt("p").systemPrompt("system")
                .budget(AiBudget.builder().maxInputCharacters(1).build()).build());
        assertInputBudgetExceeded(() -> AiRequest.builder()
                .prompt("p").parameter("metadata", Map.of("value", "large"))
                .budget(AiBudget.builder().maxInputCharacters(1).build()).build());
        assertInputBudgetExceeded(() -> AiRequest.builder()
                .prompt("p").tool(new AiToolDefinition("lookup", "description",
                        Map.of("type", "object")))
                .budget(AiBudget.builder().maxInputCharacters(1).build()).build());
        assertInputBudgetExceeded(() -> AiRequest.builder()
                .prompt("p").structuredOutput(StructuredOutputSpec.strict("answer", Map.of(
                        "type", "object",
                        "properties", Map.of("value", Map.of("type", "string")),
                        "required", java.util.List.of("value"),
                        "additionalProperties", false)))
                .budget(AiBudget.builder().maxInputCharacters(1).build()).build());
    }

    @Test
    void inputBudgetCountsExpandedRedactedTransportValues() {
        assertThatThrownBy(() -> AiRequest.builder()
                .prompt("x")
                .redactor(value -> value == null ? null : value.repeat(20))
                .budget(AiBudget.builder().maxInputCharacters(10).build())
                .build())
                .isInstanceOf(AiBudgetExceededException.class)
                .satisfies(error -> assertBudgetError(
                        (AiBudgetExceededException) error, "inputCharacters", 10, 20));
    }

    @Test
    void redactedTransportValuesAreFrozenWithTheBudgetSnapshot() throws Exception {
        AtomicInteger redactions = new AtomicInteger();
        AiRequest request = AiRequest.builder()
                .prompt("x")
                .redactor(value -> value == null ? null
                        : redactions.incrementAndGet() == 1 ? value : value.repeat(100))
                .budget(AiBudget.builder().maxInputCharacters(1).build())
                .build();

        assertThat(request.inputCharacters()).isEqualTo(1);
        assertThat(request.redactedPrompt()).isEqualTo("x");
        assertThat(request.redactedPrompt()).isEqualTo("x");
        try (AiClient client = new AiClient(value -> {
            assertThat(value.redactedPrompt()).isEqualTo("x");
            return AiResponse.text("ok");
        })) {
            client.chat(request);
        }
        assertThat(redactions).hasValue(1);
    }

    @Test
    void customRedactorDoesNotReceiveAnAbsentToolDescription() {
        AiRequest request = AiRequest.builder()
                .prompt("prompt")
                .redactor(value -> value.replace("secret", "<redacted>"))
                .tool(new AiToolDefinition("lookup", null, Map.of("type", "object")))
                .build();

        assertThat(request.redactedTools()).singleElement()
                .extracting(AiToolDefinition::description).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestSnapshotsNestedInputsBeforeMeasuringAndSending() throws Exception {
        ArrayList<Object> parameterValues = new ArrayList<>();
        parameterValues.add("small");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("values", parameterValues);

        ArrayList<Object> schemaTypes = new ArrayList<>();
        schemaTypes.add("string");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", schemaTypes);
        AiToolDefinition tool = new AiToolDefinition("lookup", "safe", schema);

        AiRequest request = AiRequest.builder().prompt("p")
                .parameters(parameters).tool(tool).build();
        long measured = request.inputCharacters();
        parameterValues.add("x".repeat(10_000));
        schemaTypes.add("null");

        assertThat(request.inputCharacters()).isEqualTo(measured);
        assertThat((java.util.List<Object>) request.parameters().get("values"))
                .containsExactly("small");
        assertThat((java.util.List<Object>) request.tools().get(0).inputSchema().get("type"))
                .containsExactly("string");
        assertThatThrownBy(() -> ((java.util.List<Object>) request.parameters().get("values")).add("late"))
                .isInstanceOf(UnsupportedOperationException.class);

        java.util.concurrent.atomic.AtomicReference<AiRequest> observed =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (AiClient client = new AiClient(value -> {
            observed.set(value);
            return AiResponse.text("ok");
        })) {
            client.chat(request);
        }
        assertThat(observed.get().inputCharacters()).isEqualTo(measured);
    }

    @Test
    void synchronousChatRejectsOutputCharacterOverage() {
        AiProvider provider = request -> AiResponse.text("abcd");
        AiRequest request = AiRequest.builder()
                .prompt("prompt")
                .budget(AiBudget.builder().maxOutputCharacters(3).build())
                .build();

        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.chat(request))
                    .isInstanceOf(AiBudgetExceededException.class)
                    .satisfies(error -> {
                        AiBudgetExceededException budgetError = (AiBudgetExceededException) error;
                        assertThat(budgetError.code()).isEqualTo(AiBudgetExceededException.EXCEEDED);
                        assertThat(budgetError.dimension()).isEqualTo("outputCharacters");
                        assertThat(budgetError.limit()).isEqualTo(3);
                        assertThat(budgetError.actual()).isEqualTo(4);
                    });
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void synchronousChatRejectsTokenAndCostOverages() {
        AiProvider provider = request -> new AiResponse("ok", "model", Map.of(
                "input_tokens", 3,
                "output_tokens", 2,
                "total_tokens", 5,
                "cost_micros", 101), null);

        try (AiClient client = new AiClient(provider)) {
            AiRequest tokenRequest = AiRequest.builder()
                    .prompt("prompt")
                    .budget(AiBudget.builder().maxInputTokens(2).build())
                    .build();
            assertThatThrownBy(() -> client.chat(tokenRequest))
                    .isInstanceOf(AiBudgetExceededException.class)
                    .satisfies(error -> assertBudgetError((AiBudgetExceededException) error,
                            "inputTokens", 2, 3));

            AiRequest costRequest = AiRequest.builder()
                    .prompt("prompt")
                    .budget(AiBudget.builder().maxCostMicros(100).build())
                    .build();
            assertThatThrownBy(() -> client.chat(costRequest))
                    .isInstanceOf(AiBudgetExceededException.class)
                    .satisfies(error -> assertBudgetError((AiBudgetExceededException) error,
                            "costMicros", 100, 101));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void configuredUsageBudgetFailsClosedWhenProviderOmitsUsage() {
        AiProvider provider = request -> AiResponse.text("ok");
        AiRequest request = AiRequest.builder()
                .prompt("prompt")
                .budget(AiBudget.builder().maxOutputTokens(10).build())
                .build();

        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.chat(request))
                    .isInstanceOf(AiBudgetExceededException.class)
                    .satisfies(error -> {
                        AiBudgetExceededException budgetError = (AiBudgetExceededException) error;
                        assertThat(budgetError.code()).isEqualTo(AiBudgetExceededException.USAGE_MISSING);
                        assertThat(budgetError.dimension()).isEqualTo("outputTokens");
                        assertThat(budgetError.limit()).isEqualTo(10);
                        assertThat(budgetError.actual()).isEqualTo(-1);
                    });
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void streamingChatStopsWhenOutputCharacterBudgetIsExceeded() {
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) {
                return AiResponse.text("unused");
            }

            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onToken("ab");
                handler.onToken("cd");
                handler.onComplete(AiResponse.text("abcd"));
            }
        };
        AiRequest request = AiRequest.builder()
                .prompt("prompt")
                .budget(AiBudget.builder().maxOutputCharacters(3).build())
                .build();
        StringBuilder output = new StringBuilder();
        AtomicReference<Throwable> callbackError = new AtomicReference<>();

        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(request, new AiStreamHandler() {
                @Override public void onToken(String token) { output.append(token); }
                @Override public void onError(Throwable error) { callbackError.set(error); }
            }))
                    .isInstanceOf(AiBudgetExceededException.class)
                    .satisfies(error -> assertBudgetError((AiBudgetExceededException) error,
                            "outputCharacters", 3, 4));
        } catch (Exception error) {
            throw new AssertionError(error);
        }

        assertThat(output).hasToString("ab");
        assertThat(callbackError.get()).isInstanceOf(AiBudgetExceededException.class);
    }

    @Test
    void streamingBudgetCancellationPropagatesWhenProviderSwallowsCallbackFailure() {
        AtomicBoolean cancellationObserved = new AtomicBoolean();
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                try { handler.onToken("ab"); } catch (RuntimeException ignored) { }
                try { handler.onToken("cd"); } catch (RuntimeException ignored) { }
                cancellationObserved.set(request.cancellationToken().isCancelled());
                try { handler.onComplete(AiResponse.text("abcd")); } catch (RuntimeException ignored) { }
            }
        };
        AiRequest request = AiRequest.builder().prompt("prompt")
                .budget(AiBudget.builder().maxOutputCharacters(3).build()).build();

        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(request, token -> { }))
                    .isInstanceOf(AiBudgetExceededException.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        assertThat(cancellationObserved).isTrue();
    }

    private static void assertBudgetError(AiBudgetExceededException error,
                                          String dimension, long limit, long actual) {
        assertThat(error.code()).isEqualTo(AiBudgetExceededException.EXCEEDED);
        assertThat(error.dimension()).isEqualTo(dimension);
        assertThat(error.limit()).isEqualTo(limit);
        assertThat(error.actual()).isEqualTo(actual);
    }

    private static void assertInputBudgetExceeded(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(AiBudgetExceededException.class)
                .satisfies(error -> {
                    AiBudgetExceededException budgetError = (AiBudgetExceededException) error;
                    assertThat(budgetError.code()).isEqualTo(AiBudgetExceededException.EXCEEDED);
                    assertThat(budgetError.dimension()).isEqualTo("inputCharacters");
                    assertThat(budgetError.limit()).isEqualTo(1);
                    assertThat(budgetError.actual()).isGreaterThan(1);
                });
    }
}

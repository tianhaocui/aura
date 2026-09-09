package io.aura.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiBudgetEnforcementTest {
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
}

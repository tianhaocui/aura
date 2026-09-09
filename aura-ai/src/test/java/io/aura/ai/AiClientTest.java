package io.aura.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aura.contract.CancellationToken;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AiClientTest {
    @Test
    void defaultExecutorUsesFixedDaemonWorkersAndBoundedQueue() throws Exception {
        ThreadPoolExecutor executor = AiClient.createDefaultExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(executor.getMaximumPoolSize());
            assertThat(executor.getQueue()).isInstanceOf(ArrayBlockingQueue.class);
            assertThat(executor.getQueue().remainingCapacity()).isEqualTo(AiClient.DEFAULT_QUEUE_CAPACITY);
            Future<Thread> worker = executor.submit(Thread::currentThread);
            assertThat(worker.get(1, TimeUnit.SECONDS).isDaemon()).isTrue();
            assertThat(worker.get().getName()).startsWith("aura-ai-");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void saturatedExecutorRejectsWithClearMessage() throws Exception {
        ThreadPoolExecutor executor = AiClient.createBoundedExecutor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> { started.countDown(); release.await(); return null; });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> { release.await(); return null; });

            AiClient client = new AiClient(request -> AiResponse.text("unused"), executor);
            assertThatThrownBy(() -> client.chat(new AiRequest("overflow")))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("saturated or shut down");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void mockProviderSupportsChatAndDefaultStreaming() throws Exception {
        AiProvider provider = request -> AiResponse.text("hello " + request.prompt());
        try (AiClient client = new AiClient(provider)) {
            AiResponse response = client.chat(new AiRequest("world"));
            assertThat(response.content()).isEqualTo("hello world");

            StringBuilder tokens = new StringBuilder();
            AtomicBoolean started = new AtomicBoolean();
            AtomicReference<AiResponse> completed = new AtomicReference<>();
            client.stream(new AiRequest("stream"), new AiStreamHandler() {
                @Override public void onStart() { started.set(true); }
                @Override public void onToken(String token) { tokens.append(token); }
                @Override public void onComplete(AiResponse result) { completed.set(result); }
            });
            assertThat(started).isTrue();
            assertThat(tokens).hasToString("hello stream");
            assertThat(completed.get().content()).isEqualTo("hello stream");
        }
    }

    @Test
    void requestsUseDeterministicSensitiveFieldRedactionByDefault() {
        AiRequest request = new AiRequest("password=hidden token=secret");
        assertThat(request.redactedPrompt()).isEqualTo("password=<redacted> token=<redacted>");

        AiRequest jsonRequest = new AiRequest("{\"password\":\"hidden\",\"token\":\"secret\"}");
        assertThat(jsonRequest.redactedPrompt()).isEqualTo("{\"password\":\"<redacted>\",\"token\":\"<redacted>\"}");
    }

    @Test
    void providerCanEmitMultipleStreamingTokens() throws Exception {
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("ab"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onToken("a");
                handler.onToken("b");
                handler.onComplete(AiResponse.text("ab"));
            }
        };
        try (AiClient client = new AiClient(provider)) {
            StringBuilder output = new StringBuilder();
            client.stream(new AiRequest("letters"), output::append);
            assertThat(output).hasToString("ab");
        }
    }

    @Test
    void synchronousChatEnforcesOutputCharacterBudget() {
        AiProvider provider = request -> AiResponse.text("abcd");
        AiRequest request = AiRequest.builder()
                .prompt("budgeted")
                .budget(AiBudget.builder().maxOutputCharacters(3).build())
                .build();

        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.chat(request))
                    .isInstanceOf(AiBudgetExceededException.class)
                    .satisfies(error -> assertBudgetError((AiBudgetExceededException) error,
                            "outputCharacters", 3, 4));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void streamingChatEnforcesOutputCharacterBudgetBeforeForwardingOverage() {
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onToken("ab");
                handler.onToken("cd");
                handler.onComplete(AiResponse.text("abcd"));
            }
        };
        AiRequest request = AiRequest.builder()
                .prompt("budgeted")
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
    void tokenAndCostBudgetsPassWhenProviderUsageIsWithinLimits() throws Exception {
        AiProvider provider = request -> new AiResponse("ok", "model", Map.of(
                "input_tokens", 2,
                "output_tokens", 3,
                "total_tokens", 5,
                "cost_micros", 100), null);
        AiRequest request = AiRequest.builder()
                .prompt("budgeted")
                .budget(AiBudget.builder()
                        .maxInputTokens(2)
                        .maxOutputTokens(3)
                        .maxTotalTokens(5)
                        .maxCostMicros(100)
                        .build())
                .build();

        try (AiClient client = new AiClient(provider)) {
            assertThat(client.chat(request).content()).isEqualTo("ok");
        }
    }

    @Test
    void tokenAndCostBudgetsRejectProviderUsageOverages() {
        AiProvider provider = request -> new AiResponse("ok", "model", Map.of(
                "input_tokens", 3,
                "output_tokens", 2,
                "total_tokens", 5,
                "cost_micros", 101), null);

        try (AiClient client = new AiClient(provider)) {
            AiRequest tokenRequest = AiRequest.builder()
                    .prompt("budgeted")
                    .budget(AiBudget.builder().maxInputTokens(2).build())
                    .build();
            assertThatThrownBy(() -> client.chat(tokenRequest))
                    .isInstanceOf(AiBudgetExceededException.class)
                    .satisfies(error -> assertBudgetError((AiBudgetExceededException) error,
                            "inputTokens", 2, 3));

            AiRequest costRequest = AiRequest.builder()
                    .prompt("budgeted")
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
                .prompt("budgeted")
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
    void providerUsageAliasesAreParsedAndTotalTokensAreDerived() {
        AiUsage usage = AiUsage.from(Map.of(
                "prompt_tokens", "3",
                "completion_tokens", 4L,
                "cost_micros", 125L));

        assertThat(usage).isEqualTo(new AiUsage(3, 4, 7, 125));
        assertThat(usage.hasTokenUsage()).isTrue();
        assertThat(usage.hasCost()).isTrue();
    }

    @Test
    void timeoutCancelsProviderWork() {
        CountDownLatch started = new CountDownLatch(1);
        AiProvider provider = request -> {
            started.countDown();
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return AiResponse.text("late");
        };
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.chat(new AiRequest("slow", Duration.ofMillis(40))))
                    .isInstanceOf(TimeoutException.class);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void cancellationTokenStopsRequest() throws Exception {
        CancellationToken token = new CancellationToken();
        CountDownLatch started = new CountDownLatch(1);
        AiProvider provider = request -> {
            started.countDown();
            while (true) {
                request.cancellationToken().throwIfCancelled();
                Thread.sleep(5);
            }
        };
        try (AiClient client = new AiClient(provider)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try { client.chat(AiRequest.builder().prompt("cancel").cancellationToken(token).build()); }
                catch (Throwable error) { failure.set(error); }
            });
            caller.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            token.cancel();
            caller.join(1_000);
            assertThat(failure.get()).isInstanceOf(CancellationException.class);
        }
    }

    private static void assertBudgetError(AiBudgetExceededException error,
                                          String dimension, long limit, long actual) {
        assertThat(error.code()).isEqualTo(AiBudgetExceededException.EXCEEDED);
        assertThat(error.dimension()).isEqualTo(dimension);
        assertThat(error.limit()).isEqualTo(limit);
        assertThat(error.actual()).isEqualTo(actual);
    }

}

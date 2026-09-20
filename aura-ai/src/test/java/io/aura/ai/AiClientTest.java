package io.aura.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aura.contract.CancellationToken;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
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
            AtomicBoolean streamStarted = new AtomicBoolean();
            AtomicReference<Throwable> streamFailure = new AtomicReference<>();
            assertThatThrownBy(() -> client.stream(new AiRequest("overflow"), new AiStreamHandler() {
                @Override public void onStart() { streamStarted.set(true); }
                @Override public void onToken(String token) { }
                @Override public void onError(Throwable error) { streamFailure.set(error); }
            })).isInstanceOf(RejectedExecutionException.class);
            assertThat(streamStarted).isFalse();
            assertThat(streamFailure.get()).isInstanceOf(RejectedExecutionException.class);
            assertThat(client.executorMetrics().observable()).isTrue();
            assertThat(client.executorMetrics().queueCapacity()).isEqualTo(1);
            assertThat(client.executorMetrics().rejected()).isEqualTo(2);
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
    void usageTotalsAccumulateAcrossChatAndStreamingAndCanReset() throws Exception {
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) {
                return new AiResponse("chat", "model",
                        Map.of("prompt_tokens", 2, "completion_tokens", 3, "total_tokens", 5,
                                "cost_micros", 7), null);
            }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onComplete(new AiResponse("stream", "model",
                        Map.of("prompt_tokens", 4, "completion_tokens", 1, "total_tokens", 5,
                                "cost_micros", 11), null));
            }
        };
        try (AiClient client = new AiClient(provider)) {
            client.chat(new AiRequest("one"));
            client.stream(new AiRequest("two"), token -> { });
            assertThat(client.usageTotals())
                    .isEqualTo(new AiUsageTotals(2, 6, 4, 10, 18, 0));
            client.resetUsage();
            assertThat(client.usageTotals()).isEqualTo(AiUsageTotals.empty());
        }
    }

    @Test
    void usageTotalsCountUnknownProviderUsage() throws Exception {
        try (AiClient client = new AiClient(request -> AiResponse.text("ok"))) {
            client.chat(new AiRequest("unknown"));
            assertThat(client.usageTotals().unknownUsage()).isEqualTo(1);
        }
    }

    @Test
    void malformedUsageDoesNotBreakAnUnbudgetedResponse() throws Exception {
        AiProvider provider = request -> new AiResponse("ok", "model",
                Map.of("total_tokens", "not-a-number"), null);
        try (AiClient client = new AiClient(provider)) {
            assertThat(client.chat(new AiRequest("malformed")).content()).isEqualTo("ok");
            assertThat(client.usageTotals().requests()).isEqualTo(1);
            assertThat(client.usageTotals().unknownUsage()).isEqualTo(1);
        }
    }

    @Test
    void explicitPricingDerivesCostForBudgetAndUsageTotals() throws Exception {
        AiProvider provider = request -> new AiResponse("ok", "model",
                Map.of("prompt_tokens", 1_000, "completion_tokens", 500), null);
        AiRequest request = AiRequest.builder()
                .prompt("priced")
                .pricing(AiPricing.of(2_000, 4_000))
                .budget(AiBudget.builder().maxCostMicros(4).build())
                .build();
        try (AiClient client = new AiClient(provider)) {
            assertThat(client.chat(request).content()).isEqualTo("ok");
            assertThat(client.usageTotals().costMicros()).isEqualTo(4);
        }
    }

    @Test
    void explicitPricingIsConservativeAndCostBudgetStillFailsClosed() {
        AiProvider provider = request -> new AiResponse("ok", "model",
                Map.of("prompt_tokens", 1, "completion_tokens", 0), null);
        AiRequest request = AiRequest.builder()
                .prompt("priced")
                .pricing(AiPricing.of(1, 0))
                .budget(AiBudget.builder().maxCostMicros(0).build())
                .build();
        assertThat(request.pricing().estimateCostMicros(
                new AiUsage(1, 0, 1, -1))).isEqualTo(1);
    }

    @Test
    void requestsUseDeterministicSensitiveFieldRedactionByDefault() {
        AiRequest request = new AiRequest("password=hidden token=secret");
        assertThat(request.redactedPrompt()).isEqualTo("password=<redacted> token=<redacted>");

        AiRequest jsonRequest = new AiRequest("{\"password\":\"hidden\",\"token\":\"secret\"}");
        assertThat(jsonRequest.redactedPrompt()).isEqualTo("{\"password\":\"<redacted>\",\"token\":\"<redacted>\"}");

        AiRedactor redactor = AiRedactor.defaultRedactor();
        assertThat(redactor.redact("Authorization: Bearer abc.def-123"))
                .isEqualTo("Authorization: <redacted>");
        assertThat(redactor.redact("authorization=Basic dXNlcjpwYXNz"))
                .isEqualTo("authorization=<redacted>");
        assertThat(redactor.redactValue(Map.of(
                "metadata", java.util.List.of(Map.of("access_token", "nested-secret")),
                "safe", "visible"))).isEqualTo(Map.of(
                "metadata", java.util.List.of(Map.of("access_token", "<redacted>")),
                "safe", "visible"));
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
    void streamingRetriesRetryableFailureBeforeFirstTokenOnly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
                if (calls.incrementAndGet() == 1) {
                    handler.onError(new AiProviderException("TRANSIENT", 503, "retry"));
                    return;
                }
                handler.onToken("ok");
                handler.onComplete(AiResponse.text("ok"));
            }
        };
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        StringBuilder output = new StringBuilder();
        AiRequest request = AiRequest.builder().prompt("retry")
                .retry(AiRetryPolicy.fixed(2, Duration.ZERO)).build();

        try (AiClient client = new AiClient(provider)) {
            client.stream(request, new AiStreamHandler() {
                @Override public void onStart() { starts.incrementAndGet(); }
                @Override public void onToken(String token) { output.append(token); }
                @Override public void onComplete(AiResponse response) { completions.incrementAndGet(); }
                @Override public void onError(Throwable error) { failures.incrementAndGet(); }
            });
            assertThat(client.usageTotals().requests()).isEqualTo(1);
        }
        assertThat(calls).hasValue(2);
        assertThat(starts).hasValue(1);
        assertThat(completions).hasValue(1);
        assertThat(failures).hasValue(0);
        assertThat(output).hasToString("ok");
    }

    @Test
    void cancellationStopsStreamingRetryBackoff() throws Exception {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> callFailure = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                calls.incrementAndGet();
                handler.onError(new AiProviderException("TRANSIENT", 503, "retry"));
            }
        };
        AiRequest request = AiRequest.builder().prompt("retry")
                .cancellationToken(token)
                .retry(AiRetryPolicy.fixed(2, Duration.ofSeconds(5)))
                .timeout(Duration.ofSeconds(10)).build();

        try (AiClient client = new AiClient(provider)) {
            Thread caller = new Thread(() -> {
                try {
                    client.stream(request, new AiStreamHandler() {
                        @Override public void onToken(String value) { }
                        @Override public void onError(Throwable error) { callbackFailure.set(error); }
                    });
                } catch (Throwable error) {
                    callFailure.set(error);
                }
            });
            caller.start();
            for (int index = 0; index < 100 && calls.get() == 0; index++) Thread.sleep(5);
            assertThat(calls).hasValue(1);
            token.cancel();
            caller.join(1_000);
            assertThat(caller.isAlive()).isFalse();
        }
        assertThat(callFailure.get()).isInstanceOf(CancellationException.class);
        assertThat(callbackFailure.get()).isSameAs(callFailure.get());
        assertThat(calls).hasValue(1);
    }

    @Test
    void retryBackoffNeverExceedsTheSharedDeadline() {
        CancellationToken token = new CancellationToken();
        AiProvider provider = request -> {
            Thread.sleep(250);
            throw new AiProviderException("TRANSIENT", 503, "retry");
        };
        AiRequest request = AiRequest.builder().prompt("deadline")
                .cancellationToken(token)
                .timeout(Duration.ofMillis(300))
                .retry(AiRetryPolicy.fixed(2, Duration.ofMillis(250))).build();
        long started = System.nanoTime();
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.chat(request)).isInstanceOf(TimeoutException.class);
            assertThat(client.executorMetrics().timedOut()).isEqualTo(1);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(450));
        assertThat(token.isCancelled()).isTrue();
    }

    @Test
    void streamingRetryBackoffNeverExceedsTheSharedDeadline() {
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
                Thread.sleep(250);
                handler.onError(new AiProviderException("TRANSIENT", 503, "retry"));
            }
        };
        AiRequest request = AiRequest.builder().prompt("deadline")
                .timeout(Duration.ofMillis(300))
                .retry(AiRetryPolicy.fixed(2, Duration.ofMillis(250))).build();
        long started = System.nanoTime();
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(request, token -> { }))
                    .isInstanceOf(TimeoutException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(450));
    }

    @Test
    void streamingNeverRetriesAfterAVisibleToken() {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
                calls.incrementAndGet();
                handler.onToken("partial");
                throw new AiProviderException("TRANSIENT", 503, "failed after token");
            }
        };
        StringBuilder output = new StringBuilder();
        AtomicInteger failures = new AtomicInteger();
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(AiRequest.builder().prompt("retry")
                            .retry(AiRetryPolicy.fixed(3, Duration.ZERO)).build(),
                    new AiStreamHandler() {
                        @Override public void onToken(String token) { output.append(token); }
                        @Override public void onError(Throwable error) { failures.incrementAndGet(); }
                    }))
                    .isInstanceOf(AiProviderException.class);
        }
        assertThat(calls).hasValue(1);
        assertThat(failures).hasValue(1);
        assertThat(output).hasToString("partial");
    }

    @Test
    void streamingRetryWaitsForThePreviousAttemptToReturn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                try {
                    if (calls.incrementAndGet() == 1) {
                        handler.onError(new AiProviderException("TRANSIENT", 503, "retry"));
                        sleepIgnoringInterrupt(200);
                        return;
                    }
                    handler.onToken("ok");
                    handler.onComplete(AiResponse.text("ok"));
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        try (AiClient client = new AiClient(provider)) {
            client.stream(AiRequest.builder().prompt("retry")
                    .retry(AiRetryPolicy.fixed(2, Duration.ZERO)).build(), token -> { });
        }
        assertThat(calls).hasValue(2);
        assertThat(maxActive).hasValue(1);
    }

    @Test
    void providerOnErrorTerminatesTheCallAndIsDeliveredOnce() {
        IOException providerFailure = new IOException("provider failed");
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onError(providerFailure);
            }
        };
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(new AiRequest("stream"), new AiStreamHandler() {
                @Override public void onToken(String token) { }
                @Override public void onError(Throwable error) { callbackFailure.set(error); }
            })).isSameAs(providerFailure);
        }
        assertThat(callbackFailure.get()).isSameAs(providerFailure);
    }

    @Test
    void throwingErrorCallbackDoesNotReplaceTheProviderFailure() {
        IOException providerFailure = new IOException("provider failed");
        IllegalStateException callbackFailure = new IllegalStateException("callback failed");
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onError(providerFailure);
            }
        };
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(new AiRequest("stream"), new AiStreamHandler() {
                @Override public void onToken(String token) { }
                @Override public void onError(Throwable error) { throw callbackFailure; }
            }))
                    .isSameAs(providerFailure)
                    .satisfies(error -> assertThat(error.getSuppressed()).containsExactly(callbackFailure));
        }
    }

    @Test
    void swallowedTokenCallbackFailureRemainsTheTerminalCause() {
        IllegalStateException callbackFailure = new IllegalStateException("token callback failed");
        AtomicReference<Throwable> delivered = new AtomicReference<>();
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                try { handler.onToken("token"); } catch (RuntimeException ignored) { }
                sleepIgnoringInterrupt(200);
            }
        };
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(
                    AiRequest.builder().prompt("stream").timeout(Duration.ofSeconds(1)).build(),
                    new AiStreamHandler() {
                        @Override public void onToken(String token) { throw callbackFailure; }
                        @Override public void onError(Throwable error) { delivered.set(error); }
                    })).isSameAs(callbackFailure);
        }
        assertThat(delivered.get()).isSameAs(callbackFailure);
    }

    @Test
    void swallowedContractFailureIsNotMaskedByTimeout() {
        AtomicReference<Throwable> delivered = new AtomicReference<>();
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onComplete(AiResponse.text("ok"));
                try { handler.onComplete(AiResponse.text("duplicate")); }
                catch (RuntimeException ignored) { }
                sleepIgnoringInterrupt(200);
            }
        };
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(
                    AiRequest.builder().prompt("stream").timeout(Duration.ofSeconds(1)).build(),
                    new AiStreamHandler() {
                        @Override public void onToken(String token) { }
                        @Override public void onError(Throwable error) { delivered.set(error); }
                    }))
                    .isInstanceOf(AiProviderContractException.class);
        }
        assertThat(delivered.get()).isInstanceOf(AiProviderContractException.class);
    }

    @Test
    void timeoutSerializesErrorAfterAnInFlightTokenCallback() {
        AtomicBoolean tokenActive = new AtomicBoolean();
        AtomicBoolean concurrentTerminal = new AtomicBoolean();
        CountDownLatch terminalDelivered = new CountDownLatch(1);
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onToken("slow");
                handler.onComplete(AiResponse.text("slow"));
            }
        };
        long started = System.nanoTime();
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(
                    AiRequest.builder().prompt("stream").timeout(Duration.ofMillis(50)).build(),
                    new AiStreamHandler() {
                        @Override public void onToken(String token) {
                            tokenActive.set(true);
                            sleepIgnoringInterrupt(200);
                            tokenActive.set(false);
                        }
                        @Override public void onError(Throwable error) {
                            concurrentTerminal.set(tokenActive.get());
                            terminalDelivered.countDown();
                        }
                    }))
                    .isInstanceOf(TimeoutException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(150));
        try {
            assertThat(terminalDelivered.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
        assertThat(concurrentTerminal).isFalse();
        assertThat(tokenActive).isFalse();
    }

    @Test
    void providerMustEmitExactlyOneTerminalCallback() {
        assertProviderContractViolation(handler -> { });
        assertProviderContractViolation(handler -> {
            handler.onComplete(AiResponse.text("ok"));
            handler.onComplete(AiResponse.text("again"));
        });
        assertProviderContractViolation(handler -> {
            handler.onError(new IOException("failed"));
            handler.onComplete(AiResponse.text("late"));
        });
        assertProviderContractViolation(handler -> {
            handler.onComplete(AiResponse.text("ok"));
            handler.onToken("late");
        });
    }

    @Test
    void providerThrowAfterCompletionDoesNotLeakAFalseSuccess() {
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
                handler.onComplete(AiResponse.text("ok"));
                throw new IOException("late failure");
            }
        };
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(new AiRequest("stream"), new AiStreamHandler() {
                @Override public void onToken(String token) { }
                @Override public void onComplete(AiResponse response) { completions.incrementAndGet(); }
                @Override public void onError(Throwable error) { failures.incrementAndGet(); }
            })).isInstanceOf(AiProviderContractException.class);
        }
        assertThat(completions).hasValue(0);
        assertThat(failures).hasValue(1);
    }

    @Test
    void clientValidatesStructuredOutputForEveryProvider() throws Exception {
        StructuredOutputSpec spec = StructuredOutputSpec.strict("answer", Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", java.util.List.of("answer"),
                "additionalProperties", false));
        AiRequest request = AiRequest.builder().prompt("answer").structuredOutput(spec).build();
        try (AiClient client = new AiClient(ignored -> AiResponse.text("{\"wrong\":true}"))) {
            assertThatThrownBy(() -> client.chat(request))
                    .isInstanceOf(AiStructuredOutputException.class)
                    .extracting("code").isEqualTo(AiStructuredOutputException.CODE);
        }
    }

    @Test
    void streamingStructuredOutputFailsBeforeCompletionIsDelivered() throws Exception {
        StructuredOutputSpec spec = StructuredOutputSpec.strict("answer", Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", java.util.List.of("answer"),
                "additionalProperties", false));
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("{}"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onToken("{}");
                handler.onComplete(AiResponse.text("{}"));
            }
        };
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(
                    AiRequest.builder().prompt("answer").structuredOutput(spec).build(),
                    new AiStreamHandler() {
                        @Override public void onToken(String token) { }
                        @Override public void onComplete(AiResponse response) { completed.set(true); }
                        @Override public void onError(Throwable error) { failure.set(error); }
                    }))
                    .isInstanceOf(AiStructuredOutputException.class);
        }
        assertThat(completed).isFalse();
        assertThat(failure.get()).isInstanceOf(AiStructuredOutputException.class);
    }

    @Test
    void streamingStructuredFailureSurvivesProviderThatSwallowsCallbackException() throws Exception {
        StructuredOutputSpec spec = StructuredOutputSpec.strict("answer", Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", java.util.List.of("answer"),
                "additionalProperties", false));
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("{}"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) {
                handler.onToken("{\"wrong\":true}");
                try {
                    handler.onComplete(AiResponse.text("{\"answer\":\"ok\"}"));
                } catch (RuntimeException ignored) { }
            }
        };
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(
                    AiRequest.builder().prompt("answer").structuredOutput(spec).build(),
                    new AiStreamHandler() {
                        @Override public void onToken(String token) { }
                        @Override public void onError(Throwable error) { failure.set(error); }
                    }))
                    .isInstanceOf(AiStructuredOutputException.class)
                    .hasMessageContaining("differs");
        }
        assertThat(failure.get()).isInstanceOf(AiStructuredOutputException.class);
    }

    @Test
    void invalidStructuredOutputStillRecordsUsageAndChecksBudgetFirst() {
        StructuredOutputSpec spec = StructuredOutputSpec.strict("answer", Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", java.util.List.of("answer"),
                "additionalProperties", false));
        AiProvider provider = request -> new AiResponse("{\"wrong\":true}", "model",
                Map.of("total_tokens", 3), null);
        try (AiClient client = new AiClient(provider)) {
            AiRequest invalid = AiRequest.builder().prompt("answer").structuredOutput(spec).build();
            assertThatThrownBy(() -> client.chat(invalid))
                    .isInstanceOf(AiStructuredOutputException.class);
            assertThat(client.usageTotals().requests()).isEqualTo(1);
            assertThat(client.usageTotals().totalTokens()).isEqualTo(3);

            AiRequest overBudget = AiRequest.builder().prompt("answer")
                    .structuredOutput(spec)
                    .budget(AiBudget.builder().maxOutputCharacters(2).build())
                    .build();
            assertThatThrownBy(() -> client.chat(overBudget))
                    .isInstanceOf(AiBudgetExceededException.class);
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
            assertThat(client.executorMetrics().timedOut()).isEqualTo(1);
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

    @Test
    void cancellationTokenStopsLongRetryBackoff() throws Exception {
        CancellationToken token = new CancellationToken();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AiProvider provider = request -> {
            calls.incrementAndGet();
            throw new AiProviderException("TRANSIENT", 503, "retry");
        };
        AiRequest request = AiRequest.builder()
                .prompt("retry-cancel")
                .cancellationToken(token)
                .retry(AiRetryPolicy.fixed(2, Duration.ofSeconds(5)))
                .timeout(Duration.ofSeconds(10))
                .build();
        try (AiClient client = new AiClient(provider)) {
            Thread caller = new Thread(() -> {
                try { client.chat(request); }
                catch (Throwable error) { failure.set(error); }
            });
            caller.start();
            for (int i = 0; i < 100 && calls.get() == 0; i++) Thread.sleep(5);
            assertThat(calls).hasValue(1);
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

    private static void assertProviderContractViolation(
            ThrowingStreamProvider streamProvider) {
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AiProvider provider = new AiProvider() {
            @Override public AiResponse chat(AiRequest request) { return AiResponse.text("unused"); }
            @Override public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
                streamProvider.stream(handler);
            }
        };
        try (AiClient client = new AiClient(provider)) {
            assertThatThrownBy(() -> client.stream(new AiRequest("stream"), new AiStreamHandler() {
                @Override public void onToken(String token) { }
                @Override public void onComplete(AiResponse response) { completions.incrementAndGet(); }
                @Override public void onError(Throwable error) { failures.incrementAndGet(); }
            }))
                    .isInstanceOf(AiProviderContractException.class)
                    .extracting("code").isEqualTo(AiProviderContractException.CODE);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        assertThat(completions).hasValue(0);
        assertThat(failures).hasValue(1);
    }

    @FunctionalInterface
    private interface ThrowingStreamProvider {
        void stream(AiStreamHandler handler) throws Exception;
    }

    private static void sleepIgnoringInterrupt(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(
                    Math.min(TimeUnit.MILLISECONDS.toNanos(10), deadline - System.nanoTime()));
            Thread.interrupted();
        }
    }

}

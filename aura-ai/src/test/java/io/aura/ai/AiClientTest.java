package io.aura.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aura.contract.CancellationToken;
import java.time.Duration;
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
}

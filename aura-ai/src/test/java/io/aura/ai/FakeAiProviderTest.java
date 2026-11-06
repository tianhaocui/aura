package io.aura.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeAiProviderTest {

    @Test
    void fakeReturnsCannedResponseAndRecordsEveryRequest() throws Exception {
        FakeAiProvider provider = FakeAiProvider.returning("canned answer");
        try (AiClient client = new AiClient(provider)) {
            AiResponse response = client.chat(new AiRequest("first"));
            client.chat(new AiRequest("second"));

            assertThat(response.content()).isEqualTo("canned answer");
            assertThat(provider.callCount()).isEqualTo(2);
            assertThat(provider.received()).extracting(AiRequest::prompt)
                    .containsExactly("first", "second");
            assertThat(provider.lastRequest().prompt()).isEqualTo("second");
        }
    }

    @Test
    void fakeCarriesReportedUsageIntoClientAccounting() throws Exception {
        FakeAiProvider provider = FakeAiProvider.returning("priced",
                Map.of("prompt_tokens", 2, "completion_tokens", 3, "cost_micros", 7));
        try (AiClient client = new AiClient(provider)) {
            client.chat(new AiRequest("unknown"));

            assertThat(client.usageTotals().requests()).isEqualTo(1);
            assertThat(client.usageTotals().totalTokens()).isEqualTo(5);
            assertThat(client.usageTotals().costMicros()).isEqualTo(7);
        }
    }

    @Test
    void fakeShapesEachReplyFromItsOwnPrompt() throws Exception {
        FakeAiProvider provider = FakeAiProvider.answering(request -> "echo:" + request.prompt());
        try (AiClient client = new AiClient(provider)) {
            assertThat(client.chat(new AiRequest("a")).content()).isEqualTo("echo:a");
            assertThat(client.chat(new AiRequest("b")).content()).isEqualTo("echo:b");
        }
    }

    @Test
    void fakeStreamsTokensThenCompletesWithTheWholeResponse() throws Exception {
        FakeAiProvider provider = FakeAiProvider.returning("one two three");
        List<String> tokens = new ArrayList<>();
        AtomicReference<AiResponse> completed = new AtomicReference<>();

        provider.stream(new AiRequest("go"), new AiStreamHandler() {
            @Override public void onToken(String token) { tokens.add(token); }
            @Override public void onComplete(AiResponse response) { completed.set(response); }
        });

        assertThat(tokens).containsExactly("one ", "two ", "three");
        assertThat(completed.get().content()).isEqualTo("one two three");
    }

    @Test
    void scriptedReplaysTransientFailuresThenSucceedsWithoutNetwork() throws Exception {
        ScriptedAiProvider provider = ScriptedAiProvider.create()
                .thenThrow(new AiProviderException("TRANSIENT", 503, "try again"))
                .thenThrow(new AiProviderException("TRANSIENT", 429, "slow down"))
                .then("recovered");
        AiRequest retrying = AiRequest.builder().prompt("flaky")
                .retry(AiRetryPolicy.fixed(3, Duration.ofMillis(1)))
                .build();

        try (AiClient client = new AiClient(provider)) {
            assertThat(client.chat(retrying).content()).isEqualTo("recovered");
            assertThat(provider.callCount()).isEqualTo(3);
            assertThat(provider.remaining()).isZero();
        }
    }

    @Test
    void scriptedStreamReportsScriptedFailureThroughOnError() {
        ScriptedAiProvider provider = ScriptedAiProvider.of(
                new AiProviderException("TRANSIENT", 500, "boom"));
        AtomicReference<Throwable> failed = new AtomicReference<>();

        assertThatThrownBy(() -> provider.stream(new AiRequest("go"), new AiStreamHandler() {
            @Override public void onToken(String token) { }
            @Override public void onError(Throwable error) { failed.set(error); }
        })).isInstanceOf(AiProviderException.class);

        assertThat(failed.get()).isInstanceOf(AiProviderException.class);
    }

    @Test
    void scriptedExhaustionFailsLoudlyInsteadOfRepeatingTheLastReply() throws Exception {
        ScriptedAiProvider provider = ScriptedAiProvider.of(AiResponse.text("only one"));
        try (AiClient client = new AiClient(provider)) {
            assertThat(client.chat(new AiRequest("a")).content()).isEqualTo("only one");

            assertThatThrownBy(() -> client.chat(new AiRequest("b")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exhausted after 2 call(s)");
        }
    }

    @Test
    void scriptedRejectsEntriesThatAreNeitherResponseNorThrowable() {
        assertThatThrownBy(() -> ScriptedAiProvider.of("plain string"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be AiResponse or Throwable");
    }

    @Test
    void fakeFailureKeepsTheTypeRetryPoliciesClassify() {
        FakeAiProvider provider = FakeAiProvider.failing(new java.io.IOException("socket closed"));

        assertThatThrownBy(() -> provider.chat(new AiRequest("go")))
                .isInstanceOf(java.io.IOException.class);
    }
}

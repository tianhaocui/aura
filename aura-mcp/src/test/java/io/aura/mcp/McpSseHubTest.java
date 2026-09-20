package io.aura.mcp;

import io.aura.contract.CancellationToken;
import io.aura.web.SseEmitter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.aura.mcp.McpSseHub.AttachResult.ALREADY_CONNECTED;
import static io.aura.mcp.McpSseHub.AttachResult.ATTACHED;
import static io.aura.mcp.McpSseHub.AttachResult.REPLAY_GAP;
import static io.aura.mcp.McpSseHub.WriteResult.CLOSED;
import static io.aura.mcp.McpSseHub.WriteResult.IDLE;
import static io.aura.mcp.McpSseHub.WriteResult.SENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSseHubTest {
    @Test
    void defaultsKeepWorstCaseRetentionExplicitlyBounded() {
        assertThat(McpSseHub.DEFAULT_QUEUE_EVENTS).isEqualTo(32);
        assertThat(McpSseHub.DEFAULT_QUEUE_BYTES).isEqualTo(64 * 1024);
        assertThat(McpSseHub.DEFAULT_HISTORY_EVENTS).isEqualTo(128);
        assertThat(McpSseHub.DEFAULT_HISTORY_BYTES).isEqualTo(256 * 1024);
    }

    @Test
    void eventAboveDefaultRetentionLimitClosesWithAnExplicitReplayGap() throws Exception {
        try (McpSseHub hub = new McpSseHub(1024 * 1024)) {
            RecordingEmitter first = new RecordingEmitter();
            assertThat(hub.attach("session", first, new CancellationToken(), -1))
                    .isEqualTo(ATTACHED);
            assertThat(hub.publish("session", "small", null)).isTrue();
            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(SENT);

            assertThat(hub.publish("session", "large",
                    Map.of("value", "x".repeat(300 * 1024)))).isFalse();
            assertThat(first.closed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(CLOSED);

            RecordingEmitter resumed = new RecordingEmitter();
            assertThat(hub.attach("session", resumed, new CancellationToken(), 1))
                    .isEqualTo(REPLAY_GAP);
            assertThat(resumed.events).singleElement().asString().contains("event=replay-gap");
        }
    }

    @Test
    void publishQueuesWithoutWritingAndWriterPreservesEventOrderAndIds() throws Exception {
        try (McpSseHub hub = hub(8, 4096, 8, 4096)) {
            RecordingEmitter emitter = new RecordingEmitter();
            assertThat(hub.attach("session", emitter, new CancellationToken(), -1)).isEqualTo(ATTACHED);

            assertThat(hub.publish("session", "notifications/one", Map.of("n", 1))).isTrue();
            assertThat(hub.publish("session", "notifications/two", Map.of("n", 2))).isTrue();
            assertThat(emitter.events).isEmpty();

            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(SENT);
            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(SENT);
            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(IDLE);
            assertThat(emitter.events).hasSize(2);
            assertThat(emitter.events.get(0)).contains("notifications/one").contains("id=1");
            assertThat(emitter.events.get(1)).contains("notifications/two").contains("id=2");
        }
    }

    @Test
    void slowWriterDoesNotBlockConcurrentPublishers() throws Exception {
        try (McpSseHub hub = hub(16, 8192, 16, 8192)) {
            BlockingEmitter emitter = new BlockingEmitter();
            assertThat(hub.attach("session", emitter, new CancellationToken(), -1)).isEqualTo(ATTACHED);
            assertThat(hub.publish("session", "notifications/first", null)).isTrue();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<McpSseHub.WriteResult> writer = executor.submit(
                        () -> hub.writeNext("session", Duration.ofSeconds(5)));
                assertThat(emitter.sendStarted.await(1, TimeUnit.SECONDS)).isTrue();

                Future<Boolean> publisher = executor.submit(
                        () -> hub.publish("session", "notifications/second", null));
                assertThat(publisher.get(1, TimeUnit.SECONDS)).isTrue();
                assertThat(hub.metrics().queuedEvents()).isEqualTo(1L);

                emitter.releaseSend.countDown();
                assertThat(writer.get(1, TimeUnit.SECONDS)).isEqualTo(SENT);
            } finally {
                emitter.releaseSend.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void blockedReplayDoesNotBlockConcurrentPublishers() throws Exception {
        try (McpSseHub hub = hub(8, 4096, 8, 4096)) {
            CancellationToken firstToken = new CancellationToken();
            assertThat(hub.attach("session", new RecordingEmitter(), firstToken, -1)).isEqualTo(ATTACHED);
            publishAndDrain(hub, "replayed");
            firstToken.cancel();

            BlockingReplayEmitter resumed = new BlockingReplayEmitter();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<McpSseHub.AttachResult> attaching = executor.submit(
                        () -> hub.attach("session", resumed, new CancellationToken(), 0));
                assertThat(resumed.replayStarted.await(1, TimeUnit.SECONDS)).isTrue();

                Future<Boolean> publisher = executor.submit(
                        () -> hub.publish("session", "queued-during-replay", null));
                assertThat(publisher.get(1, TimeUnit.SECONDS)).isTrue();
                assertThat(hub.metrics().queuedEvents()).isEqualTo(1L);

                resumed.releaseReplay.countDown();
                assertThat(attaching.get(1, TimeUnit.SECONDS)).isEqualTo(ATTACHED);
                assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(SENT);
                assertThat(resumed.events).hasSize(2);
                assertThat(resumed.events.get(0)).contains("replayed").contains("id=1");
                assertThat(resumed.events.get(1)).contains("queued-during-replay").contains("id=2");
            } finally {
                resumed.releaseReplay.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void eventCountOverflowClosesConnectionAndOverflowEventCanBeReplayed() throws Exception {
        try (McpSseHub hub = hub(1, 4096, 8, 4096)) {
            RecordingEmitter first = new RecordingEmitter();
            assertThat(hub.attach("session", first, new CancellationToken(), -1)).isEqualTo(ATTACHED);
            assertThat(hub.publish("session", "notifications/one", null)).isTrue();
            assertThat(hub.publish("session", "notifications/overflow", null)).isFalse();
            assertThat(hub.connected("session")).isFalse();
            assertThat(first.closed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(CLOSED);

            RecordingEmitter resumed = new RecordingEmitter();
            assertThat(hub.attach("session", resumed, new CancellationToken(), 1)).isEqualTo(ATTACHED);
            assertThat(resumed.events).singleElement()
                    .asString().contains("notifications/overflow").contains("id=2");
            assertThat(hub.metrics().overflowedConnections()).isEqualTo(1L);
        }
    }

    @Test
    void staleConnectionHandleCannotConsumeOrCloseAReconnectedGeneration() throws Exception {
        try (McpSseHub hub = hub(1, 4096, 8, 4096)) {
            RecordingEmitter first = new RecordingEmitter();
            McpSseHub.Attachment old = hub.attachWithHandle(
                    "session", first, new CancellationToken(), -1);
            assertThat(old.result()).isEqualTo(ATTACHED);
            assertThat(hub.publish("session", "one", null)).isTrue();
            assertThat(hub.publish("session", "overflow", null)).isFalse();
            assertThat(first.closed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(hub.writeNext(old.handle(), Duration.ZERO)).isEqualTo(CLOSED);

            RecordingEmitter reconnected = new RecordingEmitter();
            McpSseHub.Attachment current = hub.attachWithHandle(
                    "session", reconnected, new CancellationToken(), 2);
            assertThat(current.result()).isEqualTo(ATTACHED);

            assertThat(hub.writeNext(old.handle(), Duration.ZERO)).isEqualTo(CLOSED);
            hub.close(old.handle());
            assertThat(reconnected.closed.getCount()).isEqualTo(1L);
            assertThat(hub.publish("session", "current", null)).isTrue();
            assertThat(hub.writeNext(current.handle(), Duration.ZERO)).isEqualTo(SENT);
            assertThat(reconnected.events).singleElement().asString().contains("current");
        }
    }

    @Test
    void utf8QueueByteLimitOverflowsBeforeEventCountLimit() throws Exception {
        try (McpSseHub hub = new McpSseHub(256, 10, 256, 10, 2048)) {
            RecordingEmitter emitter = new RecordingEmitter();
            assertThat(hub.attach("session", emitter, new CancellationToken(), -1)).isEqualTo(ATTACHED);
            Map<String, String> multibyte = Map.of("value", "界".repeat(40));

            assertThat(hub.publish("session", "n", multibyte)).isTrue();
            assertThat(hub.publish("session", "n", multibyte)).isFalse();
            assertThat(hub.metrics().overflowedConnections()).isEqualTo(1L);
            assertThat(emitter.closed.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void historyTrimsByEventCountAndReplaysRetainedSuffix() throws Exception {
        try (McpSseHub hub = hub(8, 4096, 2, 4096)) {
            CancellationToken token = new CancellationToken();
            assertThat(hub.attach("session", new RecordingEmitter(), token, -1)).isEqualTo(ATTACHED);
            publishAndDrain(hub, "one");
            publishAndDrain(hub, "two");
            publishAndDrain(hub, "three");
            token.cancel();

            RecordingEmitter resumed = new RecordingEmitter();
            assertThat(hub.attach("session", resumed, new CancellationToken(), 1)).isEqualTo(ATTACHED);
            assertThat(resumed.events).hasSize(2);
            assertThat(resumed.events.get(0)).contains("two").contains("id=2");
            assertThat(resumed.events.get(1)).contains("three").contains("id=3");
        }
    }

    @Test
    void historyTrimsByUtf8ByteCount() throws Exception {
        try (McpSseHub hub = new McpSseHub(256, 8, 2048, 8, 256)) {
            CancellationToken token = new CancellationToken();
            assertThat(hub.attach("session", new RecordingEmitter(), token, -1)).isEqualTo(ATTACHED);
            Map<String, String> multibyte = Map.of("value", "界".repeat(40));
            assertThat(hub.publish("session", "first", multibyte)).isTrue();
            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(SENT);
            assertThat(hub.publish("session", "second", multibyte)).isTrue();
            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(SENT);
            token.cancel();

            RecordingEmitter resumed = new RecordingEmitter();
            assertThat(hub.attach("session", resumed, new CancellationToken(), 1)).isEqualTo(ATTACHED);
            assertThat(resumed.events).singleElement().asString().contains("second").contains("id=2");
        }
    }

    @Test
    void staleAndAheadLastEventIdsSendReplayGapWithoutConnecting() throws Exception {
        try (McpSseHub hub = hub(8, 4096, 1, 4096)) {
            CancellationToken token = new CancellationToken();
            assertThat(hub.attach("session", new RecordingEmitter(), token, -1)).isEqualTo(ATTACHED);
            publishAndDrain(hub, "one");
            publishAndDrain(hub, "two");
            token.cancel();

            RecordingEmitter stale = new RecordingEmitter();
            assertThat(hub.attach("session", stale, new CancellationToken(), 0)).isEqualTo(REPLAY_GAP);
            assertThat(stale.events).singleElement().asString().contains("event=replay-gap");
            assertThat(stale.closed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(hub.connected("session")).isFalse();

            RecordingEmitter ahead = new RecordingEmitter();
            assertThat(hub.attach("session", ahead, new CancellationToken(), 3)).isEqualTo(REPLAY_GAP);
            assertThat(ahead.events).singleElement().asString().contains("event=replay-gap");
            assertThat(hub.connected("session")).isFalse();
            assertThat(hub.metrics().replayGaps()).isEqualTo(2L);
        }
    }

    @Test
    void normalReplayRunsOnAttachingThreadAndDuplicateAttachIsRejected() throws Exception {
        try (McpSseHub hub = hub(8, 4096, 8, 4096)) {
            CancellationToken token = new CancellationToken();
            assertThat(hub.attach("session", new RecordingEmitter(), token, -1)).isEqualTo(ATTACHED);
            publishAndDrain(hub, "one");
            publishAndDrain(hub, "two");
            token.cancel();

            RecordingEmitter resumed = new RecordingEmitter();
            Thread attachingThread = Thread.currentThread();
            assertThat(hub.attach("session", resumed, new CancellationToken(), 1)).isEqualTo(ATTACHED);
            assertThat(resumed.events).singleElement().asString().contains("two").contains("id=2");
            assertThat(resumed.sendThreads).containsExactly(attachingThread);
            assertThat(hub.attach("session", new RecordingEmitter(), new CancellationToken(), 1))
                    .isEqualTo(ALREADY_CONNECTED);
        }
    }

    @Test
    void rejectsOversizedPublishedEventsWithoutChangingQueueOrHistorySequence() throws Exception {
        try (McpSseHub hub = new McpSseHub(80, 8, 512, 8, 512)) {
            RecordingEmitter emitter = new RecordingEmitter();
            assertThat(hub.attach("session", emitter, new CancellationToken(), -1)).isEqualTo(ATTACHED);
            assertThat(hub.publish("session", "notifications/large", Map.of("value", "x".repeat(100))))
                    .isFalse();
            assertThat(hub.metrics().queuedEvents()).isZero();
            assertThat(hub.publish("session", "ok", null)).isTrue();
            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(SENT);
            assertThat(emitter.events).singleElement().asString().contains("id=1");
        }
    }

    @Test
    void sessionRemovalDeletesHistoryWhileCloseRetainsIt() throws Exception {
        try (McpSseHub hub = hub(8, 4096, 8, 4096)) {
            RecordingEmitter emitter = new RecordingEmitter();
            assertThat(hub.attach("session", emitter, new CancellationToken(), -1))
                    .isEqualTo(ATTACHED);
            publishAndDrain(hub, "one");
            hub.close("session");
            assertThat(hub.metrics().histories()).isEqualTo(1L);

            hub.sessionRemoved("session");
            assertThat(hub.metrics().histories()).isZero();
            assertThat(emitter.closed.await(1, TimeUnit.SECONDS)).isTrue();
            RecordingEmitter fresh = new RecordingEmitter();
            assertThat(hub.attach("session", fresh, new CancellationToken(), 0)).isEqualTo(ATTACHED);
            assertThat(fresh.events).isEmpty();
        }
    }

    @Test
    void sessionRemovalDoesNotWaitForBlockingEmitterClose() throws Exception {
        McpSseHub hub = hub(8, 4096, 8, 4096);
        BlockingCloseEmitter emitter = new BlockingCloseEmitter();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(hub.attach("session", emitter, new CancellationToken(), -1)).isEqualTo(ATTACHED);
            Future<?> removal = executor.submit(() -> hub.sessionRemoved("session"));

            removal.get(1, TimeUnit.SECONDS);
            assertThat(emitter.closeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(hub.metrics().histories()).isZero();
        } finally {
            emitter.releaseClose.countDown();
            executor.shutdownNow();
            hub.close();
        }
    }

    @Test
    void rejectedCloseTaskRemainsReachableUntilWriterClosesEmitter() throws Exception {
        McpSseHub hub = hub(1, 4096, 8, 4096);
        BlockingCloseEmitter blocker = new BlockingCloseEmitter();
        try {
            overflow(hub, "blocked", blocker);
            assertThat(blocker.closeStarted.await(1, TimeUnit.SECONDS)).isTrue();

            for (int index = 0; index < 64; index++) {
                overflow(hub, "queued-" + index, new RecordingEmitter());
            }
            RecordingEmitter rejected = new RecordingEmitter();
            overflow(hub, "rejected", rejected);
            assertThat(rejected.closed.getCount()).isEqualTo(1L);

            assertThat(hub.writeNext("rejected", Duration.ZERO)).isEqualTo(CLOSED);
            assertThat(rejected.closed.await(1, TimeUnit.SECONDS)).isTrue();

            RecordingEmitter reattached = new RecordingEmitter();
            assertThat(hub.attach("rejected", reattached, new CancellationToken(), 2)).isEqualTo(ATTACHED);
        } finally {
            blocker.releaseClose.countDown();
            hub.close();
        }
    }

    @Test
    void metricsReportLiveUsageAndCumulativeFailures() throws Exception {
        try (McpSseHub hub = hub(2, 4096, 8, 4096)) {
            RecordingEmitter emitter = new RecordingEmitter();
            assertThat(hub.attach("one", emitter, new CancellationToken(), -1)).isEqualTo(ATTACHED);
            assertThat(hub.publish("one", "queued", null)).isTrue();

            McpSseMetrics live = hub.metrics();
            assertThat(live.activeConnections()).isEqualTo(1L);
            assertThat(live.closingConnections()).isZero();
            assertThat(live.histories()).isEqualTo(1L);
            assertThat(live.queuedEvents()).isEqualTo(1L);
            assertThat(live.queuedBytes()).isPositive();

            RecordingEmitter gap = new RecordingEmitter();
            assertThat(hub.attach("ahead", gap, new CancellationToken(), 1)).isEqualTo(REPLAY_GAP);
            assertThat(hub.metrics().replayGaps()).isEqualTo(1L);
        }
    }

    @Test
    void writerFailureClosesConnectionAndIncrementsMetric() throws Exception {
        try (McpSseHub hub = hub(8, 4096, 8, 4096)) {
            FailingEmitter emitter = new FailingEmitter();
            assertThat(hub.attach("session", emitter, new CancellationToken(), -1)).isEqualTo(ATTACHED);
            assertThat(hub.publish("session", "notification", null)).isTrue();

            assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(CLOSED);
            assertThat(hub.connected("session")).isFalse();
            assertThat(emitter.closed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(hub.metrics().writeFailures()).isEqualTo(1L);
        }
    }

    @Test
    void cancellationClosesConnectionAndWakesWaitingWriter() throws Exception {
        try (McpSseHub hub = hub(8, 4096, 8, 4096)) {
            CancellationToken token = new CancellationToken();
            RecordingEmitter emitter = new RecordingEmitter();
            assertThat(hub.attach("session", emitter, token, -1)).isEqualTo(ATTACHED);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<McpSseHub.WriteResult> writer = executor.submit(
                        () -> hub.writeNext("session", Duration.ofSeconds(30)));
                token.cancel();
                assertThat(writer.get(1, TimeUnit.SECONDS)).isEqualTo(CLOSED);
                assertThat(emitter.closed.await(1, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void validatesAllCapacityArgumentsAndAllowsRetentionBelowEventLimit() {
        assertThatThrownBy(() -> new McpSseHub(0, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpSseHub(1, 0, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpSseHub(1, 1, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpSseHub(1, 1, 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpSseHub(1, 1, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatCode(() -> {
            try (McpSseHub ignored = new McpSseHub(2, 1, 1, 1, 1)) { }
        }).doesNotThrowAnyException();
    }

    private static McpSseHub hub(int queueEvents, int queueBytes,
                                 int historyEvents, int historyBytes) {
        return new McpSseHub(1024, queueEvents, queueBytes, historyEvents, historyBytes);
    }

    private static void publishAndDrain(McpSseHub hub, String method) throws InterruptedException {
        assertThat(hub.publish("session", method, null)).isTrue();
        assertThat(hub.writeNext("session", Duration.ZERO)).isEqualTo(SENT);
    }

    private static void overflow(McpSseHub hub, String sessionId, RecordingEmitter emitter) {
        assertThat(hub.attach(sessionId, emitter, new CancellationToken(), -1)).isEqualTo(ATTACHED);
        assertThat(hub.publish(sessionId, "one", null)).isTrue();
        assertThat(hub.publish(sessionId, "overflow", null)).isFalse();
    }

    private static class RecordingEmitter implements SseEmitter {
        protected final List<String> events = new CopyOnWriteArrayList<>();
        private final List<Thread> sendThreads = new CopyOnWriteArrayList<>();
        protected final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void send(String data) throws Exception {
            record("data=" + data);
        }

        @Override
        public void send(String event, String data) throws Exception {
            record("event=" + event + ":" + data);
        }

        @Override
        public void send(String event, String data, String id) throws Exception {
            record("event=" + event + ",id=" + id + ":" + data);
        }

        protected void record(String event) throws Exception {
            sendThreads.add(Thread.currentThread());
            events.add(event);
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class BlockingEmitter extends RecordingEmitter {
        private final CountDownLatch sendStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSend = new CountDownLatch(1);

        @Override
        protected void record(String event) throws Exception {
            sendStarted.countDown();
            releaseSend.await();
            super.record(event);
        }
    }

    private static final class BlockingReplayEmitter extends RecordingEmitter {
        private final AtomicBoolean firstSend = new AtomicBoolean(true);
        private final CountDownLatch replayStarted = new CountDownLatch(1);
        private final CountDownLatch releaseReplay = new CountDownLatch(1);

        @Override
        protected void record(String event) throws Exception {
            if (firstSend.compareAndSet(true, false)) {
                replayStarted.countDown();
                releaseReplay.await();
            }
            super.record(event);
        }
    }

    private static final class BlockingCloseEmitter extends RecordingEmitter {
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        @Override
        public void close() {
            closeStarted.countDown();
            try {
                releaseClose.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            super.close();
        }
    }

    private static final class FailingEmitter extends RecordingEmitter {
        @Override
        protected void record(String event) {
            throw new IllegalStateException("write failed");
        }
    }
}

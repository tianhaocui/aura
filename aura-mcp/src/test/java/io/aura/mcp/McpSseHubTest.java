package io.aura.mcp;

import io.aura.contract.CancellationToken;
import io.aura.web.SseEmitter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpSseHubTest {
    @Test
    void replaysOnlyEventsAfterLastEventIdWithinBoundedSessionHistory() {
        McpSseHub hub = new McpSseHub(1024);
        CancellationToken firstToken = new CancellationToken();
        RecordingEmitter first = new RecordingEmitter();
        assertThat(hub.attach("session", first, firstToken, -1)).isTrue();
        assertThat(hub.publish("session", "notifications/one", Map.of("n", 1))).isTrue();
        assertThat(hub.publish("session", "notifications/two", Map.of("n", 2))).isTrue();
        firstToken.cancel();

        RecordingEmitter resumed = new RecordingEmitter();
        assertThat(hub.attach("session", resumed, new CancellationToken(), 1)).isTrue();
        assertThat(resumed.events).hasSize(1);
        assertThat(resumed.events.get(0)).contains("notifications/two").contains("id=2");
        hub.close();
    }

    @Test
    void rejectsOversizedPublishedEvents() {
        McpSseHub hub = new McpSseHub(16);
        RecordingEmitter emitter = new RecordingEmitter();
        assertThat(hub.attach("session", emitter, new CancellationToken(), -1)).isTrue();
        assertThat(hub.publish("session", "notifications/large", Map.of("value", "x".repeat(100))))
                .isFalse();
        hub.close();
    }

    private static final class RecordingEmitter implements SseEmitter {
        private final List<String> events = new ArrayList<>();
        @Override public void send(String data) { events.add("data=" + data); }
        @Override public void send(String event, String data) { events.add("event=" + event + ":" + data); }
        @Override public void send(String event, String data, String id) {
            events.add("event=" + event + ",id=" + id + ":" + data);
        }
        @Override public void close() { }
    }
}

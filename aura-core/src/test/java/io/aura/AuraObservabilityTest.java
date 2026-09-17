package io.aura;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuraObservabilityTest {
    @Test
    void emitsImmutableEventsAndKeepsListenerFailuresIsolated() {
        List<AuraEvent> received = new ArrayList<>();
        AuraApp app = AuraApp.create()
                .observe(event -> { throw new IllegalStateException("listener failure"); })
                .observe(received::add);

        AuraEvent event = AuraEvent.of("request.completed", java.util.Map.of("status", 200));
        ((AuraWebRuntime) app.runtimeView()).emit(event);

        assertThat(received).containsExactly(event);
        assertThat(event.attributes()).containsEntry("status", 200);
    }
}

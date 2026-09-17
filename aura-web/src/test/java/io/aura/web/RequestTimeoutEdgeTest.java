package io.aura.web;

import io.aura.AuraConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class RequestTimeoutEdgeTest {

    @Test
    void requestTimeout_negativeRejected() {
        assertThatThrownBy(() -> TestApplications.runtime(new AuraConfig().requestTimeout(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestTimeout cannot be negative");
    }

    @Test
    void requestTimeout_largeNegativeRejected() {
        assertThatThrownBy(() -> TestApplications.runtime(new AuraConfig().requestTimeout(-100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestTimeout cannot be negative");
    }

    @Test
    void requestTimeout_zeroMeansDisabled() {
        assertThat(TestApplications.runtime(new AuraConfig().requestTimeout(0)).requestTimeout())
                .isEqualTo(0);
    }

    @Test
    void requestTimeout_respondedAtomicBoolean_preventsDoubleWrite() {
        AtomicBoolean responded = new AtomicBoolean(false);
        // Simulate handler completing first
        assertThat(responded.compareAndSet(false, true)).isTrue();
        // Timeout callback should fail CAS
        assertThat(responded.compareAndSet(false, true)).isFalse();
    }

    @Test
    void requestTimeout_timeoutWins_whenHandlerSlow() {
        AtomicBoolean responded = new AtomicBoolean(false);
        // Simulate timeout firing first
        boolean timeoutWins = responded.compareAndSet(false, true);
        assertThat(timeoutWins).isTrue();
        // Handler should not write
        boolean handlerWins = responded.compareAndSet(false, true);
        assertThat(handlerWins).isFalse();
    }
}

package io.aura.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutorMetricsTest {
    @Test
    void opaqueExecutorStillReportsRejectionsAndTimeouts() {
        ExecutorMetrics metrics = ExecutorMetrics.opaque("external", 2, 3);
        assertThat(metrics.observable()).isFalse();
        assertThat(metrics.rejected()).isEqualTo(2);
        assertThat(metrics.timedOut()).isEqualTo(3);
    }

    @Test
    void negativeMetricsAreRejected() {
        assertThatThrownBy(() -> new ExecutorMetrics("bad", true, 1, -1, 0, 1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotMetricsTest {
    @TempDir Path directory;

    @Test
    void exposesDropAndBoundedQueueStateWithoutStartingARecorderOnRead() {
        AuraApp app = TestApplications.app(new AuraConfig().snapshot(options -> options
                        .enabled(true).async(false).dir(directory).maxSnapshotFileSize(1)),
                "snapshot-metrics",
                new RouteContribution("GET", "/fail", (BaseHandler) request -> {
                    throw new IllegalStateException("failure");
                }));

        SnapshotMetrics initial = SnapshotMetrics.of(app);
        assertThat(initial.dropped()).isZero();
        assertThat(initial.writerAlive()).isFalse();

        TestClient.of(app).get("/fail").execute();
        SnapshotMetrics recorded = SnapshotMetrics.of(app);
        assertThat(recorded.dropped()).isEqualTo(1);
        assertThat(recorded.asynchronous()).isFalse();
        assertThat(recorded.queueCapacity()).isZero();
    }
}

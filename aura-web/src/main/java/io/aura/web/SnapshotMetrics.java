package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraWebRuntime;

/** Read-only snapshot writer metrics for optional observability adapters. */
public record SnapshotMetrics(long dropped, int queued, int queueCapacity,
                              boolean asynchronous, boolean writerAlive) {
    public SnapshotMetrics {
        if (dropped < 0 || queued < 0 || queueCapacity < 0) {
            throw new IllegalArgumentException("snapshot metrics cannot be negative");
        }
    }

    public static SnapshotMetrics of(AuraApp app) {
        if (app == null) throw new IllegalArgumentException("app must not be null");
        if (!(app.runtimeView() instanceof AuraWebRuntime runtime)) {
            throw new IllegalArgumentException("Snapshot metrics require the Aura Web runtime");
        }
        return SnapshotRecorder.metrics(runtime);
    }
}

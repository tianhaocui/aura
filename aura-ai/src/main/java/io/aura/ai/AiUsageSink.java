package io.aura.ai;

import java.time.Instant;
import java.util.Objects;

/** Optional cross-process accounting/audit extension for completed AI calls. */
@FunctionalInterface
public interface AiUsageSink {
    void record(UsageEvent event);

    AiUsageSink NOOP = event -> { };

    record UsageEvent(Instant completedAt, String model, long inputCharacters,
                      AiUsage usage, boolean usageKnown) {
        public UsageEvent {
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
            model = model == null ? "" : model;
            if (inputCharacters < 0) throw new IllegalArgumentException("inputCharacters must not be negative");
            usage = Objects.requireNonNull(usage, "usage");
        }
    }
}

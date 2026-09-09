package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSessionManagerTest {
    @Test
    void successfulAccessExtendsIdleDeadline() {
        MutableClock clock = new MutableClock();
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5), clock);
        String id = manager.create("alice").id();

        clock.advance(Duration.ofMinutes(4));
        assertThat(manager.access(id, "alice")).isPresent();
        clock.advance(Duration.ofMinutes(4));
        assertThat(manager.access(id, "alice")).isPresent();
        clock.advance(Duration.ofMinutes(5));
        assertThat(manager.access(id, "alice")).isEmpty();
        assertThat(manager.size()).isZero();
    }

    @Test
    void wrongIdentityDoesNotRefreshOrDeleteSession() {
        MutableClock clock = new MutableClock();
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5), clock);
        String id = manager.create("alice").id();

        clock.advance(Duration.ofMinutes(4));
        assertThat(manager.access(id, "bob")).isEmpty();
        assertThat(manager.access(id, "alice")).isPresent();
    }

    @Test
    void expiredSessionsReleaseCapacity() {
        MutableClock clock = new MutableClock();
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5), clock);
        manager.create("alice");
        assertThatThrownBy(() -> manager.create("bob"))
                .isInstanceOf(McpSessionManager.SessionLimitException.class);

        clock.advance(Duration.ofMinutes(5));
        assertThat(manager.create("bob").userId()).isEqualTo("bob");
        assertThat(manager.size()).isEqualTo(1);
    }

    @Test
    void validatesHardResourceBounds() {
        assertThatThrownBy(() -> new McpSessionManager(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpSessionManager(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpSessionManager(1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpSessionManager(1, Duration.ofDays(3651)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identityIsCanonicalizedAcrossLifecycleOperations() {
        McpSessionManager manager = new McpSessionManager(2, Duration.ofMinutes(5));
        String id = manager.create(" alice ").id();
        assertThat(manager.access(id, "alice")).isPresent();
        assertThat(manager.close(id, " alice ")).isTrue();
    }

    @Test
    void concurrentCreationCannotExceedCapacity() throws Exception {
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> (java.util.concurrent.Callable<Boolean>) () -> {
                        start.await();
                        try { manager.create("user-" + index); return true; }
                        catch (McpSessionManager.SessionLimitException full) { return false; }
                    }).toList();
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            int created = 0;
            for (var future : futures) if (future.get()) created++;
            assertThat(created).isEqualTo(1);
            assertThat(manager.size()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }
}

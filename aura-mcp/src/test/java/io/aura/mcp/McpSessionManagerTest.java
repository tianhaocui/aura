package io.aura.mcp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSessionManagerTest {
    @Test
    void successfulAccessExtendsIdleDeadline() {
        MutableClock clock = new MutableClock();
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5), clock);
        String id = manager.create("alice", "2025-06-18").id();

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
        String id = manager.create("alice", "2025-06-18").id();

        clock.advance(Duration.ofMinutes(4));
        assertThat(manager.access(id, "bob")).isEmpty();
        assertThat(manager.access(id, "alice")).isPresent();
    }

    @Test
    void expiredSessionsReleaseCapacity() {
        MutableClock clock = new MutableClock();
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5), clock);
        manager.create("alice", "2025-06-18");
        assertThatThrownBy(() -> manager.create("bob", "2025-06-18"))
                .isInstanceOf(McpSessionManager.SessionLimitException.class);

        clock.advance(Duration.ofMinutes(5));
        assertThat(manager.create("bob", "2025-06-18").userId()).isEqualTo("bob");
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
        String id = manager.create(" alice ", " 2025-06-18 ").id();
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
                        try { manager.create("user-" + index, "2025-06-18"); return true; }
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

    @Test
    void recordsProtocolVersionAndStartsNegotiating() {
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5));

        McpSessionManager.Session session = manager.create("alice", " 2025-06-18 ");

        assertThat(session.protocolVersion()).isEqualTo("2025-06-18");
        assertThat(session.phase()).isEqualTo(McpSessionManager.SessionPhase.NEGOTIATING);
        assertThat(session.inFlight()).isZero();
        assertThat(manager.inspect(session.id(), "alice")).contains(session);
        assertThatThrownBy(() -> manager.create("bob", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activationIsAOneTimeTransitionAndNegotiatingSessionsCannotAdmit() {
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5));
        String id = manager.create("alice", "2025-06-18").id();

        assertThat(manager.admitReady(id, "alice")).isEmpty();
        assertThat(manager.activate(id, "bob")).isEqualTo(McpSessionManager.TransitionResult.NOT_FOUND);
        assertThat(manager.activate(id, "alice")).isEqualTo(McpSessionManager.TransitionResult.SUCCESS);
        assertThat(manager.activate(id, "alice")).isEqualTo(McpSessionManager.TransitionResult.INVALID_PHASE);
        assertThat(manager.inspect(id, "alice")).get()
                .extracting(McpSessionManager.Session::phase)
                .isEqualTo(McpSessionManager.SessionPhase.READY);
    }

    @Test
    void readyAdmissionTouchesAndLeaseReleaseIsIdempotent() {
        MutableClock clock = new MutableClock();
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5), clock);
        String id = manager.create("alice", "2025-06-18").id();
        manager.activate(id, "alice");
        clock.advance(Duration.ofMinutes(4));

        McpSessionManager.RequestLease lease = manager.admitReady(id, "alice").orElseThrow();

        assertThat(lease.session().lastAccessedAt()).isEqualTo(clock.instant());
        assertThat(lease.session().inFlight()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(4));
        assertThat(manager.inspect(id, "alice")).isPresent();
        lease.close();
        lease.close();
        assertThat(manager.inspect(id, "alice")).get()
                .extracting(McpSessionManager.Session::inFlight)
                .isEqualTo(0);
    }

    @Test
    void closeBlocksNewAdmissionsAndDefersRemovalUntilLeaseRelease() {
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5));
        String id = manager.create("alice", "2025-06-18").id();
        manager.activate(id, "alice");
        McpSessionManager.RequestLease lease = manager.admitReady(id, "alice").orElseThrow();

        assertThat(manager.close(id, "alice")).isTrue();
        assertThat(manager.active(id, "alice")).isFalse();
        assertThat(manager.inspect(id, "alice")).isEmpty();
        assertThat(manager.access(id, "alice")).isEmpty();
        assertThat(manager.admitReady(id, "alice")).isEmpty();
        assertThat(manager.size()).isEqualTo(1);
        assertThatThrownBy(() -> manager.create("bob", "2025-06-18"))
                .isInstanceOf(McpSessionManager.SessionLimitException.class);

        lease.close();

        assertThat(manager.size()).isZero();
        assertThat(manager.create("bob", "2025-06-18").userId()).isEqualTo("bob");
    }

    @Test
    void closeAndAdmissionAreLinearized() throws Exception {
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5));
        String id = manager.create("alice", "2025-06-18").id();
        manager.activate(id, "alice");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<java.util.Optional<McpSessionManager.RequestLease>> admission = executor.submit(() -> {
                start.await();
                return manager.admitReady(id, "alice");
            });
            Future<Boolean> close = executor.submit(() -> {
                start.await();
                return manager.close(id, "alice");
            });

            start.countDown();
            java.util.Optional<McpSessionManager.RequestLease> lease = admission.get();

            assertThat(close.get()).isTrue();
            assertThat(manager.admitReady(id, "alice")).isEmpty();
            assertThat(manager.inspect(id, "alice")).isEmpty();
            assertThat(manager.size()).isEqualTo(lease.isPresent() ? 1 : 0);
            lease.ifPresent(McpSessionManager.RequestLease::close);
            assertThat(manager.size()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expirationClosesInFlightSessionUntilLeaseRelease() {
        MutableClock clock = new MutableClock();
        McpSessionManager manager = new McpSessionManager(1, Duration.ofMinutes(5), clock);
        String id = manager.create("alice", "2025-06-18").id();
        manager.activate(id, "alice");
        McpSessionManager.RequestLease lease = manager.admitReady(id, "alice").orElseThrow();

        clock.advance(Duration.ofMinutes(5));

        assertThat(manager.inspect(id, "alice")).isEmpty();
        assertThat(manager.admitReady(id, "alice")).isEmpty();
        assertThat(manager.size()).isEqualTo(1);
        lease.close();
        assertThat(manager.size()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }
}

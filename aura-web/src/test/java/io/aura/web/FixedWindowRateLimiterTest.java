package io.aura.web;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    @Test
    void enforcesPerKeyLimitAndResetsAtTheNextWindow() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(8);

        assertThat(limiter.tryAcquire("alice", 2, 10)).isTrue();
        assertThat(limiter.tryAcquire("alice", 2, 10)).isTrue();
        assertThat(limiter.tryAcquire("alice", 2, 10)).isFalse();
        assertThat(limiter.tryAcquire("bob", 2, 10)).isTrue();

        assertThat(limiter.tryAcquire("alice", 2, 11)).isTrue();
        assertThat(limiter.trackedKeyCount()).isEqualTo(1);
    }

    @Test
    void rejectsNewKeysWhenCapacityIsReachedWithoutGrowingTheMap() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2);

        assertThat(limiter.tryAcquire("first", 10, 20)).isTrue();
        assertThat(limiter.tryAcquire("second", 10, 20)).isTrue();
        assertThat(limiter.tryAcquire("third", 10, 20)).isFalse();
        assertThat(limiter.trackedKeyCount()).isEqualTo(2);
        assertThat(limiter.tryAcquire("first", 10, 20)).isTrue();
    }

    @Test
    void concurrentNewKeysNeverExceedCapacity() throws Exception {
        int capacity = 32;
        int attempts = 256;
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(capacity);
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            for (int i = 0; i < attempts; i++) {
                int key = i;
                executor.submit(() -> {
                    start.await();
                    if (limiter.tryAcquire("key-" + key, 1, 30)) admitted.incrementAndGet();
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(admitted).hasValue(capacity);
        assertThat(limiter.trackedKeyCount()).isEqualTo(capacity);
    }
}

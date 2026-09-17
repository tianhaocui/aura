package io.aura.web;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key, one-second fixed-window limiter with a hard cap on tracked keys.
 * Stale windows are removed lazily, so untrusted high-cardinality keys cannot
 * grow the process heap without bound.
 */
final class FixedWindowRateLimiter {
    static final int DEFAULT_MAX_KEYS = 65_536;

    private final int maxKeys;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicInteger trackedKeys = new AtomicInteger();
    private final AtomicLong lastCleanupSecond = new AtomicLong(Long.MIN_VALUE);

    FixedWindowRateLimiter() {
        this(DEFAULT_MAX_KEYS);
    }

    FixedWindowRateLimiter(int maxKeys) {
        if (maxKeys < 1) throw new IllegalArgumentException("maxKeys must be positive");
        this.maxKeys = maxKeys;
    }

    boolean tryAcquire(String key, int permitsPerSecond, long nowSecond) {
        if (permitsPerSecond < 1) return true;
        String normalizedKey = key == null ? "unknown" : key;
        cleanupStaleWindows(nowSecond);

        while (true) {
            WindowCounter current = counters.get(normalizedKey);
            if (current != null) {
                if (current.second == nowSecond) {
                    return current.count.incrementAndGet() <= permitsPerSecond;
                }
                WindowCounter replacement = new WindowCounter(nowSecond);
                if (counters.replace(normalizedKey, current, replacement)) {
                    return replacement.count.incrementAndGet() <= permitsPerSecond;
                }
                continue;
            }

            if (trackedKeys.incrementAndGet() > maxKeys) {
                trackedKeys.decrementAndGet();
                return false;
            }
            WindowCounter created = new WindowCounter(nowSecond);
            WindowCounter raced = counters.putIfAbsent(normalizedKey, created);
            if (raced == null) {
                return created.count.incrementAndGet() <= permitsPerSecond;
            }
            trackedKeys.decrementAndGet();
        }
    }

    int trackedKeyCount() {
        return trackedKeys.get();
    }

    private void cleanupStaleWindows(long nowSecond) {
        long previous = lastCleanupSecond.get();
        if (previous == nowSecond || !lastCleanupSecond.compareAndSet(previous, nowSecond)) return;
        counters.forEach((key, counter) -> {
            if (counter.second != nowSecond && counters.remove(key, counter)) {
                trackedKeys.decrementAndGet();
            }
        });
    }

    private static final class WindowCounter {
        private final long second;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long second) {
            this.second = second;
        }
    }
}

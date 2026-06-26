package io.aura.web;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class RateLimiter {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    RateLimiter(Duration window) {
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aura-rate-limiter");
            t.setDaemon(true);
            return t;
        });
        long windowMs = window.toMillis();
        cleaner.scheduleAtFixedRate(counters::clear, windowMs, windowMs, TimeUnit.MILLISECONDS);
    }

    boolean allow(String key, int limit) {
        AtomicInteger counter = counters.computeIfAbsent(key, k -> new AtomicInteger(0));
        return counter.incrementAndGet() <= limit;
    }

    void shutdown() {
        cleaner.shutdownNow();
    }
}

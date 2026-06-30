package io.aura.web;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class RateLimiter {

    private record Entry(long timestamp) {}

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>> requests = new ConcurrentHashMap<>();

    boolean allow(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        CopyOnWriteArrayList<Entry> entries = requests.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        entries.removeIf(e -> now - e.timestamp > windowMs);
        if (entries.size() >= limit) return false;
        entries.add(new Entry(now));
        return true;
    }

    void shutdown() {
        requests.clear();
    }
}

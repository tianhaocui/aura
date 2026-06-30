package io.aura.web;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class RateLimiter {

    private record Entry(long timestamp) {}

    private final ConcurrentHashMap<String, List<Entry>> requests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    RateLimiter() {
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aura-ratelimit-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::removeEmptyKeys, 60, 60, TimeUnit.SECONDS);
    }

    boolean allow(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        List<Entry> entries = requests.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (entries) {
            entries.removeIf(e -> now - e.timestamp > windowMs);
            if (entries.size() >= limit) return false;
            entries.add(new Entry(now));
            return true;
        }
    }

    private void removeEmptyKeys() {
        requests.entrySet().removeIf(e -> {
            List<Entry> list = e.getValue();
            synchronized (list) {
                return list.isEmpty();
            }
        });
    }

    void shutdown() {
        cleaner.shutdownNow();
        requests.clear();
    }
}

package io.aura.web;

import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class MonitorDemo {

    static final AtomicLong requestCount = new AtomicLong();
    static final AtomicLong errorCount = new AtomicLong();
    static final long startTime = System.currentTimeMillis();
    static final List<Map<String, Object>> recentErrors = new CopyOnWriteArrayList<>();

    record HealthStatus(String status, long uptimeMs, String startedAt) {}

    public static void main(String[] args) {
        BaseHandler health = counted(ctx -> ctx.json(new HealthStatus(
                "UP",
                System.currentTimeMillis() - startTime,
                Instant.ofEpochMilli(startTime).toString())));

        BaseHandler status = counted(ctx -> {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

            var payload = new LinkedHashMap<String, Object>();
            payload.put("app", "Aura Monitor Demo");
            payload.put("uptime", formatDuration(runtime.getUptime()));
            payload.put("requests", requestCount.get());
            payload.put("errors", errorCount.get());

            var memory = new LinkedHashMap<String, Object>();
            long used = mem.getHeapMemoryUsage().getUsed() / 1024 / 1024;
            long max = mem.getHeapMemoryUsage().getMax() / 1024 / 1024;
            memory.put("heapUsed", used + "MB");
            memory.put("heapMax", max + "MB");
            memory.put("usage", String.format("%.1f%%", (double) used / max * 100));
            payload.put("memory", memory);

            var threadInfo = new LinkedHashMap<String, Object>();
            threadInfo.put("active", threads.getThreadCount());
            threadInfo.put("peak", threads.getPeakThreadCount());
            threadInfo.put("daemon", threads.getDaemonThreadCount());
            payload.put("threads", threadInfo);

            payload.put("jvm", Map.of(
                    "version", System.getProperty("java.version"),
                    "vendor", System.getProperty("java.vendor"),
                    "os", System.getProperty("os.name") + " " + System.getProperty("os.arch")
            ));

            ctx.json(payload);
        });

        BaseHandler errors = counted(ctx -> ctx.json(recentErrors));
        BaseHandler gc = counted(ctx -> {
            System.gc();
            ctx.json(Map.of("result", "GC triggered"));
        });

        BaseHandler failure = counted(ctx -> {
            RuntimeException error = new RuntimeException("test error");
            errorCount.incrementAndGet();
            recentErrors.add(Map.of(
                    "time", Instant.now().toString(),
                    "path", ctx.url(),
                    "error", error.getMessage()
            ));
            if (recentErrors.size() > 50) recentErrors.remove(0);
            throw error;
        });

        var app = TestApplications.app(
                new AuraConfig().port(8080).cors(true),
                "monitor-demo",
                new RouteContribution("GET", "/health", health),
                new RouteContribution("GET", "/status", status),
                new RouteContribution("GET", "/errors", errors),
                new RouteContribution("POST", "/gc", gc),
                new RouteContribution("GET", "/fail", failure));

        app.start();
        System.out.println("Monitor API running on http://localhost:8080");
        System.out.println("  GET  /health  - 健康检查");
        System.out.println("  GET  /status  - 系统状态");
        System.out.println("  GET  /errors  - 最近错误");
        System.out.println("  POST /gc      - 触发 GC");
    }

    private static BaseHandler counted(BaseHandler handler) {
        return ctx -> {
            requestCount.incrementAndGet();
            handler.handle(ctx);
        };
    }

    static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m " + s % 60 + "s";
        return s / 3600 + "h " + (s % 3600) / 60 + "m";
    }
}

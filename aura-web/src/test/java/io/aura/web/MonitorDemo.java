package io.aura.web;

import io.aura.Aura;
import io.aura.RouteEntry;

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
        var app = Aura.create().port(8080).cors(true);

        // 健康检查
        app.get("/health", (RouteEntry.F0<HealthStatus>) () ->
                new HealthStatus("UP",
                        System.currentTimeMillis() - startTime,
                        Instant.ofEpochMilli(startTime).toString()));

        // 系统信息
        app.get("/status", (RouteEntry.F0<Map<String, Object>>) () -> {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

            var status = new LinkedHashMap<String, Object>();
            status.put("app", "Aura Monitor Demo");
            status.put("uptime", formatDuration(runtime.getUptime()));
            status.put("requests", requestCount.get());
            status.put("errors", errorCount.get());

            var memory = new LinkedHashMap<String, Object>();
            long used = mem.getHeapMemoryUsage().getUsed() / 1024 / 1024;
            long max = mem.getHeapMemoryUsage().getMax() / 1024 / 1024;
            memory.put("heapUsed", used + "MB");
            memory.put("heapMax", max + "MB");
            memory.put("usage", String.format("%.1f%%", (double) used / max * 100));
            status.put("memory", memory);

            var threadInfo = new LinkedHashMap<String, Object>();
            threadInfo.put("active", threads.getThreadCount());
            threadInfo.put("peak", threads.getPeakThreadCount());
            threadInfo.put("daemon", threads.getDaemonThreadCount());
            status.put("threads", threadInfo);

            status.put("jvm", Map.of(
                    "version", System.getProperty("java.version"),
                    "vendor", System.getProperty("java.vendor"),
                    "os", System.getProperty("os.name") + " " + System.getProperty("os.arch")
            ));

            return status;
        });

        // 最近错误
        app.get("/errors", (RouteEntry.F0<List<Map<String, Object>>>) () -> recentErrors);

        // 手动触发 GC
        app.post("/gc", (RouteEntry.F0<Map<String, String>>) () -> {
            System.gc();
            return Map.of("result", "GC triggered");
        });

        // 请求计数中间件
        app.routes((BaseRouter r) -> {
            r.before(ctx -> requestCount.incrementAndGet());
            r.exception(Exception.class, (e, ctx) -> {
                errorCount.incrementAndGet();
                recentErrors.add(Map.of(
                        "time", Instant.now().toString(),
                        "path", ctx.url(),
                        "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                ));
                if (recentErrors.size() > 50) recentErrors.remove(0);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            });

            // 故意报错的测试端点
            r.get("/fail", ctx -> { throw new RuntimeException("test error"); });
        });

        app.start();
        System.out.println("Monitor API running on http://localhost:8080");
        System.out.println("  GET  /health  - 健康检查");
        System.out.println("  GET  /status  - 系统状态");
        System.out.println("  GET  /errors  - 最近错误");
        System.out.println("  POST /gc      - 触发 GC");
    }

    static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m " + s % 60 + "s";
        return s / 3600 + "h " + (s % 3600) / 60 + "m";
    }
}

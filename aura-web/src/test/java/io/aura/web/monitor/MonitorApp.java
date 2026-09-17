package io.aura.web.monitor;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import io.aura.web.BaseHandler;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitorApp {

    public static void main(String[] args) {
        var serverService = new ServerService();
        var alertService = new AlertService();
        var collector = new MetricsCollector();

        // Register localhost as the first monitored server
        var localhost = serverService.create(new ServerService.CreateReq("localhost", "127.0.0.1"));

        // Scheduled metrics collection every 10 seconds
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-collector");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (var server : serverService.allServers()) {
                    Metrics metrics = collector.collect();
                    serverService.recordMetrics(server.id(), metrics);
                    alertService.evaluate(server.id(), server.name(), metrics);
                    updateServerStatus(serverService, server.id(), metrics);
                }
            } catch (Exception e) {
                System.err.println("Collection error: " + e.getMessage());
            }
        }, 0, 10, TimeUnit.SECONDS);

        AuraConfig config = new AuraConfig().port(8080).cors(true);
        var app = AuraApp.create(config).use(new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of("monitor", "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                context.service(serverService);
                context.service(alertService);
                context.route(new RouteContribution("GET", "/health", (BaseHandler) ctx -> ctx.json(Map.of(
                            "status", "UP",
                            "uptime", formatUptime(System.currentTimeMillis() - localhost.registeredAt())
                    ))));

                context.route(new RouteContribution("GET", "/metrics/current",
                        (BaseHandler) ctx -> ctx.json(collector.collect())));

                context.route(new RouteContribution("GET", "/dashboard", (BaseHandler) ctx -> ctx.json(Map.of(
                            "servers", serverService.summary(),
                            "alerts", alertService.triggeredCount(),
                            "system", collector.collect()
                    ))));
            }
        });

        app.start();

        System.out.println("=== Aura Server Monitor ===");
        System.out.println("http://localhost:8080");
        System.out.println();
        System.out.println("API Endpoints:");
        System.out.println("  GET  /health              - Health check");
        System.out.println("  GET  /metrics/current     - Current system metrics");
        System.out.println("  GET  /dashboard           - Dashboard overview");
        System.out.println("  ---");
        System.out.println("  GET  /api/server          - List servers");
        System.out.println("  POST /api/server          - Register server");
        System.out.println("  GET  /api/server/{id}     - Server detail + latest metrics");
        System.out.println("  DEL  /api/server/{id}     - Remove server");
        System.out.println("  GET  /api/server/summary  - Health summary");
        System.out.println("  GET  /api/server/history  - Metrics history");
        System.out.println("  ---");
        System.out.println("  GET  /api/alert           - List alert rules");
        System.out.println("  POST /api/alert           - Create alert rule");
        System.out.println("  DEL  /api/alert/{id}      - Delete alert rule");
        System.out.println("  GET  /api/alert/triggered - Triggered alerts");
        System.out.println("  GET  /api/alert/triggered/count - Alert counts");
        System.out.println("  ---");
        System.out.println("  GET  /__schema__          - API schema (for AI agents)");
        System.out.println();
        System.out.println("Routes are contributed by the explicit monitor module.");
    }

    private static void updateServerStatus(ServerService service, int serverId, Metrics metrics) {
        String status;
        if (metrics.cpu().usagePercent() > 95 || metrics.memory().usagePercent() > 95) {
            status = "critical";
        } else if (metrics.cpu().usagePercent() > 80 || metrics.memory().usagePercent() > 85) {
            status = "warning";
        } else {
            status = "healthy";
        }
        service.updateStatus(serverId, status);
    }

    private static String formatUptime(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m " + s % 60 + "s";
        return s / 3600 + "h " + (s % 3600) / 60 + "m";
    }
}

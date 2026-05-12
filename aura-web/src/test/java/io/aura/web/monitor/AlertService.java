package io.aura.web.monitor;

import io.aura.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Path("/api/alert")
@Desc("Alert rules and triggered alerts")
public class AlertService {

    private final AtomicInteger ruleIdGen = new AtomicInteger(0);
    private final AtomicInteger alertIdGen = new AtomicInteger(0);
    private final Map<Integer, AlertRule> rules = new LinkedHashMap<>();
    private final List<Alert> alerts = new CopyOnWriteArrayList<>();
    private static final int MAX_ALERTS = 200;

    public record AlertRule(int id, String metric, String operator, double threshold, String severity) {}
    public record CreateRuleReq(String metric, String operator, double threshold, String severity) {}
    public record Alert(int id, int ruleId, int serverId, String serverName,
                        String metric, double value, double threshold,
                        String severity, String triggeredAt) {}

    public AlertService() {
        rules.put(ruleIdGen.incrementAndGet(), new AlertRule(1, "cpu", ">", 80, "warning"));
        rules.put(ruleIdGen.incrementAndGet(), new AlertRule(2, "cpu", ">", 95, "critical"));
        rules.put(ruleIdGen.incrementAndGet(), new AlertRule(3, "memory", ">", 85, "warning"));
        rules.put(ruleIdGen.incrementAndGet(), new AlertRule(4, "memory", ">", 95, "critical"));
        rules.put(ruleIdGen.incrementAndGet(), new AlertRule(5, "disk", ">", 90, "warning"));
    }

    @Desc("Get alert rule by ID")
    public AlertRule get(@Desc("Rule ID") int id) {
        return rules.get(id);
    }

    @Desc("List all alert rules")
    public List<AlertRule> list() {
        return new ArrayList<>(rules.values());
    }

    @Desc("Create a new alert rule")
    public AlertRule create(CreateRuleReq req) {
        int id = ruleIdGen.incrementAndGet();
        AlertRule rule = new AlertRule(id, req.metric(), req.operator(), req.threshold(), req.severity());
        rules.put(id, rule);
        return rule;
    }

    @Desc("Delete an alert rule")
    public Map<String, Object> delete(@Desc("Rule ID") int id) {
        AlertRule removed = rules.remove(id);
        if (removed == null) return Map.of("error", "not found");
        return Map.of("deleted", id);
    }

    @Get("/triggered")
    @Desc("List triggered alerts, most recent first")
    public List<Alert> triggered() {
        List<Alert> result = new ArrayList<>(alerts);
        Collections.reverse(result);
        return result;
    }

    @Get("/triggered/count")
    @Desc("Count of triggered alerts by severity")
    public Map<String, Long> triggeredCount() {
        long warning = alerts.stream().filter(a -> "warning".equals(a.severity())).count();
        long critical = alerts.stream().filter(a -> "critical".equals(a.severity())).count();
        return Map.of("warning", warning, "critical", critical, "total", (long) alerts.size());
    }

    public void evaluate(int serverId, String serverName, Metrics metrics) {
        for (AlertRule rule : rules.values()) {
            double value = extractMetricValue(rule.metric(), metrics);
            if (value < 0) continue;
            if (matches(value, rule.operator(), rule.threshold())) {
                Alert alert = new Alert(
                        alertIdGen.incrementAndGet(), rule.id(), serverId, serverName,
                        rule.metric(), value, rule.threshold(),
                        rule.severity(), Instant.now().toString()
                );
                alerts.add(alert);
                while (alerts.size() > MAX_ALERTS) alerts.remove(0);
            }
        }
    }

    private double extractMetricValue(String metric, Metrics m) {
        return switch (metric) {
            case "cpu" -> m.cpu().usagePercent();
            case "memory" -> m.memory().usagePercent();
            case "disk" -> m.disks().stream()
                    .mapToDouble(Metrics.Disk::usagePercent).max().orElse(-1);
            case "load1" -> m.load().avg1();
            case "load5" -> m.load().avg5();
            default -> -1;
        };
    }

    private boolean matches(double value, String operator, double threshold) {
        return switch (operator) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            default -> false;
        };
    }
}

package io.aura.web.monitor;

import io.aura.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Path("/api/server")
@Desc("Server monitoring management")
public class ServerService {

    private final AtomicInteger idGen = new AtomicInteger(0);
    private final Map<Integer, Server> servers = new ConcurrentHashMap<>();
    private final Map<Integer, Deque<Metrics>> history = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 360; // 1 hour at 10s intervals

    public record Server(int id, String name, String host, String status, long registeredAt) {}
    public record CreateReq(String name, String host) {}
    public record ServerDetail(Server server, Metrics latest, int historySize) {}
    public record ServerSummary(int total, int healthy, int warning, int critical) {}

    @Desc("Get server by ID with latest metrics")
    public ServerDetail get(@Desc("Server ID") int id) {
        Server s = servers.get(id);
        if (s == null) return null;
        Deque<Metrics> h = history.getOrDefault(id, new ArrayDeque<>());
        Metrics latest = h.isEmpty() ? null : h.peekLast();
        return new ServerDetail(s, latest, h.size());
    }

    @Desc("List all monitored servers")
    public List<Server> list() {
        return new ArrayList<>(servers.values());
    }

    @Desc("Register a new server for monitoring")
    public Server create(CreateReq req) {
        int id = idGen.incrementAndGet();
        Server s = new Server(id, req.name(), req.host(), "healthy", System.currentTimeMillis());
        servers.put(id, s);
        history.put(id, new ArrayDeque<>());
        return s;
    }

    @Desc("Remove a server from monitoring")
    public Map<String, Object> delete(@Desc("Server ID") int id) {
        Server removed = servers.remove(id);
        history.remove(id);
        if (removed == null) return Map.of("error", "not found");
        return Map.of("deleted", id, "name", removed.name());
    }

    @Get("/summary")
    @Desc("Get summary of all servers health status")
    public ServerSummary summary() {
        int total = servers.size();
        int healthy = 0, warning = 0, critical = 0;
        for (Server s : servers.values()) {
            switch (s.status()) {
                case "healthy" -> healthy++;
                case "warning" -> warning++;
                case "critical" -> critical++;
            }
        }
        return new ServerSummary(total, healthy, warning, critical);
    }

    @Get("/history")
    @Desc("Get metrics history for a server")
    public List<Metrics> history(@Desc("Server ID") int id) {
        Deque<Metrics> h = history.get(id);
        if (h == null) return List.of();
        return new ArrayList<>(h);
    }

    public void recordMetrics(int serverId, Metrics metrics) {
        Deque<Metrics> h = history.get(serverId);
        if (h == null) return;
        h.addLast(metrics);
        while (h.size() > MAX_HISTORY) h.pollFirst();
    }

    public void updateStatus(int serverId, String status) {
        Server old = servers.get(serverId);
        if (old == null) return;
        servers.put(serverId, new Server(old.id(), old.name(), old.host(), status, old.registeredAt()));
    }

    public Collection<Server> allServers() {
        return servers.values();
    }

    public Metrics latestMetrics(int serverId) {
        Deque<Metrics> h = history.get(serverId);
        return (h == null || h.isEmpty()) ? null : h.peekLast();
    }
}

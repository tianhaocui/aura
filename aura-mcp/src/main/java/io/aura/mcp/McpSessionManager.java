package io.aura.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, identity-bound MCP session lifecycle for stateful transports. */
public final class McpSessionManager implements AutoCloseable {
    public static final int DEFAULT_MAX_SESSIONS = 1024;
    public static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_IDLE_TTL = Duration.ofDays(3650);

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final int maxSessions;
    private final int maxSessionsPerIdentity;
    private final Duration idleTtl;
    private final Clock clock;

    public McpSessionManager() {
        this(DEFAULT_MAX_SESSIONS, DEFAULT_IDLE_TTL);
    }

    public McpSessionManager(int maxSessions, Duration idleTtl) {
        this(maxSessions, idleTtl, Math.min(32, maxSessions), Clock.systemUTC());
    }

    McpSessionManager(int maxSessions, Duration idleTtl, Clock clock) {
        this(maxSessions, idleTtl, Math.min(32, maxSessions), clock);
    }

    McpSessionManager(int maxSessions, Duration idleTtl, int maxSessionsPerIdentity, Clock clock) {
        if (maxSessions < 1) throw new IllegalArgumentException("maxSessions must be positive");
        if (maxSessionsPerIdentity < 1) throw new IllegalArgumentException("maxSessionsPerIdentity must be positive");
        if (idleTtl == null || idleTtl.isZero() || idleTtl.isNegative()) {
            throw new IllegalArgumentException("idleTtl must be positive");
        }
        if (idleTtl.compareTo(MAX_IDLE_TTL) > 0) {
            throw new IllegalArgumentException("idleTtl is too large");
        }
        this.maxSessions = maxSessions;
        this.maxSessionsPerIdentity = Math.min(maxSessions, maxSessionsPerIdentity);
        this.idleTtl = idleTtl;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public synchronized Session create(String userId) {
        String identity = requireIdentity(userId);
        evictExpired();
        if (sessions.size() >= maxSessions) {
            throw new SessionLimitException("MCP session capacity is exhausted");
        }
        long identityCount = sessions.values().stream().filter(session -> session.userId.equals(identity)).count();
        if (identityCount >= maxSessionsPerIdentity) {
            throw new SessionLimitException("MCP session capacity for this identity is exhausted");
        }
        Instant now = clock.instant();
        while (true) {
            String id = UUID.randomUUID().toString();
            SessionState state = new SessionState(id, identity, now, now);
            if (sessions.putIfAbsent(id, state) == null) return state.view();
        }
    }

    /** Resolves and touches a session only when it belongs to the current identity. */
    public Optional<Session> access(String sessionId, String userId) {
        String identity = normalizeIdentity(userId);
        if (sessionId == null || sessionId.isBlank() || identity == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        while (true) {
            SessionState current = sessions.get(sessionId);
            if (current == null) return Optional.empty();
            if (expired(current, now)) {
                sessions.remove(sessionId, current);
                return Optional.empty();
            }
            if (!current.userId.equals(identity)) return Optional.empty();
            SessionState touched = current.touch(now);
            if (sessions.replace(sessionId, current, touched)) return Optional.of(touched.view());
        }
    }

    /** Checks identity and TTL without refreshing the session's last-access time. */
    public boolean active(String sessionId, String userId) {
        String identity = normalizeIdentity(userId);
        if (sessionId == null || sessionId.isBlank() || identity == null) return false;
        SessionState state = sessions.get(sessionId);
        if (state == null) return false;
        if (expired(state, clock.instant())) {
            sessions.remove(sessionId, state);
            return false;
        }
        return state.userId.equals(identity);
    }

    public boolean close(String sessionId, String userId) {
        String identity = normalizeIdentity(userId);
        if (sessionId == null || identity == null) return false;
        SessionState state = sessions.get(sessionId);
        return state != null && state.userId.equals(identity) && sessions.remove(sessionId, state);
    }

    public int size() {
        evictExpired();
        return sessions.size();
    }

    public int maxSessions() { return maxSessions; }
    public int maxSessionsPerIdentity() { return maxSessionsPerIdentity; }
    public Duration idleTtl() { return idleTtl; }

    public List<Session> sessions() {
        evictExpired();
        List<Session> result = new ArrayList<>();
        sessions.values().stream().map(SessionState::view)
                .sorted(Comparator.comparing(Session::createdAt))
                .forEach(result::add);
        return List.copyOf(result);
    }

    public int evictExpired() {
        Instant now = clock.instant();
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        return before - sessions.size();
    }

    @Override public void close() { sessions.clear(); }

    private boolean expired(SessionState state, Instant now) {
        try {
            return !state.lastAccessedAt.plus(idleTtl).isAfter(now);
        } catch (DateTimeException overflow) {
            return true;
        }
    }

    private static String requireIdentity(String userId) {
        String normalized = normalizeIdentity(userId);
        if (normalized == null) {
            throw new IllegalArgumentException("MCP session identity must not be blank");
        }
        return normalized;
    }

    private static String normalizeIdentity(String userId) {
        if (userId == null) return null;
        String normalized = userId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public record Session(String id, String userId, Instant createdAt, Instant lastAccessedAt) { }

    public static final class SessionLimitException extends IllegalStateException {
        SessionLimitException(String message) { super(message); }
    }

    private record SessionState(String id, String userId, Instant createdAt, Instant lastAccessedAt) {
        SessionState touch(Instant now) { return new SessionState(id, userId, createdAt, now); }
        Session view() { return new Session(id, userId, createdAt, lastAccessedAt); }
    }
}

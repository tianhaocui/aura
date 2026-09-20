package io.aura.mcp;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, identity-bound MCP session lifecycle for stateful transports. */
public final class McpSessionManager implements AutoCloseable {
    public static final int DEFAULT_MAX_SESSIONS = 1024;
    public static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_IDLE_TTL = Duration.ofDays(3650);

    private final Map<String, SessionState> sessions = new HashMap<>();
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
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Session create(String userId, String protocolVersion) {
        String identity = requireNonBlank(userId, "MCP session identity must not be blank");
        String version = requireNonBlank(protocolVersion, "MCP protocol version must not be blank");
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
            if (!sessions.containsKey(id)) {
                SessionState state = new SessionState(id, identity, version, now);
                sessions.put(id, state);
                return state.view();
            }
        }
    }

    /** Resolves a session without refreshing its idle deadline. */
    public synchronized Optional<Session> inspect(String sessionId, String userId) {
        SessionState state = resolve(sessionId, userId);
        if (state == null || expireIfNecessary(state, clock.instant()) || state.phase == SessionPhase.CLOSED) {
            return Optional.empty();
        }
        return Optional.of(state.view());
    }

    /** Resolves and touches a non-closed session only when it belongs to the current identity. */
    public synchronized Optional<Session> access(String sessionId, String userId) {
        SessionState state = resolve(sessionId, userId);
        Instant now = clock.instant();
        if (state == null || expireIfNecessary(state, now) || state.phase == SessionPhase.CLOSED) {
            return Optional.empty();
        }
        state.lastAccessedAt = now;
        return Optional.of(state.view());
    }

    /** Atomically promotes a negotiating session to ready. */
    public synchronized TransitionResult activate(String sessionId, String userId) {
        SessionState state = resolve(sessionId, userId);
        Instant now = clock.instant();
        if (state == null || expireIfNecessary(state, now) || state.phase == SessionPhase.CLOSED) {
            return TransitionResult.NOT_FOUND;
        }
        if (state.phase != SessionPhase.NEGOTIATING) {
            return TransitionResult.INVALID_PHASE;
        }
        state.phase = SessionPhase.READY;
        state.lastAccessedAt = now;
        return TransitionResult.SUCCESS;
    }

    /** Admits one request only while the session is ready and returns its idempotent release handle. */
    public synchronized Optional<RequestLease> admitReady(String sessionId, String userId) {
        SessionState state = resolve(sessionId, userId);
        Instant now = clock.instant();
        if (state == null || expireIfNecessary(state, now) || state.phase != SessionPhase.READY) {
            return Optional.empty();
        }
        state.lastAccessedAt = now;
        state.inFlight++;
        return Optional.of(new RequestLease(this, state, state.view()));
    }

    /** Checks identity, phase and TTL without touching the idle deadline. */
    public synchronized boolean active(String sessionId, String userId) {
        SessionState state = resolve(sessionId, userId);
        return state != null
                && !expireIfNecessary(state, clock.instant())
                && state.phase != SessionPhase.CLOSED;
    }

    public synchronized boolean close(String sessionId, String userId) {
        SessionState state = resolve(sessionId, userId);
        if (state == null || state.phase == SessionPhase.CLOSED) return false;
        state.phase = SessionPhase.CLOSED;
        if (state.inFlight == 0) sessions.remove(state.id);
        return true;
    }

    public synchronized int size() {
        evictExpired();
        return sessions.size();
    }

    public int maxSessions() { return maxSessions; }
    public int maxSessionsPerIdentity() { return maxSessionsPerIdentity; }
    public Duration idleTtl() { return idleTtl; }

    public synchronized List<Session> sessions() {
        evictExpired();
        List<Session> result = new ArrayList<>();
        sessions.values().stream()
                .filter(state -> state.phase != SessionPhase.CLOSED)
                .map(SessionState::view)
                .sorted(Comparator.comparing(Session::createdAt))
                .forEach(result::add);
        return List.copyOf(result);
    }

    public synchronized int evictExpired() {
        Instant now = clock.instant();
        int removed = 0;
        for (SessionState state : List.copyOf(sessions.values())) {
            if (expired(state, now)) {
                state.phase = SessionPhase.CLOSED;
                if (state.inFlight == 0 && sessions.remove(state.id) != null) removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized void close() {
        sessions.clear();
    }

    private synchronized void release(SessionState state) {
        if (sessions.get(state.id) != state || state.inFlight == 0) return;
        state.inFlight--;
        if (state.inFlight == 0 && state.phase == SessionPhase.CLOSED) {
            sessions.remove(state.id);
        }
    }

    private SessionState resolve(String sessionId, String userId) {
        String identity = normalize(userId);
        if (sessionId == null || sessionId.isBlank() || identity == null) return null;
        SessionState state = sessions.get(sessionId);
        return state != null && state.userId.equals(identity) ? state : null;
    }

    private boolean expireIfNecessary(SessionState state, Instant now) {
        if (!expired(state, now)) return false;
        state.phase = SessionPhase.CLOSED;
        if (state.inFlight == 0) sessions.remove(state.id);
        return true;
    }

    private boolean expired(SessionState state, Instant now) {
        try {
            return !state.lastAccessedAt.plus(idleTtl).isAfter(now);
        } catch (DateTimeException overflow) {
            return true;
        }
    }

    private static String requireNonBlank(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException(message);
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public enum SessionPhase { NEGOTIATING, READY, CLOSED }

    public enum TransitionResult { SUCCESS, NOT_FOUND, INVALID_PHASE }

    public record Session(
            String id,
            String userId,
            String protocolVersion,
            SessionPhase phase,
            Instant createdAt,
            Instant lastAccessedAt,
            int inFlight) { }

    public static final class RequestLease implements AutoCloseable {
        private final McpSessionManager manager;
        private final SessionState state;
        private final Session session;
        private final AtomicBoolean released = new AtomicBoolean();

        private RequestLease(McpSessionManager manager, SessionState state, Session session) {
            this.manager = manager;
            this.state = state;
            this.session = session;
        }

        public Session session() { return session; }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) manager.release(state);
        }
    }

    public static final class SessionLimitException extends IllegalStateException {
        SessionLimitException(String message) { super(message); }
    }

    private static final class SessionState {
        private final String id;
        private final String userId;
        private final String protocolVersion;
        private final Instant createdAt;
        private Instant lastAccessedAt;
        private SessionPhase phase = SessionPhase.NEGOTIATING;
        private int inFlight;

        private SessionState(String id, String userId, String protocolVersion, Instant now) {
            this.id = id;
            this.userId = userId;
            this.protocolVersion = protocolVersion;
            this.createdAt = now;
            this.lastAccessedAt = now;
        }

        private Session view() {
            return new Session(id, userId, protocolVersion, phase, createdAt, lastAccessedAt, inFlight);
        }
    }
}

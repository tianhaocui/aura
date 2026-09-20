package io.aura.mcp;

import io.aura.contract.CancellationToken;
import io.aura.web.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded per-session SSE channel for server-to-client MCP messages. */
final class McpSseHub implements AutoCloseable {
    static final int DEFAULT_QUEUE_EVENTS = 32;
    static final int DEFAULT_QUEUE_BYTES = 64 * 1024;
    static final int DEFAULT_HISTORY_EVENTS = 128;
    static final int DEFAULT_HISTORY_BYTES = 256 * 1024;

    private static final int CLOSE_QUEUE_CAPACITY = 64;
    private static final CancellationToken.Registration NOOP_REGISTRATION = () -> { };

    enum AttachResult { ATTACHED, ALREADY_CONNECTED, REPLAY_GAP, WRITE_FAILED }

    enum WriteResult { SENT, IDLE, CLOSED }

    record Attachment(AttachResult result, ConnectionHandle handle) { }

    static final class ConnectionHandle {
        private final String sessionId;
        private final Connection connection;

        private ConnectionHandle(String sessionId, Connection connection) {
            this.sessionId = sessionId;
            this.connection = connection;
        }
    }

    private final Object connectionStateLock = new Object();
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Connection> closingConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionHistory> histories = new ConcurrentHashMap<>();
    private final int maxEventBytes;
    private final int maxQueueEvents;
    private final int maxQueueBytes;
    private final int maxHistoryEvents;
    private final int maxHistoryBytes;
    private final AtomicLong overflowedConnections = new AtomicLong();
    private final AtomicLong replayGaps = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor closeExecutor;

    McpSseHub(int maxEventBytes) {
        this(maxEventBytes,
                DEFAULT_QUEUE_EVENTS, DEFAULT_QUEUE_BYTES,
                DEFAULT_HISTORY_EVENTS, DEFAULT_HISTORY_BYTES);
    }

    McpSseHub(int maxEventBytes, int maxQueueEvents, int maxQueueBytes,
              int maxHistoryEvents, int maxHistoryBytes) {
        this.maxEventBytes = positive(maxEventBytes, "maxEventBytes");
        this.maxQueueEvents = positive(maxQueueEvents, "maxQueueEvents");
        this.maxQueueBytes = positive(maxQueueBytes, "maxQueueBytes");
        this.maxHistoryEvents = positive(maxHistoryEvents, "maxHistoryEvents");
        this.maxHistoryBytes = positive(maxHistoryBytes, "maxHistoryBytes");
        ThreadFactory daemonFactory = task -> {
            Thread thread = new Thread(task, "aura-mcp-sse-close");
            thread.setDaemon(true);
            return thread;
        };
        closeExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CLOSE_QUEUE_CAPACITY), daemonFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    AttachResult attach(String sessionId, SseEmitter emitter, CancellationToken token, long lastEventId) {
        return attachWithHandle(sessionId, emitter, token, lastEventId).result();
    }

    Attachment attachWithHandle(
            String sessionId, SseEmitter emitter, CancellationToken token, long lastEventId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(token, "token");
        if (closed.get()) {
            emitter.close();
            return attachment(AttachResult.WRITE_FAILED, null);
        }
        synchronized (connectionStateLock) {
            if (connections.containsKey(sessionId) || closingConnections.containsKey(sessionId)) {
                return attachment(AttachResult.ALREADY_CONNECTED, null);
            }
        }

        SessionHistory history = histories.computeIfAbsent(sessionId,
                ignored -> new SessionHistory(maxHistoryEvents, maxHistoryBytes));
        if (closed.get()) {
            histories.remove(sessionId, history);
            emitter.close();
            return attachment(AttachResult.WRITE_FAILED, null);
        }
        Replay replay = history.replayAfter(lastEventId);
        if (replay.gap()) {
            replayGaps.incrementAndGet();
            try {
                emitter.send("replay-gap", replayGapPayload(lastEventId, replay));
                return attachment(AttachResult.REPLAY_GAP, null);
            } catch (Exception failure) {
                writeFailures.incrementAndGet();
                return attachment(AttachResult.WRITE_FAILED, null);
            } finally {
                emitter.close();
            }
        }

        Connection connection = new Connection(emitter, history,
                maxQueueEvents, maxQueueBytes);
        synchronized (connectionStateLock) {
            if (closed.get()) {
                histories.remove(sessionId, history);
                emitter.close();
                return attachment(AttachResult.WRITE_FAILED, null);
            }
            if (connections.containsKey(sessionId) || closingConnections.containsKey(sessionId)) {
                return attachment(AttachResult.ALREADY_CONNECTED, null);
            }
            connections.put(sessionId, connection);
        }
        connection.setRegistration(token.onCancel(() -> close(sessionId, connection)));
        if (closed.get()) {
            close(sessionId, connection);
            connection.finishReplay();
            return attachment(AttachResult.WRITE_FAILED, null);
        }

        try {
            for (Event event : replay.events()) {
                emitter.send("message", event.payload(), Long.toString(event.id()));
            }
            return connection.isClosed()
                    ? attachment(AttachResult.WRITE_FAILED, null)
                    : attachment(AttachResult.ATTACHED, new ConnectionHandle(sessionId, connection));
        } catch (Exception failure) {
            writeFailures.incrementAndGet();
            close(sessionId, connection);
            return attachment(AttachResult.WRITE_FAILED, null);
        } finally {
            connection.finishReplay();
        }
    }

    boolean connected(String sessionId) {
        return connections.containsKey(sessionId);
    }

    boolean publish(String sessionId, String method, Map<String, ?> params) {
        Connection connection = connections.get(sessionId);
        if (connection == null || closed.get()) return false;

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("method", method);
        if (params != null) envelope.put("params", params);
        final String payload;
        try {
            payload = BoundedJson.encode(envelope, maxEventBytes);
        } catch (BoundedJson.LimitExceededException tooLarge) {
            return false;
        }
        int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;

        boolean movedToClosing = false;
        connection.lock.lock();
        try {
            if (closed.get() || connection.closed || connections.get(sessionId) != connection) return false;
            Event event = connection.history.record(payload, payloadBytes);
            boolean overflow = connection.queue.size() >= connection.maxQueueEvents
                    || connection.queuedBytes + payloadBytes > connection.maxQueueBytes;
            if (!overflow) {
                connection.queue.addLast(event);
                connection.queuedBytes += payloadBytes;
                connection.notEmpty.signal();
                return true;
            }
            connection.markClosedLocked();
            synchronized (connectionStateLock) {
                if (connections.remove(sessionId, connection)) {
                    closingConnections.put(sessionId, connection);
                    movedToClosing = true;
                }
            }
        } finally {
            connection.lock.unlock();
        }

        connection.detachCancellation();
        overflowedConnections.incrementAndGet();
        if (movedToClosing) closeAsynchronously(sessionId, connection);
        return false;
    }

    WriteResult writeNext(String sessionId, Duration wait) throws InterruptedException {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(wait, "wait");
        if (wait.isNegative()) throw new IllegalArgumentException("wait must not be negative");
        Connection connection = connections.get(sessionId);
        if (connection == null) {
            connection = closingConnections.get(sessionId);
            if (connection == null) return WriteResult.CLOSED;
        }
        return writeNext(new ConnectionHandle(sessionId, connection), wait);
    }

    WriteResult writeNext(ConnectionHandle handle, Duration wait) throws InterruptedException {
        Objects.requireNonNull(handle, "handle");
        String sessionId = handle.sessionId;
        Connection connection = handle.connection;
        boolean active = connections.get(sessionId) == connection;
        boolean closing = closingConnections.get(sessionId) == connection;
        if (!active && !closing) {
            connection.closeEmitter();
            return WriteResult.CLOSED;
        }
        if (closing) {
            connection.closeEmitter();
            closingConnections.remove(sessionId, connection);
            return WriteResult.CLOSED;
        }

        connection.writerLock.lockInterruptibly();
        try {
            connection.awaitReplay();
            Event event = connection.poll(wait);
            if (event == null) {
                if (!connection.isClosed()) return WriteResult.IDLE;
                connection.closeEmitter();
                closingConnections.remove(sessionId, connection);
                return WriteResult.CLOSED;
            }
            try {
                connection.emitter.send("message", event.payload(), Long.toString(event.id()));
                return WriteResult.SENT;
            } catch (Exception failure) {
                writeFailures.incrementAndGet();
                close(sessionId, connection);
                connection.closeEmitter();
                closingConnections.remove(sessionId, connection);
                return WriteResult.CLOSED;
            }
        } finally {
            connection.writerLock.unlock();
        }
    }

    void close(String sessionId) {
        Connection active;
        Connection closing;
        synchronized (connectionStateLock) {
            active = connections.remove(sessionId);
            closing = closingConnections.remove(sessionId);
        }
        if (active != null) active.closeDirectly();
        if (closing != null && closing != active) closing.closeDirectly();
    }

    void close(ConnectionHandle handle) {
        if (handle == null) return;
        closeExpected(handle.sessionId, handle.connection, false);
    }

    void remove(ConnectionHandle handle) {
        if (handle == null) return;
        histories.remove(handle.sessionId);
        closeExpected(handle.sessionId, handle.connection, true);
    }

    void remove(String sessionId) {
        sessionRemoved(sessionId);
    }

    void sessionRemoved(String sessionId) {
        histories.remove(sessionId);
        Connection connection;
        synchronized (connectionStateLock) {
            connection = connections.remove(sessionId);
            if (connection != null) closingConnections.put(sessionId, connection);
            else connection = closingConnections.get(sessionId);
        }
        if (connection == null) return;
        connection.markClosed();
        connection.detachCancellation();
        closeAsynchronously(sessionId, connection);
    }

    McpSseMetrics metrics() {
        long queuedEvents = 0L;
        long queuedBytes = 0L;
        for (Connection connection : connections.values()) {
            QueueSize size = connection.queueSize();
            queuedEvents += size.events();
            queuedBytes += size.bytes();
        }
        return new McpSseMetrics(
                connections.size(), closingConnections.size(), histories.size(), queuedEvents, queuedBytes,
                overflowedConnections.get(), replayGaps.get(), writeFailures.get());
    }

    int size() {
        return connections.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List<Connection> toClose;
        synchronized (connectionStateLock) {
            toClose = new ArrayList<>(connections.values());
            for (Connection connection : closingConnections.values()) {
                if (!toClose.contains(connection)) toClose.add(connection);
            }
            connections.clear();
            closingConnections.clear();
        }
        histories.clear();
        closeExecutor.shutdownNow();
        toClose.forEach(Connection::closeDirectly);
    }

    private void close(String sessionId, Connection expected) {
        closeExpected(sessionId, expected, false);
    }

    private void closeExpected(String sessionId, Connection expected, boolean includeClosing) {
        boolean removed;
        synchronized (connectionStateLock) {
            removed = connections.remove(sessionId, expected);
            if (includeClosing) removed |= closingConnections.remove(sessionId, expected);
        }
        if (removed || connections.get(sessionId) != expected
                && closingConnections.get(sessionId) != expected) expected.closeDirectly();
    }

    private void closeAsynchronously(String sessionId, Connection connection) {
        if (!connection.closeScheduled.compareAndSet(false, true)) return;
        try {
            closeExecutor.execute(() -> {
                try {
                    connection.closeEmitter();
                } finally {
                    connection.closeScheduled.set(false);
                    closingConnections.remove(sessionId, connection);
                }
            });
        } catch (RejectedExecutionException ignored) {
            connection.closeScheduled.set(false);
            // Keep it addressable until a writer, session removal, or hub shutdown closes it.
        }
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Attachment attachment(AttachResult result, ConnectionHandle handle) {
        return new Attachment(result, handle);
    }

    private static String replayGapPayload(long lastEventId, Replay replay) {
        return "{\"lastEventId\":" + lastEventId
                + ",\"oldestAvailableEventId\":" + replay.oldestAvailableId()
                + ",\"nextEventId\":" + replay.nextEventId() + "}";
    }

    private static long waitNanos(Duration wait) {
        try {
            return wait.toNanos();
        } catch (ArithmeticException tooLarge) {
            return Long.MAX_VALUE;
        }
    }

    private static final class Connection {
        private final SseEmitter emitter;
        private final SessionHistory history;
        private final int maxQueueEvents;
        private final int maxQueueBytes;
        private final ReentrantLock lock = new ReentrantLock();
        private final ReentrantLock writerLock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private final Deque<Event> queue = new ArrayDeque<>();
        private final AtomicBoolean emitterClosed = new AtomicBoolean();
        private final AtomicBoolean closeScheduled = new AtomicBoolean();
        private final CountDownLatch replayFinished = new CountDownLatch(1);
        private volatile CancellationToken.Registration registration = NOOP_REGISTRATION;
        private boolean closed;
        private long queuedBytes;

        private Connection(SseEmitter emitter, SessionHistory history,
                           int maxQueueEvents, int maxQueueBytes) {
            this.emitter = emitter;
            this.history = history;
            this.maxQueueEvents = maxQueueEvents;
            this.maxQueueBytes = maxQueueBytes;
        }

        private void setRegistration(CancellationToken.Registration registration) {
            this.registration = registration;
            if (isClosed()) registration.close();
        }

        private Event poll(Duration wait) throws InterruptedException {
            long remaining = waitNanos(wait);
            lock.lockInterruptibly();
            try {
                while (queue.isEmpty() && !closed && remaining > 0L) {
                    remaining = notEmpty.awaitNanos(remaining);
                }
                if (closed || queue.isEmpty()) return null;
                Event event = queue.removeFirst();
                queuedBytes -= event.bytes();
                return event;
            } finally {
                lock.unlock();
            }
        }

        private void awaitReplay() throws InterruptedException {
            replayFinished.await();
        }

        private void finishReplay() {
            replayFinished.countDown();
        }

        private QueueSize queueSize() {
            lock.lock();
            try {
                return new QueueSize(queue.size(), queuedBytes);
            } finally {
                lock.unlock();
            }
        }

        private boolean isClosed() {
            lock.lock();
            try {
                return closed;
            } finally {
                lock.unlock();
            }
        }

        private void closeDirectly() {
            markClosed();
            detachCancellation();
            closeEmitter();
        }

        private void markClosed() {
            lock.lock();
            try {
                markClosedLocked();
            } finally {
                lock.unlock();
            }
        }

        private void markClosedLocked() {
            if (closed) return;
            closed = true;
            queue.clear();
            queuedBytes = 0L;
            notEmpty.signalAll();
        }

        private void detachCancellation() {
            registration.close();
        }

        private void closeEmitter() {
            if (emitterClosed.compareAndSet(false, true)) emitter.close();
        }
    }

    private record Event(long id, String payload, int bytes) { }

    private record QueueSize(long events, long bytes) { }

    private record Replay(boolean gap, List<Event> events, long oldestAvailableId, long nextEventId) { }

    private static final class SessionHistory {
        private final Deque<Event> events = new ArrayDeque<>();
        private final int maxEvents;
        private final int maxBytes;
        private long nextEventId = 1L;
        private long totalBytes;

        private SessionHistory(int maxEvents, int maxBytes) {
            this.maxEvents = maxEvents;
            this.maxBytes = maxBytes;
        }

        private synchronized Event record(String payload, int bytes) {
            Event event = new Event(nextEventId++, payload, bytes);
            events.addLast(event);
            totalBytes += bytes;
            while (events.size() > maxEvents || totalBytes > maxBytes) {
                totalBytes -= events.removeFirst().bytes();
            }
            return event;
        }

        private synchronized Replay replayAfter(long lastEventId) {
            long oldestAvailableId = events.isEmpty() ? nextEventId : events.getFirst().id();
            boolean hasLastEventId = lastEventId >= 0L;
            boolean tooOld = hasLastEventId && lastEventId < oldestAvailableId - 1L;
            boolean ahead = hasLastEventId && lastEventId >= nextEventId;
            if (tooOld || ahead) {
                return new Replay(true, List.of(), oldestAvailableId, nextEventId);
            }
            List<Event> replay = events.stream()
                    .filter(event -> event.id() > lastEventId)
                    .toList();
            return new Replay(false, replay, oldestAvailableId, nextEventId);
        }
    }
}

package io.aura.web;

import io.undertow.websockets.core.WebSocketCallback;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.xnio.IoUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Global bounded lifecycle owner for all WebSocket channels in one web runtime. */
final class WebSocketConnectionRegistry {
    static final int LIMIT_CLOSE_CODE = 1013;
    static final int GOING_AWAY_CLOSE_CODE = 1001;

    private final Semaphore permits;
    private final long idleTimeoutNanos;
    private final ConcurrentHashMap<WebSocketChannel, ConnectionState> connections =
            new ConcurrentHashMap<>();
    private final Object lifecycleMonitor = new Object();
    private volatile ScheduledThreadPoolExecutor idleScheduler;
    private volatile boolean stopping;

    WebSocketConnectionRegistry(int maxConnections, int idleTimeoutSeconds) {
        if (maxConnections < 1) throw new IllegalArgumentException("maxConnections must be positive");
        if (idleTimeoutSeconds < 1) throw new IllegalArgumentException("idleTimeoutSeconds must be positive");
        this.permits = new Semaphore(maxConnections);
        this.idleTimeoutNanos = TimeUnit.SECONDS.toNanos(idleTimeoutSeconds);
    }

    boolean register(WebSocketChannel channel) {
        if (!permits.tryAcquire()) return false;
        synchronized (lifecycleMonitor) {
            if (stopping) {
                permits.release();
                return false;
            }
            connections.put(channel, new ConnectionState());
            channel.addCloseTask(this::unregister);
            if (!channel.isOpen()) {
                unregister(channel);
                return false;
            }
            try {
                ensureIdleScheduler();
            } catch (RuntimeException | Error failure) {
                unregister(channel);
                IoUtils.safeClose(channel);
                throw failure;
            }
        }
        return true;
    }

    void reject(WebSocketChannel channel) {
        sendClose(channel, null, LIMIT_CLOSE_CODE, "WebSocket connection limit reached");
    }

    void touch(WebSocketChannel channel) {
        ConnectionState state = connections.get(channel);
        if (state != null && !state.closing.get()) state.lastActivityNanos.set(System.nanoTime());
    }

    void shutdown(long timeoutMillis) {
        synchronized (lifecycleMonitor) {
            stopping = true;
            ScheduledThreadPoolExecutor scheduler = idleScheduler;
            if (scheduler != null) scheduler.shutdownNow();
        }
        connections.forEach((channel, state) ->
                sendClose(channel, state, GOING_AWAY_CLOSE_CODE, "Server shutting down"));

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        synchronized (lifecycleMonitor) {
            while (!connections.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        connections.keySet().forEach(IoUtils::safeClose);
    }

    private void ensureIdleScheduler() {
        if (idleScheduler != null) return;
        synchronized (lifecycleMonitor) {
            if (idleScheduler != null || stopping) return;
            ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "aura-websocket-idle");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.setRemoveOnCancelPolicy(true);
            long intervalNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(100),
                    Math.min(TimeUnit.SECONDS.toNanos(1), idleTimeoutNanos / 2));
            scheduler.scheduleAtFixedRate(this::closeIdleConnections,
                    intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
            idleScheduler = scheduler;
        }
    }

    private void closeIdleConnections() {
        long now = System.nanoTime();
        connections.forEach((channel, state) -> {
            if (now - state.lastActivityNanos.get() >= idleTimeoutNanos) {
                sendClose(channel, state, GOING_AWAY_CLOSE_CODE, "WebSocket idle timeout");
            }
        });
    }

    private void unregister(WebSocketChannel channel) {
        if (connections.remove(channel) != null) {
            permits.release();
            synchronized (lifecycleMonitor) {
                lifecycleMonitor.notifyAll();
            }
        }
    }

    private void sendClose(WebSocketChannel channel, ConnectionState state,
                           int code, String reason) {
        if (state != null && !state.closing.compareAndSet(false, true)) return;
        try {
            WebSockets.sendClose(code, reason, channel, new WebSocketCallback<Void>() {
                @Override public void complete(WebSocketChannel completed, Void context) {
                    IoUtils.safeClose(completed);
                }

                @Override public void onError(WebSocketChannel failed, Void context, Throwable error) {
                    IoUtils.safeClose(failed);
                }
            });
        } catch (RuntimeException failure) {
            IoUtils.safeClose(channel);
        }
    }

    private static final class ConnectionState {
        private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
        private final AtomicBoolean closing = new AtomicBoolean();
    }
}

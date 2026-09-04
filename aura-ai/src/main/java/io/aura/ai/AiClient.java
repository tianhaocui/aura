package io.aura.ai;

import io.aura.CancellationToken;
import java.time.Duration;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Synchronous and streaming facade around an {@link AiProvider}. */
public final class AiClient implements AutoCloseable {
    static final int DEFAULT_QUEUE_CAPACITY = 128;
    private static final int DEFAULT_WORKERS = Math.max(2,
            Math.min(Runtime.getRuntime().availableProcessors(), 8));
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final AiProvider provider;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public AiClient(AiProvider provider) {
        this(provider, createDefaultExecutor(), true);
    }

    public AiClient(AiProvider provider, ExecutorService executor) {
        this(provider, executor, false);
    }

    private AiClient(AiProvider provider, ExecutorService executor, boolean ownsExecutor) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    public AiProvider provider() { return provider; }

    public AiResponse chat(AiRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        request.cancellationToken().throwIfCancelled();
        Future<AiResponse> future = submit(() -> {
            request.cancellationToken().throwIfCancelled();
            return provider.chat(request);
        });
        try {
            AiResponse response = await(future, request.timeout(), request.cancellationToken());
            if (request.budget().maxOutputCharacters() > 0
                    && response.content().length() > request.budget().maxOutputCharacters()) {
                throw new IllegalStateException("AI response exceeds configured output budget");
            }
            return response;
        } catch (TimeoutException | CancellationException | InterruptedException error) {
            request.cancellationToken().cancel();
            future.cancel(true);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error;
        } catch (ExecutionException error) {
            future.cancel(true);
            return throwCause(error);
        }
    }

    public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        request.cancellationToken().throwIfCancelled();
        AtomicBoolean errorDelivered = new AtomicBoolean();
        Future<Void> future = submit(() -> {
            handler.onStart();
            request.cancellationToken().throwIfCancelled();
            provider.stream(request, new AiStreamHandler() {
                @Override public void onToken(String token) {
                    request.cancellationToken().throwIfCancelled();
                    handler.onToken(token);
                }
                @Override public void onComplete(AiResponse response) {
                    request.cancellationToken().throwIfCancelled();
                    handler.onComplete(response);
                }
                @Override public void onError(Throwable error) {
                    if (errorDelivered.compareAndSet(false, true)) handler.onError(error);
                }
            });
            return null;
        });
        try {
            await(future, request.timeout(), request.cancellationToken());
        } catch (TimeoutException | CancellationException | InterruptedException error) {
            request.cancellationToken().cancel();
            future.cancel(true);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (errorDelivered.compareAndSet(false, true)) handler.onError(error);
            throw error;
        } catch (ExecutionException error) {
            future.cancel(true);
            Throwable cause = unwrap(error);
            if (errorDelivered.compareAndSet(false, true)) handler.onError(cause);
            throwCause(error);
        }
    }

    private static <T> T throwCause(ExecutionException error) throws Exception {
        Throwable cause = unwrap(error);
        if (cause instanceof Exception exception) throw exception;
        if (cause instanceof Error fatal) throw fatal;
        throw new RuntimeException(cause);
    }

    private <T> Future<T> submit(Callable<T> task) {
        try {
            return executor.submit(task);
        } catch (RejectedExecutionException error) {
            throw new RejectedExecutionException(
                    "AI request rejected because the executor is saturated or shut down", error);
        }
    }

    static ThreadPoolExecutor createDefaultExecutor() {
        return createBoundedExecutor(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    static ThreadPoolExecutor createBoundedExecutor(int workers, int queueCapacity) {
        if (workers < 1) throw new IllegalArgumentException("workers must be positive");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        return new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                task -> {
                    Thread thread = new Thread(task, "aura-ai-" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static Throwable unwrap(ExecutionException error) {
        Throwable cause = error.getCause();
        return cause == null ? error : cause;
    }

    private static <T> T await(Future<T> future, Duration timeout, CancellationToken token)
            throws Exception {
        if (timeout == null || timeout.isZero()) {
            while (true) {
                token.throwIfCancelled();
                try { return future.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { }
            }
        }
        long nanos = timeout.toNanos();
        long deadline = System.nanoTime() + nanos;
        while (true) {
            token.throwIfCancelled();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("AI request timed out after " + timeout);
            try { return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
            catch (TimeoutException ignored) { }
        }
    }

    public static AiClient load() {
        return ServiceLoader.load(AiProvider.class).findFirst()
                .map(AiClient::new)
                .orElseThrow(() -> new IllegalStateException("No AiProvider implementation found"));
    }

    public static AiClient load(String name) {
        Objects.requireNonNull(name, "name");
        return ServiceLoader.load(AiProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> name.equals(provider.name()))
                .findFirst()
                .map(AiClient::new)
                .orElseThrow(() -> new IllegalArgumentException("No AiProvider named " + name));
    }

    @Override public void close() {
        if (ownsExecutor) executor.shutdownNow();
    }
}

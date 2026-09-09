package io.aura.ai;

import io.aura.contract.CancellationToken;
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
import java.util.concurrent.atomic.AtomicReference;

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
        long deadline = request.timeout() == null || request.timeout().isZero()
                ? Long.MAX_VALUE : System.nanoTime() + request.timeout().toNanos();
        for (int attempt = 1; ; attempt++) {
            Duration remaining = remaining(request.timeout(), deadline);
            Future<AiResponse> future = submit(() -> {
                request.cancellationToken().throwIfCancelled();
                return provider.chat(request);
            });
            try {
                AiResponse response = await(future, remaining, request.cancellationToken());
                enforceBudget(request, response);
                return response;
            } catch (TimeoutException | CancellationException | InterruptedException error) {
                request.cancellationToken().cancel();
                future.cancel(true);
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw error;
            } catch (ExecutionException error) {
                future.cancel(true);
                Throwable cause = unwrap(error);
                if (cause instanceof AiBudgetExceededException budgetFailure) {
                    throw budgetFailure;
                }
                if (attempt >= request.retryPolicy().maxAttempts()
                        || !request.retryPolicy().shouldRetry(cause)) {
                    if (cause instanceof Exception exception) throw exception;
                    if (cause instanceof Error fatal) throw fatal;
                    throw new RuntimeException(cause);
                }
                sleepBackoff(request, remaining, deadline);
            }
        }
    }

    public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        request.cancellationToken().throwIfCancelled();
        AtomicBoolean errorDelivered = new AtomicBoolean();
        AtomicReference<AiBudgetExceededException> budgetFailure = new AtomicReference<>();
        StringBuilder streamedContent = new StringBuilder();
        Future<Void> future = submit(() -> {
            handler.onStart();
            request.cancellationToken().throwIfCancelled();
            provider.stream(request, new AiStreamHandler() {
                @Override public void onToken(String token) {
                    request.cancellationToken().throwIfCancelled();
                    if (token != null) {
                        streamedContent.append(token);
                        try {
                            enforceOutputCharacters(request, streamedContent.length());
                        } catch (AiBudgetExceededException exceeded) {
                            budgetFailure.compareAndSet(null, exceeded);
                            request.cancellationToken().cancel();
                            throw exceeded;
                        }
                    }
                    handler.onToken(token);
                }
                @Override public void onComplete(AiResponse response) {
                    request.cancellationToken().throwIfCancelled();
                    try {
                        enforceBudget(request, response);
                    } catch (AiBudgetExceededException exceeded) {
                        budgetFailure.compareAndSet(null, exceeded);
                        request.cancellationToken().cancel();
                        throw exceeded;
                    }
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
            if (budgetFailure.get() != null) {
                AiBudgetExceededException exceeded = budgetFailure.get();
                if (errorDelivered.compareAndSet(false, true)) handler.onError(exceeded);
                throw exceeded;
            }
        } catch (TimeoutException | CancellationException | InterruptedException error) {
            request.cancellationToken().cancel();
            future.cancel(true);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable terminal = budgetFailure.get() != null ? budgetFailure.get() : error;
            if (errorDelivered.compareAndSet(false, true)) handler.onError(terminal);
            if (terminal instanceof Exception exception) throw exception;
            throw error;
        } catch (ExecutionException error) {
            request.cancellationToken().cancel();
            future.cancel(true);
            Throwable cause = unwrap(error);
            Throwable terminal = budgetFailure.get() != null ? budgetFailure.get() : cause;
            if (errorDelivered.compareAndSet(false, true)) handler.onError(terminal);
            if (terminal instanceof Exception exception) throw exception;
            if (terminal instanceof Error fatal) throw fatal;
            throw new RuntimeException(terminal);
        }
    }

    private static void enforceBudget(AiRequest request, AiResponse response) {
        enforceOutputCharacters(request, response.content().length());
        AiBudget budget = request.budget();
        if (!budget.requiresUsage()) return;
        AiUsage usage = AiUsage.from(response.usage());
        if (budget.maxInputTokens() > 0 && usage.inputTokens() < 0) {
            throw AiBudgetExceededException.missing("inputTokens", budget.maxInputTokens());
        }
        if (budget.maxOutputTokens() > 0 && usage.outputTokens() < 0) {
            throw AiBudgetExceededException.missing("outputTokens", budget.maxOutputTokens());
        }
        if (budget.maxTotalTokens() > 0 && usage.totalTokens() < 0) {
            throw AiBudgetExceededException.missing("totalTokens", budget.maxTotalTokens());
        }
        if (budget.maxCostMicros() > 0 && usage.costMicros() < 0) {
            throw AiBudgetExceededException.missing("costMicros", budget.maxCostMicros());
        }
        if (budget.maxInputTokens() > 0 && usage.inputTokens() > budget.maxInputTokens()) {
            throw AiBudgetExceededException.exceeded("inputTokens", budget.maxInputTokens(), usage.inputTokens());
        }
        if (budget.maxOutputTokens() > 0 && usage.outputTokens() > budget.maxOutputTokens()) {
            throw AiBudgetExceededException.exceeded("outputTokens", budget.maxOutputTokens(), usage.outputTokens());
        }
        if (budget.maxTotalTokens() > 0 && usage.totalTokens() > budget.maxTotalTokens()) {
            throw AiBudgetExceededException.exceeded("totalTokens", budget.maxTotalTokens(), usage.totalTokens());
        }
        if (budget.maxCostMicros() > 0 && usage.costMicros() > budget.maxCostMicros()) {
            throw AiBudgetExceededException.exceeded("costMicros", budget.maxCostMicros(), usage.costMicros());
        }
    }

    private static void enforceOutputCharacters(AiRequest request, long actual) {
        long limit = request.budget().maxOutputCharacters();
        if (limit > 0 && actual > limit) {
            throw AiBudgetExceededException.exceeded("outputCharacters", limit, actual);
        }
    }

    private static <T> T throwCause(ExecutionException error) throws Exception {
        Throwable cause = unwrap(error);
        if (cause instanceof Exception exception) throw exception;
        if (cause instanceof Error fatal) throw fatal;
        throw new RuntimeException(cause);
    }

    private static Duration remaining(Duration configured, long deadline) throws TimeoutException {
        if (configured == null || configured.isZero()) return Duration.ZERO;
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException("AI request timed out after " + configured);
        return Duration.ofNanos(nanos);
    }

    private static void sleepBackoff(AiRequest request, Duration remaining, long deadline) throws Exception {
        Duration backoff = request.retryPolicy().backoff();
        if (backoff.isZero()) return;
        if (remaining != null && !remaining.isZero() && backoff.compareTo(remaining) >= 0) {
            throw new TimeoutException("AI request timed out during retry backoff");
        }
        long millis = backoff.toMillis();
        int nanos = backoff.minusMillis(millis).getNano();
        try {
            Thread.sleep(millis, nanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        request.cancellationToken().throwIfCancelled();
        if (deadline != Long.MAX_VALUE && deadline <= System.nanoTime()) {
            throw new TimeoutException("AI request timed out during retry backoff");
        }
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

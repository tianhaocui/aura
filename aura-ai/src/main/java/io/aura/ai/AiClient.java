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
import java.util.concurrent.atomic.AtomicLong;

/** Synchronous and streaming facade around an {@link AiProvider}. */
public final class AiClient implements AutoCloseable {
    static final int DEFAULT_QUEUE_CAPACITY = 128;
    private static final int DEFAULT_WORKERS = Math.max(2,
            Math.min(Runtime.getRuntime().availableProcessors(), 8));
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final AiProvider provider;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong costMicros = new AtomicLong();
    private final AtomicLong unknownUsage = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();
    private final AtomicLong timedOutRequests = new AtomicLong();

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

    public io.aura.contract.ExecutorMetrics executorMetrics() {
        if (executor instanceof ThreadPoolExecutor pool) {
            return new io.aura.contract.ExecutorMetrics("ai", true,
                    pool.getMaximumPoolSize(), pool.getActiveCount(), pool.getQueue().size(),
                    pool.getQueue().size() + pool.getQueue().remainingCapacity(),
                    pool.getCompletedTaskCount(), rejectedRequests.get(), timedOutRequests.get());
        }
        return io.aura.contract.ExecutorMetrics.opaque(
                "ai-external", rejectedRequests.get(), timedOutRequests.get());
    }

    /** Returns a snapshot of cumulative usage observed by this client. */
    public AiUsageTotals usageTotals() {
        return new AiUsageTotals(requests.get(), inputTokens.get(), outputTokens.get(),
                totalTokens.get(), costMicros.get(), unknownUsage.get());
    }

    /** Resets cumulative usage counters for a new accounting window. */
    public void resetUsage() {
        requests.set(0);
        inputTokens.set(0);
        outputTokens.set(0);
        totalTokens.set(0);
        costMicros.set(0);
        unknownUsage.set(0);
    }

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
                recordUsage(request, response);
                enforceBudget(request, response);
                validateStructuredOutput(request, response);
                return response;
            } catch (TimeoutException | CancellationException | InterruptedException error) {
                if (error instanceof TimeoutException) timedOutRequests.incrementAndGet();
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
        AtomicReference<AiStructuredOutputException> structuredFailure = new AtomicReference<>();
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
                    recordUsage(request, response);
                    try {
                        enforceBudget(request, response);
                    } catch (AiBudgetExceededException exceeded) {
                        budgetFailure.compareAndSet(null, exceeded);
                        request.cancellationToken().cancel();
                        throw exceeded;
                    }
                    try {
                        validateStreamingStructuredOutput(request, response, streamedContent);
                    } catch (AiStructuredOutputException invalid) {
                        structuredFailure.compareAndSet(null, invalid);
                        request.cancellationToken().cancel();
                        throw invalid;
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
            if (structuredFailure.get() != null) {
                AiStructuredOutputException invalid = structuredFailure.get();
                if (errorDelivered.compareAndSet(false, true)) handler.onError(invalid);
                throw invalid;
            }
        } catch (TimeoutException | CancellationException | InterruptedException error) {
            if (error instanceof TimeoutException) timedOutRequests.incrementAndGet();
            request.cancellationToken().cancel();
            future.cancel(true);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable terminal = terminalFailure(budgetFailure.get(), structuredFailure.get(), error);
            if (errorDelivered.compareAndSet(false, true)) handler.onError(terminal);
            if (terminal instanceof Exception exception) throw exception;
            throw error;
        } catch (ExecutionException error) {
            request.cancellationToken().cancel();
            future.cancel(true);
            Throwable cause = unwrap(error);
            Throwable terminal = terminalFailure(budgetFailure.get(), structuredFailure.get(), cause);
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
        AiUsage usage = usageFor(request, response);
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

    private static void validateStructuredOutput(AiRequest request, AiResponse response) {
        if (request.structuredOutput() != null) {
            request.structuredOutput().validate(response.content());
        }
    }

    private static void validateStreamingStructuredOutput(
            AiRequest request, AiResponse response, StringBuilder streamedContent) {
        if (request.structuredOutput() == null) return;
        String delivered = streamedContent.toString();
        if (!delivered.equals(response.content())) {
            throw new AiStructuredOutputException(
                    "AI streaming content differs from the completed response");
        }
        request.structuredOutput().validate(delivered);
    }

    private static Throwable terminalFailure(
            AiBudgetExceededException budgetFailure,
            AiStructuredOutputException structuredFailure,
            Throwable fallback) {
        if (budgetFailure != null) return budgetFailure;
        return structuredFailure != null ? structuredFailure : fallback;
    }

    private void recordUsage(AiRequest request, AiResponse response) {
        requests.incrementAndGet();
        final AiUsage usage;
        try {
            usage = usageFor(request, response);
        } catch (RuntimeException malformedUsage) {
            // Observability must not make an otherwise valid response fail.
            // Budget enforcement still parses strictly immediately afterwards
            // whenever the request requires provider usage.
            unknownUsage.incrementAndGet();
            return;
        }
        addKnown(inputTokens, usage.inputTokens());
        addKnown(outputTokens, usage.outputTokens());
        addKnown(totalTokens, usage.totalTokens());
        addKnown(costMicros, usage.costMicros());
        if (!usage.hasTokenUsage() && !usage.hasCost()) unknownUsage.incrementAndGet();
    }

    private static AiUsage usageFor(AiRequest request, AiResponse response) {
        AiUsage reported = AiUsage.from(response == null ? null : response.usage());
        if (reported.hasCost() || request == null || request.pricing() == null) return reported;
        long estimated = request.pricing().estimateCostMicros(reported);
        return estimated < 0 ? reported : new AiUsage(reported.inputTokens(),
                reported.outputTokens(), reported.totalTokens(), estimated);
    }

    private static void addKnown(AtomicLong target, long value) {
        if (value <= 0) return;
        target.accumulateAndGet(value, (current, addition) -> {
            long result = current + addition;
            return result < current ? Long.MAX_VALUE : result;
        });
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
        long remainingNanos = backoff.toNanos();
        while (remainingNanos > 0) {
            request.cancellationToken().throwIfCancelled();
            long slice = Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(50));
            long millis = TimeUnit.NANOSECONDS.toMillis(slice);
            int nanos = (int) (slice - TimeUnit.MILLISECONDS.toNanos(millis));
            try {
                Thread.sleep(millis, nanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            remainingNanos -= slice;
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
            rejectedRequests.incrementAndGet();
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

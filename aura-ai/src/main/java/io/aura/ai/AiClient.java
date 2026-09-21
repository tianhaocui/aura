package io.aura.ai;

import io.aura.contract.CancellationToken;
import java.time.Duration;
import java.time.Instant;
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
    private final AiUsageSink usageSink;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong costMicros = new AtomicLong();
    private final AtomicLong unknownUsage = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();
    private final AtomicLong timedOutRequests = new AtomicLong();

    public AiClient(AiProvider provider) {
        this(provider, createDefaultExecutor(), true, AiUsageSink.NOOP);
    }

    public AiClient(AiProvider provider, ExecutorService executor) {
        this(provider, executor, false, AiUsageSink.NOOP);
    }

    /** Creates a client with an optional sink for durable accounting or audit. */
    public AiClient(AiProvider provider, ExecutorService executor, AiUsageSink usageSink) {
        this(provider, executor, false, usageSink);
    }

    private AiClient(AiProvider provider, ExecutorService executor, boolean ownsExecutor,
                     AiUsageSink usageSink) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
        this.usageSink = usageSink == null ? AiUsageSink.NOOP : usageSink;
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
        request.enforceInputBudget();
        request.cancellationToken().throwIfCancelled();
        long deadline = request.timeout() == null || request.timeout().isZero()
                ? Long.MAX_VALUE : System.nanoTime() + request.timeout().toNanos();
        try {
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
                } catch (TimeoutException error) {
                    future.cancel(true);
                    throw error;
                } catch (CancellationException | InterruptedException error) {
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
                    sleepBackoff(request, deadline);
                }
            }
        } catch (TimeoutException error) {
            timedOutRequests.incrementAndGet();
            request.cancellationToken().cancel();
            throw error;
        }
    }

    public void stream(AiRequest request, AiStreamHandler handler) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        request.enforceInputBudget();
        request.cancellationToken().throwIfCancelled();
        StreamCallbacks callbacks = new StreamCallbacks(handler);
        long deadline = request.timeout() == null || request.timeout().isZero()
                ? Long.MAX_VALUE : System.nanoTime() + request.timeout().toNanos();
        Future<Void> startFuture;
        try {
            startFuture = submit(() -> {
                callbacks.start();
                return null;
            });
        } catch (RuntimeException error) {
            callbacks.error(error);
            throw error;
        }
        try {
            await(startFuture, remaining(request.timeout(), deadline), request.cancellationToken());
        } catch (TimeoutException | CancellationException | InterruptedException error) {
            if (error instanceof TimeoutException) timedOutRequests.incrementAndGet();
            request.cancellationToken().cancel();
            startFuture.cancel(true);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            callbacks.error(error);
            throw error;
        } catch (ExecutionException error) {
            request.cancellationToken().cancel();
            startFuture.cancel(true);
            Throwable cause = unwrap(error);
            callbacks.error(cause);
            throwFailure(cause);
        }
        for (int attempt = 1; ; attempt++) {
            StreamAttempt state = new StreamAttempt(request, callbacks);
            Duration remaining;
            try {
                remaining = remaining(request.timeout(), deadline);
            } catch (TimeoutException error) {
                timedOutRequests.incrementAndGet();
                request.cancellationToken().cancel();
                callbacks.error(error);
                throw error;
            }
            Future<AiResponse> future;
            try {
                future = submit(() -> executeStreamAttempt(request, state));
            } catch (RuntimeException error) {
                callbacks.error(error);
                throw error;
            }
            try {
                AiResponse response = awaitStream(
                        future, remaining, request.cancellationToken(), state);
                recordUsage(request, response);
                enforceBudget(request, response);
                validateStreamingStructuredOutput(request, response, state.content());
                callbacks.complete(response);
                return;
            } catch (TimeoutException | CancellationException | InterruptedException error) {
                if (error instanceof TimeoutException) timedOutRequests.incrementAndGet();
                request.cancellationToken().cancel();
                future.cancel(true);
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                callbacks.error(error);
                throw error;
            } catch (ExecutionException error) {
                future.cancel(true);
                Throwable cause = unwrap(error);
                if (!state.tokenDelivered()
                        && attempt < request.retryPolicy().maxAttempts()
                        && request.retryPolicy().shouldRetry(cause)) {
                    try {
                        sleepBackoff(request, deadline);
                    } catch (Exception backoffFailure) {
                        if (backoffFailure instanceof TimeoutException) timedOutRequests.incrementAndGet();
                        request.cancellationToken().cancel();
                        callbacks.error(backoffFailure);
                        throw backoffFailure;
                    }
                    continue;
                }
                request.cancellationToken().cancel();
                callbacks.error(cause);
                throwFailure(cause);
                return;
            } catch (RuntimeException error) {
                request.cancellationToken().cancel();
                future.cancel(true);
                callbacks.error(error);
                throw error;
            }
        }
    }

    private AiResponse executeStreamAttempt(AiRequest request, StreamAttempt state) throws Exception {
        try {
            request.cancellationToken().throwIfCancelled();
            provider.stream(request, state);
        } catch (Throwable error) {
            Throwable callbackFailure = state.callbackFailure();
            if (callbackFailure != null) return throwFailure(callbackFailure);
            AiProviderContractException contractFailure = state.contractFailure();
            if (contractFailure != null) throw contractFailure;
            if (state.terminal()) {
                throw new AiProviderContractException(
                        "AI provider threw after a terminal stream callback", error);
            }
            return throwFailure(error);
        }
        Throwable callbackFailure = state.callbackFailure();
        if (callbackFailure != null) return throwFailure(callbackFailure);
        AiProviderContractException contractFailure = state.contractFailure();
        if (contractFailure != null) throw contractFailure;
        if (!state.terminal()) {
            throw new AiProviderContractException(
                    "AI provider returned without a terminal stream callback");
        }
        if (state.failure() != null) return throwFailure(state.failure());
        return state.response();
    }

    private static final class StreamCallbacks {
        private final AiStreamHandler handler;
        private final java.util.concurrent.locks.ReentrantLock lock =
                new java.util.concurrent.locks.ReentrantLock();
        private final AtomicReference<StreamTerminal> terminal = new AtomicReference<>();
        private boolean delivered;

        private StreamCallbacks(AiStreamHandler handler) { this.handler = handler; }

        private void start() {
            lock.lock();
            try {
                if (terminal.get() != null) {
                    deliverTerminal();
                    return;
                }
                handler.onStart();
                deliverTerminal();
            } finally {
                lock.unlock();
            }
        }

        private void token(String token) {
            lock.lock();
            try {
                if (terminal.get() != null) {
                    deliverTerminal();
                    throw new CancellationException("AI stream is already terminal");
                }
                try {
                    handler.onToken(token);
                } finally {
                    deliverTerminal();
                }
            } finally {
                lock.unlock();
            }
        }

        private void complete(AiResponse response) {
            terminal.compareAndSet(null, new StreamTerminal(response, null));
            tryDeliverTerminal();
        }

        private void error(Throwable error) {
            terminal.compareAndSet(null, new StreamTerminal(null, error));
            tryDeliverTerminal();
        }

        private void tryDeliverTerminal() {
            if (!lock.tryLock()) return;
            try {
                deliverTerminal();
            } finally {
                lock.unlock();
            }
        }

        private void deliverTerminal() {
            StreamTerminal value = terminal.get();
            if (value == null || delivered) return;
            delivered = true;
            if (value.error() == null) {
                handler.onComplete(value.response());
                return;
            }
            try {
                handler.onError(value.error());
            } catch (Throwable callbackError) {
                if (callbackError != value.error()) value.error().addSuppressed(callbackError);
            }
        }
    }

    private record StreamTerminal(AiResponse response, Throwable error) { }

    private static final class StreamAttempt implements AiStreamHandler {
        private static final int OPEN = 0;
        private static final int COMPLETE = 1;
        private static final int ERROR = 2;

        private final AiRequest request;
        private final StreamCallbacks downstream;
        private final StringBuilder content = new StringBuilder();
        private final AtomicInteger terminal = new AtomicInteger(OPEN);
        private final AtomicBoolean tokenDelivered = new AtomicBoolean();
        private final AtomicReference<AiResponse> response = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        private final AtomicReference<AiProviderContractException> contractFailure =
                new AtomicReference<>();

        private StreamAttempt(AiRequest request, StreamCallbacks downstream) {
            this.request = request;
            this.downstream = downstream;
        }

        @Override public synchronized void onToken(String token) {
            request.cancellationToken().throwIfCancelled();
            if (terminal.get() != OPEN) {
                failContract("AI provider emitted a token after a terminal stream callback");
            }
            if (token != null) {
                content.append(token);
                try {
                    enforceOutputCharacters(request, content.length());
                } catch (RuntimeException error) {
                    callbackFailure.compareAndSet(null, error);
                    request.cancellationToken().cancel();
                    throw error;
                }
            }
            tokenDelivered.set(true);
            try {
                downstream.token(token);
            } catch (Throwable error) {
                callbackFailure.compareAndSet(null, error);
                request.cancellationToken().cancel();
                throwUnchecked(error);
            }
        }

        @Override public synchronized void onComplete(AiResponse value) {
            if (value == null) failContract("AI provider completed a stream with a null response");
            if (!terminal.compareAndSet(OPEN, COMPLETE)) {
                failContract("AI provider emitted more than one terminal stream callback");
            }
            response.set(value);
        }

        @Override public synchronized void onError(Throwable error) {
            if (error == null) failContract("AI provider failed a stream with a null error");
            if (!terminal.compareAndSet(OPEN, ERROR)) {
                failContract("AI provider emitted more than one terminal stream callback");
            }
            failure.set(error);
        }

        private void failContract(String message) {
            AiProviderContractException error = new AiProviderContractException(message);
            contractFailure.compareAndSet(null, error);
            throw error;
        }

        private Throwable knownFailure() {
            Throwable known = callbackFailure.get();
            if (known != null) return known;
            return contractFailure.get();
        }

        private boolean terminal() { return terminal.get() != OPEN; }
        private boolean tokenDelivered() { return tokenDelivered.get(); }
        private StringBuilder content() { return content; }
        private AiResponse response() { return response.get(); }
        private Throwable failure() { return failure.get(); }
        private Throwable callbackFailure() { return callbackFailure.get(); }
        private AiProviderContractException contractFailure() { return contractFailure.get(); }
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
        try {
            usageSink.record(new AiUsageSink.UsageEvent(
                    Instant.now(), request.model(), request.inputCharacters(), usage,
                    usage.hasTokenUsage() || usage.hasCost()));
        } catch (RuntimeException ignored) {
            // Accounting must not turn a completed provider response into an application failure.
        }
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

    private static <T> T throwFailure(Throwable error) throws Exception {
        if (error instanceof Exception exception) throw exception;
        if (error instanceof Error fatal) throw fatal;
        throw new RuntimeException(error);
    }

    private static void throwUnchecked(Throwable error) {
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error instanceof Error fatal) throw fatal;
        throw new RuntimeException(error);
    }

    private static Duration remaining(Duration configured, long deadline) throws TimeoutException {
        if (configured == null || configured.isZero()) return Duration.ZERO;
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException("AI request timed out after " + configured);
        return Duration.ofNanos(nanos);
    }

    private static void sleepBackoff(AiRequest request, long deadline) throws Exception {
        Duration backoff = request.retryPolicy().backoff();
        if (backoff.isZero()) return;
        long remainingNanos = backoff.toNanos();
        while (remainingNanos > 0) {
            request.cancellationToken().throwIfCancelled();
            long slice = Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(50));
            if (deadline != Long.MAX_VALUE) {
                long deadlineNanos = deadline - System.nanoTime();
                if (deadlineNanos <= 0) {
                    throw new TimeoutException("AI request timed out during retry backoff");
                }
                slice = Math.min(slice, deadlineNanos);
            }
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

    private static <T> T awaitStream(
            Future<T> future, Duration timeout, CancellationToken token, StreamAttempt state)
            throws Exception {
        long deadline = timeout == null || timeout.isZero()
                ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        while (true) {
            Throwable known = state.knownFailure();
            if (known != null) {
                future.cancel(true);
                throw new ExecutionException(known);
            }
            try {
                token.throwIfCancelled();
            } catch (CancellationException cancelled) {
                known = state.knownFailure();
                if (known != null) {
                    future.cancel(true);
                    throw new ExecutionException(known);
                }
                throw cancelled;
            }
            long slice = TimeUnit.MILLISECONDS.toNanos(10);
            if (deadline != Long.MAX_VALUE) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    known = state.knownFailure();
                    if (known != null) {
                        future.cancel(true);
                        throw new ExecutionException(known);
                    }
                    throw new TimeoutException("AI request timed out after " + timeout);
                }
                slice = Math.min(slice, remaining);
            }
            try {
                return future.get(slice, TimeUnit.NANOSECONDS);
            } catch (TimeoutException poll) {
                // Recheck provider callback state, cancellation, and the absolute deadline.
            } catch (ExecutionException | CancellationException terminal) {
                Throwable terminalCause = state.knownFailure();
                if (terminalCause != null) throw new ExecutionException(terminalCause);
                throw terminal;
            }
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

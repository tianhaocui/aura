package io.aura.workflow;

import io.aura.contract.CancellationToken;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkflowRuntime<C> implements AutoCloseable {
    private final WorkflowRegistry registry;
    private final WorkflowExecutionStore store;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, CancellationToken> activeCancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<WorkflowResult>> inFlightByKey = new ConcurrentHashMap<>();
    private final AtomicLong rejectedExecutions = new AtomicLong();
    private final CancellationToken runtimeCancellation = new CancellationToken();

    public WorkflowRuntime() { this(new WorkflowRegistry(), new InMemoryWorkflowExecutionStore()); }
    public WorkflowRuntime(WorkflowRegistry registry) { this(registry, new InMemoryWorkflowExecutionStore()); }
    public WorkflowRuntime(WorkflowRegistry registry, WorkflowExecutionStore store) {
        this(registry, store, defaultWorkers(), 256);
    }
    /** Creates a runtime with explicit bounded executor settings. */
    public WorkflowRuntime(WorkflowRegistry registry, WorkflowExecutionStore store,
                           int workers, int queueCapacity) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        if (workers < 1) throw new IllegalArgumentException("workers must be positive");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, "aura-workflow-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public WorkflowRegistry registry() { return registry; }
    public WorkflowExecutionStore store() { return store; }
    public int activeExecutions() { return executor.getActiveCount(); }
    public int queuedExecutions() { return executor.getQueue().size(); }
    public long completedExecutions() { return executor.getCompletedTaskCount(); }
    public long rejectedExecutions() { return rejectedExecutions.get(); }
    public int workerLimit() { return executor.getMaximumPoolSize(); }
    public int queueCapacity() { return executor.getQueue().remainingCapacity() + executor.getQueue().size(); }

    public WorkflowResult execute(String workflowId, C context, WorkflowSelector<C> selector) {
        return execute(workflowId, context, selector, new CancellationToken());
    }

    public WorkflowResult execute(String workflowId, C context, WorkflowSelector<C> selector,
                                  CancellationToken cancellationToken) {
        return execute(workflowId, context, selector, cancellationToken, null);
    }

    public WorkflowResult execute(String workflowId, C context, WorkflowSelector<C> selector,
                                  CancellationToken cancellationToken, String idempotencyKey) {
        @SuppressWarnings("unchecked") WorkflowDefinition<C> definition = (WorkflowDefinition<C>) registry.get(workflowId);
        CancellationToken executionToken = linkedToken(cancellationToken);
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            java.util.Optional<WorkflowExecution> existing = store.findByIdempotencyKey(workflowId, key);
            if (existing.isPresent()) return new WorkflowResult(existing.get(), null);
        }
        return definition.execute(context, selector, executionToken, store::save, key);
    }
    public WorkflowResult execute(String workflowId, C context) {
        return execute(workflowId, context, WorkflowSelector.defaultVariant());
    }

    public CompletableFuture<WorkflowResult> executeAsync(String workflowId, C context,
                                                           WorkflowSelector<C> selector) {
        return executeAsync(workflowId, context, selector, new CancellationToken());
    }

    public CompletableFuture<WorkflowResult> executeAsync(String workflowId, C context,
                                                           WorkflowSelector<C> selector,
                                                           CancellationToken cancellationToken) {
        return executeAsync(workflowId, context, selector, cancellationToken, null);
    }

    public CompletableFuture<WorkflowResult> executeAsync(String workflowId, C context,
                                                           WorkflowSelector<C> selector,
                                                           String idempotencyKey) {
        return executeAsync(workflowId, context, selector, new CancellationToken(), idempotencyKey);
    }

    public CompletableFuture<WorkflowResult> executeAsync(String workflowId, C context,
                                                           WorkflowSelector<C> selector,
                                                           CancellationToken cancellationToken,
                                                           String idempotencyKey) {
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        CancellationToken executionToken = linkedToken(cancellationToken);
        String key = normalizeKey(idempotencyKey);
        String inFlightKey = key == null ? null : workflowId + "\u0000" + key;
        if (inFlightKey != null) {
            CompletableFuture<WorkflowResult> previous = inFlightByKey.get(inFlightKey);
            if (previous != null) return previous;
        }
        if (key != null) {
            java.util.Optional<WorkflowExecution> existing = store.findByIdempotencyKey(workflowId, key);
            if (existing.isPresent()) return CompletableFuture.completedFuture(new WorkflowResult(existing.get(), null));
        }
        CompletableFuture<WorkflowResult> future = new CompletableFuture<>();
        if (inFlightKey != null) {
            CompletableFuture<WorkflowResult> previous = inFlightByKey.putIfAbsent(inFlightKey, future);
            if (previous != null) return previous;
        }
        try {
            executor.execute(() -> {
                try {
                    @SuppressWarnings("unchecked") WorkflowDefinition<C> definition =
                            (WorkflowDefinition<C>) registry.get(workflowId);
                    WorkflowResult result = definition.execute(context, selector, executionToken, execution -> {
                        persistExecution(execution);
                        if (execution.status() == WorkflowExecution.Status.PENDING
                                || execution.status() == WorkflowExecution.Status.RUNNING) {
                            activeCancellations.put(execution.executionId(), executionToken);
                        } else {
                            activeCancellations.remove(execution.executionId(), executionToken);
                        }
                    }, key);
                    future.complete(result);
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                } finally {
                    if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
                }
            });
        } catch (RejectedExecutionException error) {
            rejectedExecutions.incrementAndGet();
            if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
            future.completeExceptionally(new WorkflowException(WorkflowException.EXECUTOR_SATURATED,
                    "Workflow executor is saturated or shut down", error));
        }
        return future;
    }

    public CompletableFuture<WorkflowResult> executeAsync(String workflowId, C context) {
        return executeAsync(workflowId, context, WorkflowSelector.defaultVariant());
    }

    public CompletableFuture<WorkflowResult> executeAsync(String workflowId, C context,
                                                           CancellationToken cancellationToken) {
        return executeAsync(workflowId, context, WorkflowSelector.defaultVariant(), cancellationToken);
    }

    public CompletableFuture<WorkflowResult> executeAsync(String workflowId, C context,
                                                           String idempotencyKey) {
        return executeAsync(workflowId, context, WorkflowSelector.defaultVariant(), idempotencyKey);
    }

    public java.util.Optional<WorkflowExecution> execution(String executionId) {
        return store.find(executionId);
    }

    /** Reads the redacted immutable projection exposed by a durable store. */
    public java.util.Optional<WorkflowExecutionSnapshot> executionSnapshot(String executionId) {
        return store.findSnapshot(executionId);
    }

    public java.util.List<WorkflowExecution> executions(String workflowId) {
        return store.list(workflowId);
    }

    public java.util.List<WorkflowExecutionSnapshot> executionSnapshots(String workflowId) {
        return store.listSnapshots(workflowId);
    }

    /** Requests cooperative cancellation of a queued or running execution. */
    public boolean cancel(String executionId) {
        if (executionId == null || executionId.isBlank()) return false;
        CancellationToken token = activeCancellations.get(executionId);
        return token != null && token.cancel();
    }
    public WorkflowResult execute(WorkflowDefinition<C> definition, C context, WorkflowSelector<C> selector) {
        return definition.execute(context, selector, linkedToken(new CancellationToken()), this::persistExecution);
    }

    public WorkflowResult execute(WorkflowDefinition<C> definition, C context,
                                  WorkflowSelector<C> selector, CancellationToken cancellationToken) {
        return definition.execute(context, selector, linkedToken(cancellationToken), this::persistExecution);
    }

    public WorkflowResult execute(WorkflowDefinition<C> definition, C context,
                                  WorkflowSelector<C> selector, CancellationToken cancellationToken,
                                  String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            java.util.Optional<WorkflowExecution> existing = store.findByIdempotencyKey(definition.id(), key);
            if (existing.isPresent()) return new WorkflowResult(existing.get(), null);
        }
        return definition.execute(context, selector, linkedToken(cancellationToken), this::persistExecution, key);
    }

    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null) return null;
        String value = idempotencyKey.trim();
        return value.isEmpty() ? null : value;
    }

    @Override public void close() {
        runtimeCancellation.cancel();
        activeCancellations.values().forEach(CancellationToken::cancel);
        activeCancellations.clear();
        inFlightByKey.values().forEach(future -> future.cancel(true));
        inFlightByKey.clear();
        executor.shutdownNow();
    }

    private CancellationToken linkedToken(CancellationToken callerToken) {
        return CancellationToken.linked(runtimeCancellation,
                Objects.requireNonNull(callerToken, "cancellationToken"));
    }

    private void persistExecution(WorkflowExecution execution) {
        store.save(execution);
        store.saveSnapshot(execution.snapshot());
    }

    private static int defaultWorkers() {
        return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }
}

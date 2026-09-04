package io.aura.workflow;

import io.aura.contract.CancellationToken;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkflowRuntime<C> implements AutoCloseable {
    private final WorkflowRegistry registry;
    private final WorkflowExecutionStore store;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, CancellationToken> activeCancellations = new ConcurrentHashMap<>();

    public WorkflowRuntime() { this(new WorkflowRegistry(), new InMemoryWorkflowExecutionStore()); }
    public WorkflowRuntime(WorkflowRegistry registry) { this(registry, new InMemoryWorkflowExecutionStore()); }
    public WorkflowRuntime(WorkflowRegistry registry, WorkflowExecutionStore store) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), task -> {
                    Thread thread = new Thread(task, "aura-workflow-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public WorkflowRegistry registry() { return registry; }
    public WorkflowExecutionStore store() { return store; }

    public WorkflowResult execute(String workflowId, C context, WorkflowSelector<C> selector) {
        return execute(workflowId, context, selector, new CancellationToken());
    }

    public WorkflowResult execute(String workflowId, C context, WorkflowSelector<C> selector,
                                  CancellationToken cancellationToken) {
        @SuppressWarnings("unchecked") WorkflowDefinition<C> definition = (WorkflowDefinition<C>) registry.get(workflowId);
        return definition.execute(context, selector, cancellationToken, store::save);
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
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        CompletableFuture<WorkflowResult> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    @SuppressWarnings("unchecked") WorkflowDefinition<C> definition =
                            (WorkflowDefinition<C>) registry.get(workflowId);
                    WorkflowResult result = definition.execute(context, selector, cancellationToken, execution -> {
                        store.save(execution);
                        if (execution.status() == WorkflowExecution.Status.PENDING
                                || execution.status() == WorkflowExecution.Status.RUNNING) {
                            activeCancellations.put(execution.executionId(), cancellationToken);
                        } else {
                            activeCancellations.remove(execution.executionId(), cancellationToken);
                        }
                    });
                    future.complete(result);
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
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

    public java.util.Optional<WorkflowExecution> execution(String executionId) {
        return store.find(executionId);
    }

    public java.util.List<WorkflowExecution> executions(String workflowId) {
        return store.list(workflowId);
    }

    /** Requests cooperative cancellation of a queued or running execution. */
    public boolean cancel(String executionId) {
        if (executionId == null || executionId.isBlank()) return false;
        CancellationToken token = activeCancellations.get(executionId);
        return token != null && token.cancel();
    }
    public WorkflowResult execute(WorkflowDefinition<C> definition, C context, WorkflowSelector<C> selector) {
        return definition.execute(context, selector);
    }

    public WorkflowResult execute(WorkflowDefinition<C> definition, C context,
                                  WorkflowSelector<C> selector, CancellationToken cancellationToken) {
        return definition.execute(context, selector, cancellationToken, store::save);
    }

    @Override public void close() {
        activeCancellations.values().forEach(CancellationToken::cancel);
        activeCancellations.clear();
        executor.shutdownNow();
    }
}

package io.aura.workflow;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkflowRuntime<C> implements AutoCloseable {
    private final WorkflowRegistry registry;
    private final WorkflowExecutionStore store;
    private final ThreadPoolExecutor executor;

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
        @SuppressWarnings("unchecked") WorkflowDefinition<C> definition = (WorkflowDefinition<C>) registry.get(workflowId);
        return definition.execute(context, selector);
    }
    public WorkflowResult execute(String workflowId, C context) {
        return execute(workflowId, context, WorkflowSelector.defaultVariant());
    }

    public CompletableFuture<WorkflowResult> executeAsync(String workflowId, C context,
                                                           WorkflowSelector<C> selector) {
        Objects.requireNonNull(selector, "selector");
        CompletableFuture<WorkflowResult> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    WorkflowResult result = execute(workflowId, context, selector);
                    store.save(result.execution());
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

    public java.util.Optional<WorkflowExecution> execution(String executionId) {
        return store.find(executionId);
    }

    public java.util.List<WorkflowExecution> executions(String workflowId) {
        return store.list(workflowId);
    }
    public WorkflowResult execute(WorkflowDefinition<C> definition, C context, WorkflowSelector<C> selector) {
        return definition.execute(context, selector);
    }

    @Override public void close() { executor.shutdownNow(); }
}

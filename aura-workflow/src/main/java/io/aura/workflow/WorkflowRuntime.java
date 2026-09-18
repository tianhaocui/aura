package io.aura.workflow;

import io.aura.contract.CancellationToken;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkflowRuntime<C> implements AutoCloseable {
    private final WorkflowRegistry registry;
    private final WorkflowExecutionStore store;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, CancellationToken> activeCancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutionHandle> executionHandles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<WorkflowResult>> inFlightByKey = new ConcurrentHashMap<>();
    private final AtomicLong rejectedExecutions = new AtomicLong();
    private final AtomicLong leaseConflicts = new AtomicLong();
    private final AtomicLong lostLeases = new AtomicLong();
    private final AtomicLong leaseRenewalRejections = new AtomicLong();
    private final CancellationToken runtimeCancellation = new CancellationToken();
    private final DurableWorkflowExecutionStore durableStore;
    private final Duration leaseTtl;
    private final String ownerId;
    private final Object leaseLifecycleMonitor = new Object();
    private final int leaseWorkerLimit;
    private volatile ScheduledThreadPoolExecutor leaseScheduler;
    private volatile ThreadPoolExecutor leaseRenewExecutor;
    private final ConcurrentHashMap<LeaseIdentity, LeaseGuard> leaseGuards = new ConcurrentHashMap<>();
    private final AtomicInteger activeLeaseGuards = new AtomicInteger();
    private final int maxActiveLeases;
    private final long renewalIntervalNanos;
    private volatile ScheduledFuture<?> leaseSweep;
    private final ThreadLocal<java.util.ArrayDeque<LeaseScope>> currentLeases =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    public WorkflowRuntime() { this(new WorkflowRegistry(), new InMemoryWorkflowExecutionStore()); }
    public WorkflowRuntime(WorkflowRegistry registry) { this(registry, new InMemoryWorkflowExecutionStore()); }
    public WorkflowRuntime(WorkflowRegistry registry, WorkflowExecutionStore store) {
        this(registry, store, defaultWorkers(), 256);
    }
    /** Creates a runtime with explicit bounded executor settings. */
    public WorkflowRuntime(WorkflowRegistry registry, WorkflowExecutionStore store,
                           int workers, int queueCapacity) {
        this(registry, store, workers, queueCapacity, Duration.ofSeconds(30),
                java.util.UUID.randomUUID().toString());
    }

    /** Creates a runtime with explicit bounded execution and durable lease settings. */
    public WorkflowRuntime(WorkflowRegistry registry, WorkflowExecutionStore store,
                           int workers, int queueCapacity, Duration leaseTtl, String ownerId) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        this.durableStore = store instanceof DurableWorkflowExecutionStore durable ? durable : null;
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.compareTo(Duration.ofMillis(30)) < 0) {
            throw new IllegalArgumentException("leaseTtl must be at least 30 milliseconds");
        }
        this.ownerId = ownerId == null || ownerId.isBlank()
                ? java.util.UUID.randomUUID().toString() : ownerId.trim();
        if (workers < 1) throw new IllegalArgumentException("workers must be positive");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, "aura-workflow-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        try {
            this.maxActiveLeases = Math.addExact(workers, queueCapacity);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("workflow lease capacity is too large", overflow);
        }
        this.leaseWorkerLimit = Math.max(1, Math.min(workers, 8));
        try {
            this.renewalIntervalNanos = Math.max(
                    TimeUnit.MILLISECONDS.toNanos(1), leaseTtl.toNanos() / 3L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("leaseTtl is too large", overflow);
        }
    }
    public WorkflowRegistry registry() { return registry; }
    public WorkflowExecutionStore store() { return store; }
    public int activeExecutions() { return executor.getActiveCount(); }
    public int queuedExecutions() { return executor.getQueue().size(); }
    public long completedExecutions() { return executor.getCompletedTaskCount(); }
    public long rejectedExecutions() { return rejectedExecutions.get(); }
    public long leaseConflicts() { return leaseConflicts.get(); }
    public long lostLeases() { return lostLeases.get(); }
    public int activeLeases() { return activeLeaseGuards.get(); }
    public String ownerId() { return ownerId; }
    boolean leaseRenewalStarted() { return leaseScheduler != null; }
    /** Lease/fencing token visible only while the current thread executes a workflow step. */
    public java.util.Optional<WorkflowLease> currentLease() {
        LeaseScope scope = currentLeases.get().peekLast();
        return scope == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(scope.guard().current());
    }
    public int workerLimit() { return executor.getMaximumPoolSize(); }
    public int queueCapacity() { return executor.getQueue().remainingCapacity() + executor.getQueue().size(); }
    public io.aura.contract.ExecutorMetrics executorMetrics() {
        return new io.aura.contract.ExecutorMetrics("workflow", true,
                executor.getMaximumPoolSize(), executor.getActiveCount(), executor.getQueue().size(),
                executor.getQueue().size() + executor.getQueue().remainingCapacity(),
                executor.getCompletedTaskCount(), rejectedExecutions.get(), 0);
    }
    public io.aura.contract.ExecutorMetrics leaseRenewalMetrics() {
        ThreadPoolExecutor renewalExecutor = leaseRenewExecutor;
        if (renewalExecutor == null) {
            return new io.aura.contract.ExecutorMetrics("workflow-lease-renewal", true,
                    leaseWorkerLimit, 0, 0, maxActiveLeases, 0,
                    leaseRenewalRejections.get(), 0);
        }
        return new io.aura.contract.ExecutorMetrics("workflow-lease-renewal", true,
                renewalExecutor.getMaximumPoolSize(), renewalExecutor.getActiveCount(),
                renewalExecutor.getQueue().size(),
                renewalExecutor.getQueue().size() + renewalExecutor.getQueue().remainingCapacity(),
                renewalExecutor.getCompletedTaskCount(), leaseRenewalRejections.get(), 0);
    }

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
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            java.util.Optional<WorkflowResult> retained = retainedResult(workflowId, key);
            if (retained.isPresent()) return retained.get();
        }
        CancellationToken executionToken = linkedToken(cancellationToken);
        final LeaseGuard lease;
        try {
            lease = acquireLease(workflowId, key, executionToken);
        } catch (RuntimeException | Error failure) {
            executionToken.detach();
            throw failure;
        }
        try {
            if (key != null) {
                java.util.Optional<WorkflowResult> retained = retainedResult(workflowId, key);
                if (retained.isPresent()) return retained.get();
            }
            lease.bind();
            WorkflowResult result = definition.execute(
                    context, selector, executionToken, state -> {
                        lease.throwIfLost();
                        persistExecution(state, lease);
                    }, key);
            return result;
        } finally {
            lease.unbind();
            lease.close();
            executionToken.detach();
        }
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
        @SuppressWarnings("unchecked") WorkflowDefinition<C> definition;
        try {
            definition = (WorkflowDefinition<C>) registry.get(workflowId);
        } catch (Throwable error) {
            CompletableFuture<WorkflowResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(error);
            return failed;
        }
        String key = normalizeKey(idempotencyKey);
        String inFlightKey = key == null ? null : workflowId + "\u0000" + key;
        if (inFlightKey != null) {
            CompletableFuture<WorkflowResult> previous = inFlightByKey.get(inFlightKey);
            if (previous != null) return previous;
        }
        if (key != null) {
            try {
                java.util.Optional<WorkflowResult> retained = retainedResult(workflowId, key);
                if (retained.isPresent()) return CompletableFuture.completedFuture(retained.get());
            } catch (Throwable error) {
                CompletableFuture<WorkflowResult> retained = new CompletableFuture<>();
                retained.completeExceptionally(error);
                return retained;
            }
        }
        CompletableFuture<WorkflowResult> future = new CompletableFuture<>();
        if (inFlightKey != null) {
            CompletableFuture<WorkflowResult> previous = inFlightByKey.putIfAbsent(inFlightKey, future);
            if (previous != null) return previous;
        }
        CancellationToken executionToken = linkedToken(cancellationToken);
        final LeaseGuard lease;
        try {
            lease = acquireLease(workflowId, key, executionToken);
        } catch (Throwable error) {
            executionToken.detach();
            if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
            future.completeExceptionally(error);
            return future;
        }
        if (key != null) {
            try {
                java.util.Optional<WorkflowResult> retained = retainedResult(workflowId, key);
                if (retained.isPresent()) {
                    lease.close();
                    executionToken.detach();
                    if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
                    future.complete(retained.get());
                    return future;
                }
            } catch (Throwable error) {
                lease.close();
                executionToken.detach();
                if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
                future.completeExceptionally(error);
                return future;
            }
        }
        final WorkflowExecution execution;
        try {
            execution = definition.prepare(context, selector, key);
        } catch (Throwable error) {
            lease.close();
            executionToken.detach();
            if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
            future.completeExceptionally(error);
            return future;
        }
        ExecutionHandle handle = new ExecutionHandle(
                execution, executionToken, future, inFlightKey, lease);
        executionHandles.put(execution.executionId(), handle);
        try {
            persistExecution(execution, handle.lease);
        } catch (Throwable error) {
            executionHandles.remove(execution.executionId(), handle);
            lease.close();
            executionToken.detach();
            if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
            future.completeExceptionally(error);
            return future;
        }
        handle.cancellationRegistration = executionToken.onCancel(
                () -> cancelHandle(handle, false));
        future.whenComplete((ignored, error) -> {
            if (future.isCancelled()) cancel(execution.executionId());
        });
        try {
            executor.execute(() -> {
                if (!handle.claimed.compareAndSet(false, true)) return;
                if (future.isDone()) {
                    try {
                        if (execution.status() == WorkflowExecution.Status.PENDING) {
                            execution.cancel(new java.util.concurrent.CancellationException("Workflow cancelled"));
                            persistExecution(execution, handle.lease);
                        }
                    } finally {
                        executionHandles.remove(execution.executionId(), handle);
                        activeCancellations.remove(execution.executionId(), executionToken);
                        handle.cancellationRegistration.close();
                        handle.lease.close();
                        executionToken.detach();
                        if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
                    }
                    return;
                }
                handle.workerStarted.set(true);
                WorkflowResult result = null;
                Throwable failure = null;
                try {
                    handle.lease.bind();
                    result = definition.executePrepared(context, selector, executionToken, executionState -> {
                        handle.lease.throwIfLost();
                        persistExecution(executionState, handle.lease);
                        if (executionState.status() == WorkflowExecution.Status.PENDING
                                || executionState.status() == WorkflowExecution.Status.RUNNING) {
                            activeCancellations.put(executionState.executionId(), executionToken);
                        } else {
                            activeCancellations.remove(executionState.executionId(), executionToken);
                        }
                    }, key, execution);
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    handle.lease.unbind();
                    executionHandles.remove(execution.executionId(), handle);
                    activeCancellations.remove(execution.executionId(), executionToken);
                    handle.cancellationRegistration.close();
                    handle.lease.close();
                    executionToken.detach();
                    if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
                }
                if (failure == null) future.complete(result);
                else future.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException error) {
            rejectedExecutions.incrementAndGet();
            executionHandles.remove(execution.executionId(), handle);
            handle.cancellationRegistration.close();
            execution.cancel(error);
            Throwable persistenceFailure = null;
            try {
                persistExecution(execution, handle.lease);
            } catch (Throwable failure) {
                persistenceFailure = failure;
            } finally {
                handle.lease.close();
                executionToken.detach();
            }
            if (inFlightKey != null) inFlightByKey.remove(inFlightKey, future);
            if (persistenceFailure != null) future.completeExceptionally(persistenceFailure);
            else future.completeExceptionally(new WorkflowException(WorkflowException.EXECUTOR_SATURATED,
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

    /**
     * Lists persisted non-terminal executions for explicit business recovery.
     * Aura never replays these automatically because an external side effect
     * may have completed before its final state was persisted.
     */
    public java.util.List<WorkflowRecoveryCandidate> recoveryCandidates(String workflowId) {
        try {
            return store.listSnapshots(workflowId).stream()
                    .filter(snapshot -> snapshot.status() == WorkflowExecution.Status.PENDING
                            || snapshot.status() == WorkflowExecution.Status.RUNNING)
                    .map(snapshot -> new WorkflowRecoveryCandidate(snapshot,
                            durableStore != null && snapshot.idempotencyKey() != null
                                    && durableStore.currentLease(
                                            snapshot.workflowId(), snapshot.idempotencyKey()).isPresent()))
                    .toList();
        } catch (WorkflowException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new WorkflowException(WorkflowException.STORE_FAILURE,
                    "Workflow recovery state could not be read", failure);
        }
    }

    /** Requests cooperative cancellation of a queued or running execution. */
    public boolean cancel(String executionId) {
        if (executionId == null || executionId.isBlank()) return false;
        ExecutionHandle handle = executionHandles.get(executionId);
        if (handle != null) return cancelHandle(handle, true);
        CancellationToken token = activeCancellations.get(executionId);
        return token != null && token.cancel();
    }

    private boolean cancelHandle(ExecutionHandle handle, boolean signalToken) {
        WorkflowExecution.Status status = handle.execution.status();
        if (status == WorkflowExecution.Status.SUCCEEDED
                || status == WorkflowExecution.Status.FAILED
                || status == WorkflowExecution.Status.CANCELLED) {
            return false;
        }
        if (signalToken) handle.token.cancel();
        if (handle.claimed.compareAndSet(false, true)) {
            handle.execution.cancel(new java.util.concurrent.CancellationException("Workflow cancelled"));
            try {
                persistExecution(handle.execution, handle.lease);
            } catch (Throwable failure) {
                handle.lease.close();
                executionHandles.remove(handle.execution.executionId(), handle);
                handle.cancellationRegistration.close();
                handle.token.detach();
                if (handle.inFlightKey != null) inFlightByKey.remove(handle.inFlightKey, handle.future);
                handle.future.completeExceptionally(failure);
                return true;
            }
            handle.lease.close();
            executionHandles.remove(handle.execution.executionId(), handle);
            handle.cancellationRegistration.close();
            handle.token.detach();
            if (handle.inFlightKey != null) inFlightByKey.remove(handle.inFlightKey, handle.future);
            handle.future.complete(new WorkflowResult(handle.execution, null));
        }
        return true;
    }
    public WorkflowResult execute(WorkflowDefinition<C> definition, C context, WorkflowSelector<C> selector) {
        return execute(definition, context, selector, new CancellationToken(), null);
    }

    public WorkflowResult execute(WorkflowDefinition<C> definition, C context,
                                  WorkflowSelector<C> selector, CancellationToken cancellationToken) {
        return execute(definition, context, selector, cancellationToken, null);
    }

    public WorkflowResult execute(WorkflowDefinition<C> definition, C context,
                                  WorkflowSelector<C> selector, CancellationToken cancellationToken,
                                  String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            java.util.Optional<WorkflowResult> retained = retainedResult(definition.id(), key);
            if (retained.isPresent()) return retained.get();
        }
        CancellationToken executionToken = linkedToken(cancellationToken);
        final LeaseGuard lease;
        try {
            lease = acquireLease(definition.id(), key, executionToken);
        } catch (RuntimeException | Error failure) {
            executionToken.detach();
            throw failure;
        }
        try {
            if (key != null) {
                java.util.Optional<WorkflowResult> retained = retainedResult(definition.id(), key);
                if (retained.isPresent()) return retained.get();
            }
            lease.bind();
            WorkflowResult result = definition.execute(
                    context, selector, executionToken, state -> {
                        lease.throwIfLost();
                        persistExecution(state, lease);
                    }, key);
            return result;
        } finally {
            lease.unbind();
            lease.close();
            executionToken.detach();
        }
    }

    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null) return null;
        String value = idempotencyKey.trim();
        return value.isEmpty() ? null : value;
    }

    @Override public void close() {
        runtimeCancellation.cancel();
        executionHandles.values().forEach(handle -> cancel(handle.execution.executionId()));
        activeCancellations.values().forEach(CancellationToken::cancel);
        activeCancellations.clear();
        inFlightByKey.values().forEach(future -> future.cancel(true));
        inFlightByKey.clear();
        executor.shutdownNow();
        long awaitMillis = Math.max(10L, Math.min(5_000L, leaseTtl.toMillis() / 2L));
        try {
            executor.awaitTermination(awaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        leaseGuards.values().forEach(LeaseGuard::lose);
        synchronized (leaseLifecycleMonitor) {
            if (leaseSweep != null) leaseSweep.cancel(false);
            if (leaseScheduler != null) leaseScheduler.shutdownNow();
            if (leaseRenewExecutor != null) leaseRenewExecutor.shutdownNow();
        }
    }

    private final class ExecutionHandle {
        private final WorkflowExecution execution;
        private final CancellationToken token;
        private final CompletableFuture<WorkflowResult> future;
        private final String inFlightKey;
        private final LeaseGuard lease;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean workerStarted = new AtomicBoolean();
        private CancellationToken.Registration cancellationRegistration = () -> { };

        private ExecutionHandle(WorkflowExecution execution, CancellationToken token,
                                CompletableFuture<WorkflowResult> future, String inFlightKey,
                                LeaseGuard lease) {
            this.execution = execution;
            this.token = token;
            this.future = future;
            this.inFlightKey = inFlightKey;
            this.lease = lease;
        }
    }

    private CancellationToken linkedToken(CancellationToken callerToken) {
        return CancellationToken.linked(runtimeCancellation,
                Objects.requireNonNull(callerToken, "cancellationToken"));
    }

    private void persistExecution(WorkflowExecution execution, LeaseGuard lease) {
        try {
            WorkflowExecutionSnapshot snapshot = execution.snapshot();
            if (durableStore != null && lease != null && lease.current() != null) {
                durableStore.saveLeased(snapshot, lease.current());
            } else {
                store.saveSnapshot(snapshot);
                store.retainLocal(execution);
            }
        } catch (WorkflowException failure) {
            if (WorkflowException.LEASE_LOST.equals(failure.code()) && lease != null) {
                lease.lose();
            }
            throw failure;
        } catch (RuntimeException failure) {
            throw new WorkflowException(WorkflowException.STORE_FAILURE,
                    "Workflow execution state could not be persisted", failure);
        }
    }

    private LeaseGuard acquireLease(String workflowId, String idempotencyKey,
                                    CancellationToken executionToken) {
        if (durableStore == null || idempotencyKey == null) return new LeaseGuard();
        if (runtimeCancellation.isCancelled()) {
            throw new WorkflowException(WorkflowException.CANCELLED,
                    "Workflow runtime is stopping");
        }
        final WorkflowLease lease;
        try {
            lease = durableStore.tryAcquire(workflowId, idempotencyKey, ownerId, leaseTtl)
                    .orElseThrow(() -> {
                        leaseConflicts.incrementAndGet();
                        return new WorkflowException(WorkflowException.LEASE_UNAVAILABLE,
                                "Workflow execution is owned by another runtime");
                    });
        } catch (WorkflowException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new WorkflowException(WorkflowException.STORE_FAILURE,
                    "Workflow lease could not be acquired", failure);
        }
        if (runtimeCancellation.isCancelled()) {
            releaseLeaseSafely(lease);
            throw new WorkflowException(WorkflowException.CANCELLED,
                    "Workflow runtime is stopping");
        }
        if (activeLeaseGuards.incrementAndGet() > maxActiveLeases) {
            activeLeaseGuards.decrementAndGet();
            releaseLeaseSafely(lease);
            throw new WorkflowException(WorkflowException.LEASE_CAPACITY_EXHAUSTED,
                    "Workflow lease renewal capacity is exhausted");
        }
        try {
            ensureLeaseRenewalInfrastructure();
        } catch (RuntimeException | Error failure) {
            activeLeaseGuards.decrementAndGet();
            releaseLeaseSafely(lease);
            throw failure;
        }
        LeaseGuard guard = new LeaseGuard(lease, executionToken);
        leaseGuards.put(LeaseIdentity.from(lease), guard);
        if (runtimeCancellation.isCancelled()) {
            guard.close();
            throw new WorkflowException(WorkflowException.CANCELLED,
                    "Workflow runtime is stopping");
        }
        return guard;
    }

    private void ensureLeaseRenewalInfrastructure() {
        if (leaseScheduler != null) return;
        synchronized (leaseLifecycleMonitor) {
            if (leaseScheduler != null) return;
            if (runtimeCancellation.isCancelled()) {
                throw new WorkflowException(WorkflowException.CANCELLED,
                        "Workflow runtime is stopping");
            }
            ThreadPoolExecutor renewalExecutor = new ThreadPoolExecutor(
                    leaseWorkerLimit, leaseWorkerLimit, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(maxActiveLeases), task -> {
                        Thread thread = new Thread(task, "aura-workflow-lease-renew");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "aura-workflow-lease-sweep");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.setRemoveOnCancelPolicy(true);
            try {
                ScheduledFuture<?> sweep = scheduler.scheduleAtFixedRate(
                        this::sweepLeases, renewalIntervalNanos, renewalIntervalNanos,
                        TimeUnit.NANOSECONDS);
                leaseRenewExecutor = renewalExecutor;
                leaseSweep = sweep;
                leaseScheduler = scheduler;
            } catch (RuntimeException | Error failure) {
                renewalExecutor.shutdownNow();
                scheduler.shutdownNow();
                throw failure;
            }
        }
    }

    private void releaseLeaseSafely(WorkflowLease lease) {
        try { durableStore.release(lease); }
        catch (RuntimeException ignored) { }
    }

    private java.util.Optional<WorkflowResult> retainedResult(
            String workflowId, String idempotencyKey) {
        try {
            if (durableStore != null) {
                java.util.Optional<WorkflowExecutionSnapshot> snapshot =
                        durableStore.findSnapshotByIdempotencyKey(workflowId, idempotencyKey);
                if (snapshot.isPresent()) {
                    WorkflowExecutionSnapshot retained = snapshot.get();
                    if (!isTerminal(retained.status())
                            && durableStore.currentLease(workflowId, idempotencyKey).isPresent()) {
                        leaseConflicts.incrementAndGet();
                        throw new WorkflowException(WorkflowException.LEASE_UNAVAILABLE,
                                "Workflow execution is owned by another runtime");
                    }
                    throw retainedIdempotency(retained.executionId());
                }
                java.util.Optional<WorkflowExecution> execution = store.findByIdempotencyKey(
                        workflowId, idempotencyKey);
                if (execution.isPresent()) {
                    if (durableStore.currentLease(workflowId, idempotencyKey).isPresent()) {
                        leaseConflicts.incrementAndGet();
                        throw new WorkflowException(WorkflowException.LEASE_UNAVAILABLE,
                                "Workflow execution is owned by another runtime");
                    }
                    throw retainedIdempotency(execution.get().executionId());
                }
                return java.util.Optional.empty();
            }
            java.util.Optional<WorkflowExecution> execution = store.findByIdempotencyKey(
                    workflowId, idempotencyKey);
            if (execution.isPresent()) {
                WorkflowExecution retained = execution.get();
                if (isTerminal(retained.status())) return java.util.Optional.of(new WorkflowResult(retained, null));
                throw retainedIdempotency(retained.executionId());
            }
            return java.util.Optional.empty();
        } catch (WorkflowException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new WorkflowException(WorkflowException.STORE_FAILURE,
                    "Workflow idempotency state could not be read", failure);
        }
    }

    private static boolean isTerminal(WorkflowExecution.Status status) {
        return status == WorkflowExecution.Status.SUCCEEDED
                || status == WorkflowExecution.Status.FAILED
                || status == WorkflowExecution.Status.CANCELLED;
    }

    private static WorkflowException retainedIdempotency(String executionId) {
        return new WorkflowException(WorkflowException.IDEMPOTENCY_RETAINED,
                "Workflow idempotency key is retained by execution " + executionId,
                java.util.Map.of("executionId", executionId));
    }

    private void sweepLeases() {
        long now = System.nanoTime();
        leaseGuards.values().forEach(guard -> guard.submitRenewal(now));
    }

    private final class LeaseGuard implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicBoolean renewing = new AtomicBoolean();
        private volatile WorkflowLease lease;
        private final CancellationToken executionToken;
        private volatile long nextRenewalNanos;
        private final ThreadLocal<Boolean> boundHere =
                ThreadLocal.withInitial(() -> Boolean.FALSE);

        private LeaseGuard() {
            this.lease = null;
            this.executionToken = null;
            this.nextRenewalNanos = Long.MAX_VALUE;
        }

        private LeaseGuard(WorkflowLease lease, CancellationToken executionToken) {
            this.lease = lease;
            this.executionToken = executionToken;
            this.nextRenewalNanos = System.nanoTime() + renewalIntervalNanos;
        }

        private void submitRenewal(long nowNanos) {
            if (lease == null || closed.get() || lost.get()
                    || nowNanos < nextRenewalNanos || !renewing.compareAndSet(false, true)) return;
            try {
                ThreadPoolExecutor renewalExecutor = leaseRenewExecutor;
                if (renewalExecutor == null) {
                    renewing.set(false);
                    lose();
                    return;
                }
                renewalExecutor.execute(this::renew);
            } catch (RejectedExecutionException saturated) {
                renewing.set(false);
                leaseRenewalRejections.incrementAndGet();
                lose();
            }
        }

        private void renew() {
            try {
                if (closed.get()) return;
                java.util.Optional<WorkflowLease> renewed = durableStore.renew(lease, leaseTtl);
                if (renewed.isPresent()) {
                    lease = renewed.get();
                    nextRenewalNanos = System.nanoTime() + renewalIntervalNanos;
                } else lose();
            } catch (RuntimeException failure) {
                lose();
            } finally {
                renewing.set(false);
            }
        }

        private void lose() {
            if (closed.get()) return;
            if (lost.compareAndSet(false, true)) {
                lostLeases.incrementAndGet();
                executionToken.cancel();
            }
        }

        private void throwIfLost() {
            if (lost.get()) throw new WorkflowException(WorkflowException.LEASE_LOST,
                    "Workflow execution lease was lost");
        }

        private WorkflowLease current() { return lease; }

        private void bind() {
            if (boundHere.get()) return;
            currentLeases.get().addLast(new LeaseScope(this));
            boundHere.set(Boolean.TRUE);
        }

        private void unbind() {
            if (!boundHere.get()) return;
            java.util.ArrayDeque<LeaseScope> scopes = currentLeases.get();
            if (!scopes.isEmpty()) scopes.removeLast();
            if (scopes.isEmpty()) currentLeases.remove();
            boundHere.remove();
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (lease == null) return;
            leaseGuards.remove(LeaseIdentity.from(lease), this);
            activeLeaseGuards.decrementAndGet();
            try {
                durableStore.release(lease);
            } catch (RuntimeException ignored) {
                // Lease expiration is the safe fallback when release is unavailable.
            }
        }
    }

    private final class LeaseScope {
        private final LeaseGuard guard;

        private LeaseScope(LeaseGuard guard) { this.guard = guard; }
        private LeaseGuard guard() { return guard; }
    }

    private record LeaseIdentity(String workflowId, String idempotencyKey, String token) {
        private static LeaseIdentity from(WorkflowLease lease) {
            return new LeaseIdentity(lease.workflowId(), lease.idempotencyKey(), lease.token());
        }
    }

    private static int defaultWorkers() {
        return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }
}

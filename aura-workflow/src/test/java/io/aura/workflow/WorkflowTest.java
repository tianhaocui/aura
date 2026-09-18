package io.aura.workflow;

import org.junit.jupiter.api.Test;
import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.CancellationToken;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTest {
    @Test
    void executesDefaultOrder() {
        List<String> events = new ArrayList<>();
        WorkflowDefinition<List<String>> workflow = Workflow.<List<String>>builder("place-order")
                .step("place-order", c -> { c.add("place-order"); return null; })
                .step("outbound", c -> { c.add("outbound"); return null; })
                .step("deduct-inventory", c -> { c.add("deduct-inventory"); return null; })
                .before("place-order", "outbound")
                .before("outbound", "deduct-inventory")
                .build();

        WorkflowResult result = workflow.execute(events);

        assertThat(result.succeeded()).isTrue();
        assertThat(events).containsExactly("place-order", "outbound", "deduct-inventory");
    }

    @Test
    void selectsCustomerVariantAndSupportsInsertBeforeAfter() {
        List<String> events = new ArrayList<>();
        WorkflowDefinition<List<String>> workflow = Workflow.<List<String>>builder("place-order")
                .step("place-order", c -> { c.add("place-order"); return null; })
                .step("outbound", c -> { c.add("outbound"); return null; })
                .step("deduct-inventory", c -> { c.add("deduct-inventory"); return null; })
                .before("place-order", "outbound")
                .before("outbound", "deduct-inventory")
                .variant("customer-b", variant -> variant
                        .insertBefore("outbound", "stocktake", c -> { c.add("stocktake"); return null; })
                        .insertBefore("outbound", "quality-check", c -> { c.add("quality-check"); return null; }))
                .build();

        workflow.execute(events, WorkflowSelector.constant("customer-b"));

        assertThat(events).containsExactly("place-order", "stocktake", "quality-check", "outbound", "deduct-inventory");
    }

    @Test
    void rejectsCyclesAndConflictingOrder() {
        assertThatThrownBy(() -> Workflow.<Object>builder("cycle")
                .step("a", c -> null).step("b", c -> null).step("c", c -> null)
                .before("a", "b").before("b", "c").before("c", "a").build())
                .isInstanceOf(WorkflowException.class)
                .extracting("code").isEqualTo(WorkflowException.DEPENDENCY_CYCLE);

        assertThatThrownBy(() -> Workflow.<Object>builder("conflict")
                .step("a", c -> null).step("b", c -> null)
                .before("a", "b").before("b", "a").build())
                .isInstanceOf(WorkflowException.class)
                .extracting("code").isEqualTo(WorkflowException.ORDER_CONFLICT);
    }

    @Test
    void rejectsUnknownVariantAndDuplicateOrMissingSteps() {
        WorkflowDefinition<Object> workflow = Workflow.<Object>builder("known")
                .step("only", c -> null).build();
        assertThatThrownBy(() -> workflow.execute(new Object(), WorkflowSelector.constant("missing")))
                .isInstanceOf(WorkflowException.class)
                .extracting("code").isEqualTo(WorkflowException.UNKNOWN_VARIANT);

        assertThatThrownBy(() -> Workflow.<Object>builder("duplicate").step("a", c -> null).step("a", c -> null))
                .isInstanceOf(WorkflowException.class).extracting("code").isEqualTo(WorkflowException.DUPLICATE_STEP);
        assertThatThrownBy(() -> Workflow.<Object>builder("missing").step("a", c -> null).before("a", "b").build())
                .isInstanceOf(WorkflowException.class).extracting("code").isEqualTo(WorkflowException.MISSING_STEP);
    }

    @Test
    void recordsFailureAndRespectsRetryBoundary() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition<Object> workflow = Workflow.<Object>builder("retry")
                .step("unstable", c -> { attempts.incrementAndGet(); throw new IllegalStateException("boom"); },
                        StepMode.IDEMPOTENT, RetryPolicy.fixed(3, Duration.ZERO))
                .build();

        WorkflowResult result = workflow.execute(new Object());

        assertThat(attempts).hasValue(3);
        assertThat(result.execution().status()).isEqualTo(WorkflowExecution.Status.FAILED);
        assertThat(result.execution().step("unstable").status()).isEqualTo(StepExecution.Status.FAILED);
        assertThat(result.execution().step("unstable").attempts()).isEqualTo(3);
    }

    @Test
    void cancellationInterruptsLongRetryDelayPromptly() throws Exception {
        java.util.concurrent.CountDownLatch failed = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition<Object> workflow = Workflow.<Object>builder("cancel-retry")
                .step("unstable", context -> {
                    attempts.incrementAndGet();
                    failed.countDown();
                    throw new IllegalStateException("retry");
                }, StepMode.IDEMPOTENT, RetryPolicy.fixed(2, Duration.ofSeconds(5)))
                .build();
        CancellationToken token = new CancellationToken();
        java.util.concurrent.CompletableFuture<WorkflowResult> future =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> workflow.execute(new Object(), WorkflowSelector.defaultVariant(), token,
                                ignored -> { }));
        assertThat(failed.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        token.cancel();
        WorkflowResult result = future.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result.cancelled()).isTrue();
        assertThat(attempts).hasValue(1);
    }

    @Test
    void invokesCompensationInReverseOrder() {
        List<String> events = new ArrayList<>();
        WorkflowDefinition<List<String>> workflow = Workflow.<List<String>>builder("compensate")
                .step("reserve", c -> { c.add("reserve"); return "reservation"; }, StepMode.COMPENSATABLE, RetryPolicy.none())
                .step("charge", c -> { c.add("charge"); throw new IllegalStateException("declined"); })
                .before("reserve", "charge")
                .compensate("reserve", (c, value) -> c.add("release:" + value))
                .build();

        WorkflowResult result = workflow.execute(events);

        assertThat(result.execution().status()).isEqualTo(WorkflowExecution.Status.FAILED);
        assertThat(events).containsExactly("reserve", "charge", "release:reservation");
        assertThat(result.execution().step("reserve").status()).isEqualTo(StepExecution.Status.COMPENSATED);
    }

    @Test
    void workflowRuntimeComposesThroughAuraModuleContext() {
        AuraApp app = AuraApp.create()
                .use(WorkflowModule.create())
                .use(new AuraModule() {
                    @Override public ModuleMetadata descriptor() {
                        return ModuleMetadata.of("orders", "1.0.0").requires("workflow");
                    }

                    @Override public void install(ModuleContext context) {
                        WorkflowRegistry registry = context.require(WorkflowRegistry.class);
                        registry.register(Workflow.<List<String>>builder("fulfillment")
                                .step("place-order", events -> { events.add("place-order"); return null; })
                                .step("outbound", events -> { events.add("outbound"); return null; })
                                .before("place-order", "outbound")
                                .build());
                    }
                });

        app.prepare();
        assertThat(((io.aura.AuraWebRuntime) app.runtimeView()).directRoutes())
                .noneMatch(route -> route.path().startsWith("/__schema__"));
        @SuppressWarnings("unchecked")
        WorkflowRuntime<List<String>> runtime = (WorkflowRuntime<List<String>>) (WorkflowRuntime<?>) app.require(WorkflowRuntime.class);
        List<String> events = new ArrayList<>();
        WorkflowResult result = runtime.execute("fulfillment", events);

        assertThat(result.succeeded()).isTrue();
        assertThat(events).containsExactly("place-order", "outbound");
    }

    @Test
    void exposesDeterministicWorkflowSchemaForDiscovery() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<List<String>>builder("fulfillment")
                .step("place-order", events -> null)
                .step("outbound", events -> null)
                .before("place-order", "outbound")
                .variant("quality", flow -> flow.insertBefore("outbound", "quality-check", events -> null))
                .build());

        assertThat(registry.schema().toString())
                .contains("fulfillment", "place-order", "quality-check");
    }

    @Test
    void executesAsyncWithBoundedRuntimeAndStoresExecution() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<List<String>>builder("async-flow")
                .step("one", events -> { events.add("one"); return null; })
                .build());
        WorkflowRuntime<List<String>> runtime = new WorkflowRuntime<>(registry);
        try {
            List<String> events = new ArrayList<>();
            WorkflowResult result = runtime.executeAsync("async-flow", events).get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.succeeded()).isTrue();
            assertThat(runtime.execution(result.execution().executionId())).isPresent();
            assertThat(events).containsExactly("one");
        } finally {
            runtime.close();
        }
    }

    @Test
    void exposesAndValidatesBoundedExecutorMetrics() {
        WorkflowRegistry registry = new WorkflowRegistry();
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry,
                new InMemoryWorkflowExecutionStore(), 2, 7);
        try {
            assertThat(runtime.workerLimit()).isEqualTo(2);
            assertThat(runtime.queueCapacity()).isEqualTo(7);
            assertThat(runtime.queuedExecutions()).isZero();
            assertThat(runtime.activeExecutions()).isZero();
            assertThat(runtime.rejectedExecutions()).isZero();
        } finally {
            runtime.close();
        }
        assertThatThrownBy(() -> new WorkflowRuntime<>(registry,
                new InMemoryWorkflowExecutionStore(), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowRuntime<>(registry,
                new InMemoryWorkflowExecutionStore(), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propagatesCancellationAndPersistsCancelledExecution() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<CancellationToken> tokenRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        registry.register(Workflow.<Object>builder("cancel-flow")
                .step("long-step", context -> {
                    started.countDown();
                    while (!tokenRef.get().isCancelled()) Thread.sleep(10);
                    tokenRef.get().throwIfCancelled();
                    return null;
                })
                .step("never-run", context -> null)
                .before("long-step", "never-run")
                .build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry);
        try {
            CancellationToken token = new CancellationToken();
            tokenRef.set(token);
            java.util.concurrent.CompletableFuture<WorkflowResult> future =
                    runtime.executeAsync("cancel-flow", new Object(), token);
            assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            token.cancel();
            WorkflowResult result = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.cancelled()).isTrue();
            assertThat(result.failed()).isFalse();
            assertThat(runtime.execution(result.execution().executionId())).get().satisfies(stored -> {
                assertThat(stored.executionId()).isEqualTo(result.execution().executionId());
                assertThat(stored.status()).isEqualTo(result.execution().status());
            });
            assertThat(result.execution().step("never-run").status())
                    .isEqualTo(StepExecution.Status.PENDING);
            assertThat(result.execution().step("long-step").status())
                    .isEqualTo(StepExecution.Status.CANCELLED);
        } finally {
            runtime.close();
        }
    }

    @Test
    void preCancelledSynchronousExecutionFailsWithStableCancellationState() {
        WorkflowDefinition<Object> workflow = Workflow.<Object>builder("pre-cancelled")
                .step("never-run", context -> null)
                .build();
        CancellationToken token = new CancellationToken();
        token.cancel();

        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>();
        try {
            WorkflowResult result = runtime.execute(
                    workflow, new Object(), WorkflowSelector.defaultVariant(), token);

            assertThat(result.cancelled()).isTrue();
            assertThat(result.failed()).isFalse();
            assertThat(result.execution().status()).isEqualTo(WorkflowExecution.Status.CANCELLED);
        } finally {
            runtime.close();
        }
    }

    @Test
    void runtimeShutdownCancelsExecutionWithoutCancellingCallerToken() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        registry.register(Workflow.<Object>builder("shutdown-cancel")
                .step("long-step", context -> {
                    started.countDown();
                    Thread.sleep(10_000);
                    return null;
                })
                .build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry);
        CancellationToken caller = new CancellationToken();
        try {
            runtime.executeAsync("shutdown-cancel", new Object(),
                    WorkflowSelector.defaultVariant(), caller);
            assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String executionId = runtime.executions("shutdown-cancel").stream()
                    .findFirst().map(WorkflowExecution::executionId).orElse(null);
            assertThat(executionId).isNotNull();
            runtime.close();
            for (int i = 0; i < 100; i++) {
                if (runtime.execution(executionId)
                        .map(execution -> execution.status() == WorkflowExecution.Status.CANCELLED)
                        .orElse(false)) break;
                Thread.sleep(10);
            }
            assertThat(runtime.executions("shutdown-cancel"))
                    .anyMatch(execution -> execution.status() == WorkflowExecution.Status.CANCELLED);
            assertThat(caller.isCancelled()).isFalse();
        } finally {
            runtime.close();
        }
    }

    @Test
    void queuedExecutionCanBeCancelledBeforeItsWorkerStarts() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch firstStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger secondAttempts = new AtomicInteger();
        registry.register(Workflow.<String>builder("queued-cancel")
                .step("wait", context -> {
                    firstStarted.countDown();
                    releaseFirst.await();
                    return context;
                })
                .build());
        WorkflowRuntime<String> runtime = new WorkflowRuntime<>(
                registry, new InMemoryWorkflowExecutionStore(), 1, 2);
        try {
            CompletableFuture<WorkflowResult> first = runtime.executeAsync("queued-cancel", "first");
            assertThat(firstStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            WorkflowDefinition<String> secondDefinition = Workflow.<String>builder("queued-cancel-second")
                    .step("never-run", context -> {
                        secondAttempts.incrementAndGet();
                        return context;
                    }).build();
            // Register a separate id so the queued execution is unambiguous.
            registry.register(secondDefinition);
            CompletableFuture<WorkflowResult> second =
                    runtime.executeAsync("queued-cancel-second", "second");
            String executionId = runtime.executions("queued-cancel-second").stream()
                    .findFirst().map(WorkflowExecution::executionId).orElse(null);
            assertThat(executionId).isNotNull();

            assertThat(runtime.cancel(executionId)).isTrue();
            WorkflowResult cancelled = second.get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(cancelled.cancelled()).isTrue();
            assertThat(cancelled.execution().status()).isEqualTo(WorkflowExecution.Status.CANCELLED);
            assertThat(secondAttempts).hasValue(0);
            assertThat(runtime.executionSnapshot(executionId))
                    .get().extracting(WorkflowExecutionSnapshot::status)
                    .isEqualTo(WorkflowExecution.Status.CANCELLED);
            releaseFirst.countDown();
            first.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            runtime.close();
        }
    }

    @Test
    void unknownWorkflowDoesNotPoisonIdempotentAsyncFuture() throws Exception {
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>();
        try {
            CompletableFuture<WorkflowResult> first = runtime.executeAsync(
                    "missing-workflow", new Object(), WorkflowSelector.defaultVariant(), "same-key");
            assertThatThrownBy(() -> first.get(1, java.util.concurrent.TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(WorkflowException.class)
                    .hasMessageContaining("Unknown workflow");
            CompletableFuture<WorkflowResult> second = runtime.executeAsync(
                    "missing-workflow", new Object(), WorkflowSelector.defaultVariant(), "same-key");
            assertThatThrownBy(() -> second.get(1, java.util.concurrent.TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(WorkflowException.class)
                    .hasMessageContaining("Unknown workflow");
        } finally {
            runtime.close();
        }
    }

    @Test
    void cancellingQueuedFuturePersistsCancelledExecution() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        registry.register(Workflow.<String>builder("future-cancel")
                .step("wait", context -> {
                    started.countDown();
                    release.await();
                    return context;
                }).build());
        WorkflowRuntime<String> runtime = new WorkflowRuntime<>(
                registry, new InMemoryWorkflowExecutionStore(), 1, 2);
        try {
            CompletableFuture<WorkflowResult> first = runtime.executeAsync("future-cancel", "first");
            assertThat(started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            CompletableFuture<WorkflowResult> queued = runtime.executeAsync("future-cancel", "queued");
            String executionId = runtime.executions("future-cancel").stream()
                    .filter(execution -> execution.status() == WorkflowExecution.Status.PENDING)
                    .findFirst().map(WorkflowExecution::executionId).orElse(null);
            assertThat(executionId).isNotNull();
            assertThat(queued.cancel(false)).isTrue();
            release.countDown();
            first.get(1, java.util.concurrent.TimeUnit.SECONDS);
            for (int i = 0; i < 100; i++) {
                if (runtime.execution(executionId)
                        .map(execution -> execution.status() == WorkflowExecution.Status.CANCELLED)
                        .orElse(false)) break;
                Thread.sleep(5);
            }
            assertThat(runtime.execution(executionId))
                    .get().extracting(WorkflowExecution::status)
                    .isEqualTo(WorkflowExecution.Status.CANCELLED);
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test
    void synchronousRuntimeExecutionIsInspectableThroughTheSameStore() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<Object>builder("sync-flow")
                .step("one", context -> "done")
                .build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry);
        try {
            WorkflowResult result = runtime.execute("sync-flow", new Object());

            assertThat(runtime.execution(result.execution().executionId())).get().satisfies(stored -> {
                assertThat(stored.executionId()).isEqualTo(result.execution().executionId());
                assertThat(stored.status()).isEqualTo(result.execution().status());
            });
            assertThat(runtime.executions("sync-flow")).extracting(WorkflowExecution::executionId)
                    .containsExactly(result.execution().executionId());
        } finally {
            runtime.close();
        }
    }

    @Test
    void runtimePublishesSnapshotProjectionOnEveryAsyncStateChange() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<Object>builder("snapshot-runtime")
                .step("one", context -> "secret-result")
                .build());
        java.util.List<WorkflowExecutionSnapshot> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();
        WorkflowExecutionStore store = new WorkflowExecutionStore() {
            @Override public void retainLocal(WorkflowExecution execution) { }
            @Override public void saveSnapshot(WorkflowExecutionSnapshot snapshot) { snapshots.add(snapshot); }
            @Override public java.util.Optional<WorkflowExecution> find(String id) { return java.util.Optional.empty(); }
            @Override public java.util.List<WorkflowExecution> list(String workflowId) { return java.util.List.of(); }
        };
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, store);
        try {
            WorkflowResult result = runtime.executeAsync("snapshot-runtime", new Object()).get(2,
                    java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.succeeded()).isTrue();
            assertThat(snapshots).isNotEmpty();
            assertThat(snapshots).anyMatch(snapshot -> snapshot.status() == WorkflowExecution.Status.SUCCEEDED);
            assertThat(snapshots.toString()).doesNotContain("secret-result");
        } finally {
            runtime.close();
        }
    }

    @Test
    void runtimeExposesDurableSnapshotReadPaths() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<Object>builder("snapshot-read")
                .step("one", context -> null).build());
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore();
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, store);
        try {
            WorkflowResult result = runtime.execute("snapshot-read", new Object());
            assertThat(runtime.executionSnapshot(result.execution().executionId())).isPresent();
            assertThat(runtime.executionSnapshots("snapshot-read")).hasSize(1);
        } finally {
            runtime.close();
        }
    }

    @Test
    void runtimeCanCancelQueuedOrRunningExecutionById() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        registry.register(Workflow.<Object>builder("runtime-cancel")
                .step("first", context -> {
                    started.countDown();
                    Thread.sleep(100);
                    return null;
                })
                .step("second", context -> null)
                .build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry);
        try {
            java.util.concurrent.CompletableFuture<WorkflowResult> future =
                    runtime.executeAsync("runtime-cancel", new Object());
            String executionId = null;
            for (int i = 0; i < 100 && executionId == null; i++) {
                executionId = runtime.executions("runtime-cancel").stream()
                        .findFirst().map(WorkflowExecution::executionId).orElse(null);
                if (executionId == null) Thread.sleep(5);
            }
            assertThat(executionId).isNotNull();
            assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.cancel(executionId)).isTrue();
            WorkflowResult result = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.cancelled()).isTrue();
            assertThat(result.execution().step("second").status())
                    .isEqualTo(StepExecution.Status.PENDING);
            assertThat(runtime.cancel(executionId)).isFalse();
        } finally {
            runtime.close();
        }
    }

    @Test
    void defaultExecutionStoreIsBoundedAndEvictsOldestRecords() {
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore(2);
        WorkflowExecution first = new WorkflowExecution("flow", null);
        WorkflowExecution second = new WorkflowExecution("flow", null);
        WorkflowExecution third = new WorkflowExecution("flow", null);

        store.saveSnapshot(first.snapshot());
        store.retainLocal(first);
        store.saveSnapshot(second.snapshot());
        store.retainLocal(second);
        store.saveSnapshot(third.snapshot());
        store.retainLocal(third);

        assertThat(store.maxExecutions()).isEqualTo(2);
        assertThat(store.list("flow")).extracting(WorkflowExecution::executionId)
                .containsExactly(second.executionId(), third.executionId());
        assertThat(store.find(first.executionId())).isEmpty();
    }

    @Test
    void executionSnapshotIsImmutableAndExcludesBusinessResultsAndMessages() {
        WorkflowDefinition<Object> workflow = Workflow.<Object>builder("snapshot-flow")
                .step("secret-step", context -> "password=hidden")
                .build();
        WorkflowResult result = workflow.execute(new Object());

        WorkflowExecutionSnapshot snapshot = result.execution().snapshot();
        assertThat(snapshot.status()).isEqualTo(WorkflowExecution.Status.SUCCEEDED);
        assertThat(snapshot.steps()).singleElement().satisfies(step -> {
            assertThat(step.stepId()).isEqualTo("secret-step");
            assertThat(step.status()).isEqualTo(StepExecution.Status.SUCCEEDED);
        });
        assertThat(snapshot.toMap().toString()).doesNotContain("password", "hidden");
        assertThatThrownBy(() -> snapshot.toMap().put("status", "FAILED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void executionSnapshotKeepsStableWorkflowErrorCodeWithoutRawMessage() {
        WorkflowDefinition<Object> workflow = Workflow.<Object>builder("snapshot-failure")
                .step("fail", context -> { throw new WorkflowException(WorkflowException.MISSING_STEP, "secret details"); })
                .build();
        WorkflowExecutionSnapshot snapshot = workflow.execute(new Object()).execution().snapshot();

        assertThat(snapshot.errorCode()).isEqualTo(WorkflowException.MISSING_STEP);
        assertThat(snapshot.toMap().toString()).doesNotContain("secret details");
    }

    @Test
    void inMemoryStoreKeepsSnapshotProjectionAlongsideExecution() {
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore(1);
        WorkflowExecution execution = new WorkflowExecution("flow", null);
        store.saveSnapshot(execution.snapshot());
        store.retainLocal(execution);

        assertThat(store.findSnapshot(execution.executionId())).isPresent();
        assertThat(store.findSnapshot(execution.executionId()).orElseThrow().executionId())
                .isEqualTo(execution.executionId());
    }

    @Test
    void evictedSnapshotCannotBeRevivedAsAnUnboundedLocalExecution() {
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore(1);
        WorkflowExecution first = new WorkflowExecution("flow", null);
        WorkflowExecution second = new WorkflowExecution("flow", null);

        store.saveSnapshot(first.snapshot());
        store.saveSnapshot(second.snapshot());
        store.retainLocal(first);
        store.retainLocal(second);

        assertThat(store.find(first.executionId())).isEmpty();
        assertThat(store.findSnapshot(first.executionId())).isEmpty();
        assertThat(store.list("flow")).extracting(WorkflowExecution::executionId)
                .containsExactly(second.executionId());
    }

    @Test
    void idempotencyKeySharesConcurrentFutureAndRetainsRedactedTerminalState() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        registry.register(Workflow.<Object>builder("idempotent")
                .step("once", context -> {
                    runs.incrementAndGet();
                    started.countDown();
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    return "done";
                })
                .build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry);
        try {
            java.util.concurrent.CompletableFuture<WorkflowResult> first =
                    runtime.executeAsync("idempotent", new Object(), "message-42");
            assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.CompletableFuture<WorkflowResult> duplicate =
                    runtime.executeAsync("idempotent", new Object(), "message-42");
            assertThat(duplicate).isSameAs(first);
            release.countDown();

            WorkflowResult result = first.get(2, java.util.concurrent.TimeUnit.SECONDS);
            var retained = runtime.executeAsync("idempotent", new Object(), "message-42");
            assertThatThrownBy(() -> retained.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOfSatisfying(WorkflowException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(WorkflowException.IDEMPOTENCY_RETAINED);
                        assertThat(failure.details()).containsEntry(
                                "executionId", result.execution().executionId());
                    });
            assertThat(runs).hasValue(1);
            assertThat(result.execution().idempotencyKey()).isEqualTo("message-42");
            assertThat(runtime.execution(result.execution().executionId())).isEmpty();
            assertThat(runtime.executionSnapshot(result.execution().executionId())).isPresent();
        } finally {
            runtime.close();
        }
    }

    @Test
    void durableLeasePreventsCrossRuntimeExecutionBeforeFirstRecordIsVisible() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        AtomicInteger runs = new AtomicInteger();
        registry.register(Workflow.<Object>builder("leased")
                .step("once", context -> { runs.incrementAndGet(); return "done"; })
                .build());
        BlockingSaveStore store = new BlockingSaveStore();
        WorkflowRuntime<Object> first = new WorkflowRuntime<>(
                registry, store, 1, 8, java.time.Duration.ofMillis(300), "runtime-1");
        WorkflowRuntime<Object> second = new WorkflowRuntime<>(
                registry, store, 1, 8, java.time.Duration.ofMillis(300), "runtime-2");
        try {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<WorkflowResult>> firstResult =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> callerFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    firstResult.set(first.executeAsync("leased", new Object(), "event-42"));
                } catch (Throwable failure) {
                    callerFailure.set(failure);
                }
            });
            caller.start();
            assertThat(store.saveEntered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            var duplicate = second.executeAsync("leased", new Object(), "event-42");
            assertThatThrownBy(() -> duplicate.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.LEASE_UNAVAILABLE);
            assertThat(second.leaseConflicts()).isEqualTo(1);
            assertThat(first.executorMetrics().queueCapacity()).isEqualTo(8);

            store.allowSave.countDown();
            caller.join(2_000);
            assertThat(callerFailure.get()).isNull();
            assertThat(firstResult.get()).isNotNull();
            assertThat(firstResult.get().get(2, java.util.concurrent.TimeUnit.SECONDS).succeeded()).isTrue();
            assertThat(runs).hasValue(1);
            assertThat(store.currentLease("leased", "event-42")).isEmpty();
        } finally {
            store.allowSave.countDown();
            first.close();
            second.close();
        }
    }

    @Test
    void durableLeaseIsRenewedDuringLongRunningStep() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        registry.register(Workflow.<Object>builder("renewed")
                .step("wait", context -> {
                    started.countDown();
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    return null;
                }).build());
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore();
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(
                registry, store, 1, 8, java.time.Duration.ofMillis(90), "runtime-renew");
        try {
            var future = runtime.executeAsync("renewed", new Object(), "event-renew");
            assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Thread.sleep(220);
            assertThat(store.currentLease("renewed", "event-renew")).isPresent();
            assertThat(runtime.lostLeases()).isZero();
            assertThat(runtime.activeLeases()).isEqualTo(1);
            assertThat(runtime.leaseRenewalMetrics().queueCapacity()).isEqualTo(9);
            release.countDown();
            assertThat(future.get(2, java.util.concurrent.TimeUnit.SECONDS).succeeded()).isTrue();
            assertThat(store.currentLease("renewed", "event-renew")).isEmpty();
            assertThat(runtime.activeLeases()).isZero();
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test
    void currentLeaseReflectsTheLatestRenewedExpiryInsideTheStep() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.atomic.AtomicReference<WorkflowRuntime<Object>> runtimeRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> initialExpiry =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> renewedExpiry =
                new java.util.concurrent.atomic.AtomicReference<>();
        registry.register(Workflow.<Object>builder("current-renewed-lease")
                .step("inspect", context -> {
                    initialExpiry.set(runtimeRef.get().currentLease().orElseThrow().expiresAt());
                    Thread.sleep(220);
                    renewedExpiry.set(runtimeRef.get().currentLease().orElseThrow().expiresAt());
                    return null;
                }).build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(
                registry, new InMemoryWorkflowExecutionStore(), 1, 8,
                java.time.Duration.ofMillis(90), "current-lease-runtime");
        runtimeRef.set(runtime);
        try {
            assertThat(runtime.executeAsync(
                    "current-renewed-lease", new Object(), "event-current")
                    .get(2, java.util.concurrent.TimeUnit.SECONDS).succeeded()).isTrue();
            assertThat(renewedExpiry.get()).isAfter(initialExpiry.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    void leaseRenewalInfrastructureStartsOnlyForLeasedExecution() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<Object>builder("lazy-renewal")
                .step("done", context -> null).build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry);
        try {
            assertThat(runtime.leaseRenewalStarted()).isFalse();
            assertThat(runtime.execute("lazy-renewal", new Object()).succeeded()).isTrue();
            assertThat(runtime.leaseRenewalStarted()).isFalse();
            assertThat(runtime.execute(
                    "lazy-renewal", new Object(), WorkflowSelector.defaultVariant(),
                    new CancellationToken(), "event-lazy").succeeded()).isTrue();
            assertThat(runtime.leaseRenewalStarted()).isTrue();
        } finally {
            runtime.close();
        }
    }

    @Test
    void committedTerminalStateIsNotReversedByConcurrentRenewalLoss() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        AtomicInteger runs = new AtomicInteger();
        registry.register(Workflow.<Object>builder("commit-point")
                .step("write", context -> {
                    runs.incrementAndGet();
                    return "committed";
                }).build());
        FinalCommitRenewalFailureStore store = new FinalCommitRenewalFailureStore();
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, store, 1, 8,
                java.time.Duration.ofMillis(60), "commit-runtime");
        try {
            WorkflowResult result = runtime.executeAsync(
                    "commit-point", new Object(), "event-commit")
                    .get(3, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(result.succeeded()).isTrue();
            assertThat(store.renewalRejected.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(store.findSnapshotByIdempotencyKey("commit-point", "event-commit"))
                    .get().extracting(WorkflowExecutionSnapshot::status)
                    .isEqualTo(WorkflowExecution.Status.SUCCEEDED);
            var retained = runtime.executeAsync(
                    "commit-point", new Object(), "event-commit");
            assertThatThrownBy(() -> retained.get(1, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.IDEMPOTENCY_RETAINED);
            assertThat(runs).hasValue(1);
        } finally {
            runtime.close();
        }
    }

    @Test
    void persistenceFailureAfterStepSuccessNeverRetriesTheBusinessStep() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        AtomicInteger runs = new AtomicInteger();
        registry.register(Workflow.<Object>builder("persist-after-success")
                .step("external-write", context -> {
                    runs.incrementAndGet();
                    return "written";
                }, StepMode.IDEMPOTENT, RetryPolicy.fixed(3, Duration.ZERO))
                .build());
        CommitThenFailStore store = new CommitThenFailStore();
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, store, 1, 8,
                java.time.Duration.ofSeconds(1), "persist-runtime");
        try {
            var result = runtime.executeAsync(
                    "persist-after-success", new Object(), "event-persist");

            assertThatThrownBy(() -> result.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.STORE_FAILURE);
            assertThat(runs).hasValue(1);
            assertThat(store.findSnapshotByIdempotencyKey(
                    "persist-after-success", "event-persist"))
                    .get().satisfies(snapshot -> {
                        assertThat(snapshot.status()).isEqualTo(WorkflowExecution.Status.RUNNING);
                        assertThat(snapshot.steps()).singleElement()
                                .extracting(WorkflowExecutionSnapshot.StepSnapshot::status)
                                .isEqualTo(StepExecution.Status.SUCCEEDED);
                    });
        } finally {
            runtime.close();
        }
    }

    @Test
    void leaseRenewalRegistrySupportsTokensThatAreOnlyUniquePerWorkflowKey() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        registry.register(Workflow.<Object>builder("per-key-token")
                .step("wait", context -> {
                    started.countDown();
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    return null;
                }).build());
        PerKeyEpochStore store = new PerKeyEpochStore();
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, store, 2, 4,
                java.time.Duration.ofMillis(90), "epoch-runtime");
        try {
            var first = runtime.executeAsync("per-key-token", new Object(), "event-a");
            var second = runtime.executeAsync("per-key-token", new Object(), "event-b");
            assertThat(started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            Thread.sleep(250);

            assertThat(store.currentLease("per-key-token", "event-a")).isPresent();
            assertThat(store.currentLease("per-key-token", "event-b")).isPresent();
            assertThat(store.renewals.get()).isGreaterThanOrEqualTo(2);
            release.countDown();
            assertThat(first.get(2, java.util.concurrent.TimeUnit.SECONDS).succeeded()).isTrue();
            assertThat(second.get(2, java.util.concurrent.TimeUnit.SECONDS).succeeded()).isTrue();
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test
    void fencedSaveLossUpdatesLeaseHealthImmediately() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<Object>builder("save-lost")
                .step("never", context -> null).build());
        PerKeyEpochStore store = new PerKeyEpochStore();
        store.rejectSaves.set(true);
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, store, 1, 4,
                java.time.Duration.ofSeconds(1), "save-lost-runtime");
        try {
            var result = runtime.executeAsync("save-lost", new Object(), "event-lost");
            assertThatThrownBy(() -> result.get(1, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.LEASE_LOST);
            assertThat(runtime.lostLeases()).isEqualTo(1);
            assertThat(runtime.activeLeases()).isZero();
        } finally {
            runtime.close();
        }
    }

    @Test
    void expiredLeaseCanBeTakenOverWithoutOldOwnerReleasingNewLease() throws Exception {
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore(2);
        WorkflowLease first = store.tryAcquire("lease-store", "event-1", "owner-1",
                java.time.Duration.ofMillis(200)).orElseThrow();
        assertThat(store.tryAcquire("lease-store", "event-1", "owner-2",
                java.time.Duration.ofSeconds(1))).isEmpty();

        Thread.sleep(250);
        WorkflowLease second = store.tryAcquire("lease-store", "event-1", "owner-2",
                java.time.Duration.ofSeconds(1)).orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(store.renew(first, java.time.Duration.ofSeconds(1))).isEmpty();
        WorkflowExecution stale = new WorkflowExecution("lease-store", null, "event-1");
        assertThatThrownBy(() -> store.saveLeased(stale.snapshot(), first))
                .isInstanceOf(WorkflowException.class)
                .extracting("code").isEqualTo(WorkflowException.LEASE_LOST);

        store.release(first);
        assertThat(store.currentLease("lease-store", "event-1")).contains(second);
        store.release(second);
        assertThat(store.currentLease("lease-store", "event-1")).isEmpty();
    }

    @Test
    void leasedPersistenceRejectsMismatchedWorkflowIdentity() {
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore();
        WorkflowLease lease = store.tryAcquire("orders", "event-1", "owner-1",
                java.time.Duration.ofSeconds(1)).orElseThrow();
        WorkflowExecution wrongWorkflow = new WorkflowExecution("payments", null, "event-1");
        WorkflowExecution wrongKey = new WorkflowExecution("orders", null, "event-2");

        assertThatThrownBy(() -> store.saveLeased(
                wrongWorkflow.snapshot(), lease))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match the lease identity");
        assertThatThrownBy(() -> store.saveLeased(
                wrongKey.snapshot(), lease))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match the lease identity");
        assertThat(store.findByIdempotencyKey("orders", "event-1")).isEmpty();
        assertThat(store.findSnapshotByIdempotencyKey("orders", "event-1")).isEmpty();

        WorkflowExecution first = new WorkflowExecution("orders", null, "event-1");
        WorkflowExecution second = new WorkflowExecution("orders", null, "event-1");
        store.saveLeased(first.snapshot(), lease);
        assertThatThrownBy(() -> store.saveLeased(second.snapshot(), lease))
                .isInstanceOf(WorkflowException.class)
                .extracting("code").isEqualTo(WorkflowException.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void closedRuntimeRejectsNewLeasedExecutionWithStableCode() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<Object>builder("closed-runtime")
                .step("never", context -> null).build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry);
        runtime.close();

        assertThatThrownBy(() -> runtime.execute(
                "closed-runtime", new Object(), WorkflowSelector.defaultVariant(),
                new CancellationToken(), "event-after-close"))
                .isInstanceOf(WorkflowException.class)
                .extracting("code").isEqualTo(WorkflowException.CANCELLED);
    }

    @Test
    void businessStepCanReadFencingTokenOnlyDuringLeasedExecution() {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.atomic.AtomicReference<WorkflowRuntime<Object>> runtimeRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> fencingToken =
                new java.util.concurrent.atomic.AtomicReference<>();
        registry.register(Workflow.<Object>builder("fenced")
                .step("external-write", context -> {
                    fencingToken.set(runtimeRef.get().currentLease().orElseThrow().token());
                    return null;
                }).build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(
                registry, new InMemoryWorkflowExecutionStore(), 1, 8,
                java.time.Duration.ofSeconds(1), "fencing-runtime");
        runtimeRef.set(runtime);
        try {
            assertThat(runtime.execute("fenced", new Object(),
                    WorkflowSelector.defaultVariant(), new io.aura.contract.CancellationToken(),
                    "event-fenced").succeeded()).isTrue();
            assertThat(fencingToken.get()).isNotBlank();
            assertThat(runtime.currentLease()).isEmpty();
        } finally {
            runtime.close();
        }
    }

    @Test
    void nestedUnleasedExecutionHidesThenRestoresOuterFencingToken() {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.atomic.AtomicReference<WorkflowRuntime<Object>> runtimeRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<String> tokens = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean innerLeasePresent =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        WorkflowDefinition<Object> inner = Workflow.<Object>builder("inner")
                .step("read-outer-token", context -> {
                    innerLeasePresent.set(runtimeRef.get().currentLease().isPresent());
                    return null;
                }).build();
        registry.register(inner);
        registry.register(Workflow.<Object>builder("outer")
                .step("before", context -> {
                    tokens.add(runtimeRef.get().currentLease().orElseThrow().token());
                    runtimeRef.get().execute(inner, new Object(), WorkflowSelector.defaultVariant());
                    tokens.add(runtimeRef.get().currentLease().orElseThrow().token());
                    return null;
                }).build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(
                registry, new InMemoryWorkflowExecutionStore(), 1, 8,
                java.time.Duration.ofSeconds(1), "nested-runtime");
        runtimeRef.set(runtime);
        try {
            assertThat(runtime.execute("outer", new Object(),
                    WorkflowSelector.defaultVariant(), new io.aura.contract.CancellationToken(),
                    "event-outer").succeeded()).isTrue();
            assertThat(tokens).hasSize(2).allMatch(tokens.get(0)::equals);
            assertThat(innerLeasePresent).isFalse();
            assertThat(runtime.currentLease()).isEmpty();
        } finally {
            runtime.close();
        }
    }

    @Test
    void asyncCompletionCallbackRunsAfterFencingScopeAndLeaseRelease() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        registry.register(Workflow.<Object>builder("callback-scope")
                .step("done", context -> {
                    started.countDown();
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    return null;
                }).build());
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore();
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, store, 1, 8,
                java.time.Duration.ofSeconds(1), "callback-runtime");
        try {
            java.util.concurrent.atomic.AtomicReference<java.util.Optional<WorkflowLease>> callbackLease =
                    new java.util.concurrent.atomic.AtomicReference<>();
            var future = runtime.executeAsync("callback-scope", new Object(), "event-callback");
            assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            future.whenComplete((result, failure) -> callbackLease.set(runtime.currentLease()));
            release.countDown();
            assertThat(future.get(2, java.util.concurrent.TimeUnit.SECONDS).succeeded()).isTrue();
            assertThat(callbackLease.get()).isEmpty();
            assertThat(store.currentLease("callback-scope", "event-callback")).isEmpty();
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test
    void shutdownDoesNotReleaseLeaseWhileIgnoringCancellationStepStillRuns() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean release = new java.util.concurrent.atomic.AtomicBoolean();
        registry.register(Workflow.<Object>builder("shutdown-lease")
                .step("ignore-cancellation", context -> {
                    started.countDown();
                    while (!release.get()) {
                        java.util.concurrent.locks.LockSupport.parkNanos(
                                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
                        Thread.interrupted();
                    }
                    return null;
                }).build());
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore();
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, store, 1, 8,
                java.time.Duration.ofMillis(300), "shutdown-runtime");
        var future = runtime.executeAsync("shutdown-lease", new Object(), "event-shutdown");
        assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        runtime.close();

        assertThat(store.currentLease("shutdown-lease", "event-shutdown")).isPresent();
        Thread.sleep(350);
        assertThat(store.currentLease("shutdown-lease", "event-shutdown")).isEmpty();
        assertThat(runtime.lostLeases()).isEqualTo(1);
        release.set(true);
        assertThatThrownBy(() -> future.get(2, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(store.findSnapshotByIdempotencyKey("shutdown-lease", "event-shutdown"))
                .get().extracting(WorkflowExecutionSnapshot::status)
                .isNotEqualTo(WorkflowExecution.Status.SUCCEEDED);
        assertThat(store.findByIdempotencyKey("shutdown-lease", "event-shutdown")).isEmpty();

        WorkflowRuntime<Object> recoveryRuntime = new WorkflowRuntime<>(registry, store, 1, 8,
                java.time.Duration.ofMillis(300), "recovery-runtime");
        try {
            var duplicate = recoveryRuntime.executeAsync(
                    "shutdown-lease", new Object(), "event-shutdown");
            assertThatThrownBy(() -> duplicate.get(1, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.IDEMPOTENCY_RETAINED);
        } finally {
            recoveryRuntime.close();
        }
    }

    @Test
    void leaseRegistryIsBoundedWithTheExecutionStore() {
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore(1);
        assertThat(store.tryAcquire("flow-a", "event-a", "owner",
                java.time.Duration.ofSeconds(1))).isPresent();
        assertThatThrownBy(() -> store.tryAcquire("flow-b", "event-b", "owner",
                java.time.Duration.ofSeconds(1)))
                .isInstanceOf(WorkflowException.class)
                .extracting("code").isEqualTo(WorkflowException.LEASE_CAPACITY_EXHAUSTED);
    }

    @Test
    void recoveryCandidatesAreReportedButNeverReplayedAutomatically() {
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore();
        WorkflowExecution abandoned = new WorkflowExecution("recoverable", null, "event-abandoned");
        abandoned.start();
        store.saveSnapshot(abandoned.snapshot());
        store.retainLocal(abandoned);
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(new WorkflowRegistry(), store);
        try {
            assertThat(runtime.recoveryCandidates("recoverable")).singleElement().satisfies(candidate -> {
                assertThat(candidate.snapshot().executionId()).isEqualTo(abandoned.executionId());
                assertThat(candidate.currentlyLeased()).isFalse();
            });
            WorkflowLease lease = store.tryAcquire("recoverable", "event-abandoned",
                    "recovery-worker", java.time.Duration.ofSeconds(1)).orElseThrow();
            assertThat(runtime.recoveryCandidates("recoverable")).singleElement()
                    .extracting(WorkflowRecoveryCandidate::currentlyLeased).isEqualTo(true);
            store.release(lease);
        } finally {
            runtime.close();
        }
    }

    @Test
    void recoveryStoreFailuresUseStableCode() {
        WorkflowExecutionStore failing = new WorkflowExecutionStore() {
            @Override public void saveSnapshot(WorkflowExecutionSnapshot snapshot) { }
            @Override public java.util.Optional<WorkflowExecution> find(String executionId) {
                return java.util.Optional.empty();
            }
            @Override public java.util.List<WorkflowExecution> list(String workflowId) {
                return java.util.List.of();
            }
            @Override public java.util.List<WorkflowExecutionSnapshot> listSnapshots(String workflowId) {
                throw new IllegalStateException("database offline");
            }
        };
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(new WorkflowRegistry(), failing);
        try {
            assertThatThrownBy(() -> runtime.recoveryCandidates("orders"))
                    .isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.STORE_FAILURE);
        } finally {
            runtime.close();
        }
    }

    @Test
    void storeFailuresUseStableCodeBeforeBusinessStepsRun() {
        WorkflowRegistry registry = new WorkflowRegistry();
        AtomicInteger runs = new AtomicInteger();
        registry.register(Workflow.<Object>builder("store-failure")
                .step("must-not-run", context -> { runs.incrementAndGet(); return null; })
                .build());
        WorkflowExecutionStore failing = new WorkflowExecutionStore() {
            @Override public void saveSnapshot(WorkflowExecutionSnapshot snapshot) {
                throw new IllegalStateException("database offline");
            }
            @Override public java.util.Optional<WorkflowExecution> find(String executionId) { return java.util.Optional.empty(); }
            @Override public java.util.List<WorkflowExecution> list(String workflowId) { return java.util.List.of(); }
        };
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, failing);
        try {
            var future = runtime.executeAsync("store-failure", new Object());
            assertThatThrownBy(() -> future.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.STORE_FAILURE);
            assertThat(runs).hasValue(0);
        } finally {
            runtime.close();
        }
    }

    @Test
    void durableSnapshotPreventsReplayWhenMutableExecutionCannotBeReconstructed() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        AtomicInteger runs = new AtomicInteger();
        registry.register(Workflow.<Object>builder("snapshot-dedupe")
                .step("external-write", context -> { runs.incrementAndGet(); return null; })
                .build());
        SharedSnapshotBackend backend = new SharedSnapshotBackend();
        WorkflowRuntime<Object> first = new WorkflowRuntime<>(registry,
                new SnapshotOnlyStore(backend), 1, 8,
                java.time.Duration.ofSeconds(1), "snapshot-owner-1");
        WorkflowRuntime<Object> second = new WorkflowRuntime<>(registry,
                new SnapshotOnlyStore(backend), 1, 8,
                java.time.Duration.ofSeconds(1), "snapshot-owner-2");
        try {
            assertThat(first.execute("snapshot-dedupe", new Object(),
                    WorkflowSelector.defaultVariant(), new io.aura.contract.CancellationToken(),
                    "event-snapshot").succeeded()).isTrue();

            var duplicate = second.executeAsync("snapshot-dedupe", new Object(), "event-snapshot");
            assertThatThrownBy(() -> duplicate.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.IDEMPOTENCY_RETAINED);
            assertThat(runs).hasValue(1);
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void visibleNonTerminalExecutionIsNeverReturnedAsACompletedDuplicate() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<Object>builder("visible-running")
                .step("never", context -> null).build());
        InMemoryWorkflowExecutionStore store = new InMemoryWorkflowExecutionStore();
        WorkflowExecution running = new WorkflowExecution(
                "visible-running", null, "event-running");
        running.start();
        store.saveSnapshot(running.snapshot());
        store.retainLocal(running);
        WorkflowLease lease = store.tryAcquire("visible-running", "event-running", "owner-1",
                java.time.Duration.ofSeconds(1)).orElseThrow();
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry, store, 1, 8,
                java.time.Duration.ofSeconds(1), "owner-2");
        try {
            var leased = runtime.executeAsync("visible-running", new Object(), "event-running");
            assertThatThrownBy(() -> leased.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.LEASE_UNAVAILABLE);

            store.release(lease);
            var abandoned = runtime.executeAsync("visible-running", new Object(), "event-running");
            assertThatThrownBy(() -> abandoned.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause().isInstanceOf(WorkflowException.class)
                    .extracting("code").isEqualTo(WorkflowException.IDEMPOTENCY_RETAINED);
        } finally {
            store.release(lease);
            runtime.close();
        }
    }

    private static final class BlockingSaveStore implements DurableWorkflowExecutionStore {
        private final InMemoryWorkflowExecutionStore delegate = new InMemoryWorkflowExecutionStore();
        private final java.util.concurrent.CountDownLatch saveEntered = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch allowSave = new java.util.concurrent.CountDownLatch(1);

        private void awaitSave() {
            saveEntered.countDown();
            try {
                if (!allowSave.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("save was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        @Override public void retainLocal(WorkflowExecution execution) {
            delegate.retainLocal(execution);
        }
        @Override public void saveLeased(WorkflowExecutionSnapshot snapshot,
                                         WorkflowLease lease) {
            awaitSave();
            delegate.saveLeased(snapshot, lease);
        }
        @Override public void saveSnapshot(WorkflowExecutionSnapshot snapshot) { delegate.saveSnapshot(snapshot); }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshot(String id) { return delegate.findSnapshot(id); }
        @Override public java.util.List<WorkflowExecutionSnapshot> listSnapshots(String id) { return delegate.listSnapshots(id); }
        @Override public java.util.Optional<WorkflowExecution> find(String id) { return delegate.find(id); }
        @Override public java.util.List<WorkflowExecution> list(String id) { return delegate.list(id); }
        @Override public java.util.Optional<WorkflowExecution> findByIdempotencyKey(String flow, String key) {
            return delegate.findByIdempotencyKey(flow, key);
        }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshotByIdempotencyKey(String flow, String key) {
            return delegate.findSnapshotByIdempotencyKey(flow, key);
        }
        @Override public java.util.Optional<WorkflowLease> tryAcquire(String flow, String key, String owner, java.time.Duration ttl) {
            return delegate.tryAcquire(flow, key, owner, ttl);
        }
        @Override public java.util.Optional<WorkflowLease> renew(WorkflowLease lease, java.time.Duration ttl) {
            return delegate.renew(lease, ttl);
        }
        @Override public void release(WorkflowLease lease) { delegate.release(lease); }
        @Override public java.util.Optional<WorkflowLease> currentLease(String flow, String key) {
            return delegate.currentLease(flow, key);
        }
    }

    private static final class FinalCommitRenewalFailureStore
            implements DurableWorkflowExecutionStore {
        private final InMemoryWorkflowExecutionStore delegate = new InMemoryWorkflowExecutionStore();
        private final java.util.concurrent.CountDownLatch terminalSaved =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch renewalRejected =
                new java.util.concurrent.CountDownLatch(1);

        @Override public void retainLocal(WorkflowExecution execution) { delegate.retainLocal(execution); }
        @Override public void saveLeased(WorkflowExecutionSnapshot snapshot,
                                         WorkflowLease lease) {
            delegate.saveLeased(snapshot, lease);
            if (snapshot.status() == WorkflowExecution.Status.SUCCEEDED) {
                terminalSaved.countDown();
                try {
                    if (!renewalRejected.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("renewal failure was not observed");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
        @Override public void saveSnapshot(WorkflowExecutionSnapshot snapshot) {
            delegate.saveSnapshot(snapshot);
        }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshot(String id) {
            return delegate.findSnapshot(id);
        }
        @Override public java.util.List<WorkflowExecutionSnapshot> listSnapshots(String id) {
            return delegate.listSnapshots(id);
        }
        @Override public java.util.Optional<WorkflowExecution> find(String id) {
            return delegate.find(id);
        }
        @Override public java.util.List<WorkflowExecution> list(String id) {
            return delegate.list(id);
        }
        @Override public java.util.Optional<WorkflowExecution> findByIdempotencyKey(
                String flow, String key) {
            return delegate.findByIdempotencyKey(flow, key);
        }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshotByIdempotencyKey(
                String flow, String key) {
            return delegate.findSnapshotByIdempotencyKey(flow, key);
        }
        @Override public java.util.Optional<WorkflowLease> tryAcquire(
                String flow, String key, String owner, java.time.Duration ttl) {
            return delegate.tryAcquire(flow, key, owner, ttl);
        }
        @Override public java.util.Optional<WorkflowLease> renew(
                WorkflowLease lease, java.time.Duration ttl) {
            if (terminalSaved.getCount() == 0) {
                renewalRejected.countDown();
                return java.util.Optional.empty();
            }
            return delegate.renew(lease, ttl);
        }
        @Override public void release(WorkflowLease lease) { delegate.release(lease); }
        @Override public java.util.Optional<WorkflowLease> currentLease(String flow, String key) {
            return delegate.currentLease(flow, key);
        }
    }

    private static final class CommitThenFailStore implements DurableWorkflowExecutionStore {
        private final InMemoryWorkflowExecutionStore delegate = new InMemoryWorkflowExecutionStore();
        private final java.util.concurrent.atomic.AtomicBoolean failed =
                new java.util.concurrent.atomic.AtomicBoolean();

        @Override public void retainLocal(WorkflowExecution execution) { delegate.retainLocal(execution); }
        @Override public void saveLeased(WorkflowExecutionSnapshot snapshot,
                                         WorkflowLease lease) {
            delegate.saveLeased(snapshot, lease);
            boolean completedStep = snapshot.status() == WorkflowExecution.Status.RUNNING
                    && snapshot.steps().stream().anyMatch(
                            step -> step.status() == StepExecution.Status.SUCCEEDED);
            if (completedStep && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("commit acknowledgement lost");
            }
        }
        @Override public void saveSnapshot(WorkflowExecutionSnapshot snapshot) {
            delegate.saveSnapshot(snapshot);
        }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshot(String id) {
            return delegate.findSnapshot(id);
        }
        @Override public java.util.List<WorkflowExecutionSnapshot> listSnapshots(String id) {
            return delegate.listSnapshots(id);
        }
        @Override public java.util.Optional<WorkflowExecution> find(String id) {
            return delegate.find(id);
        }
        @Override public java.util.List<WorkflowExecution> list(String id) {
            return delegate.list(id);
        }
        @Override public java.util.Optional<WorkflowExecution> findByIdempotencyKey(
                String flow, String key) {
            return delegate.findByIdempotencyKey(flow, key);
        }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshotByIdempotencyKey(
                String flow, String key) {
            return delegate.findSnapshotByIdempotencyKey(flow, key);
        }
        @Override public java.util.Optional<WorkflowLease> tryAcquire(
                String flow, String key, String owner, java.time.Duration ttl) {
            return delegate.tryAcquire(flow, key, owner, ttl);
        }
        @Override public java.util.Optional<WorkflowLease> renew(
                WorkflowLease lease, java.time.Duration ttl) {
            return delegate.renew(lease, ttl);
        }
        @Override public void release(WorkflowLease lease) { delegate.release(lease); }
        @Override public java.util.Optional<WorkflowLease> currentLease(String flow, String key) {
            return delegate.currentLease(flow, key);
        }
    }

    private static final class PerKeyEpochStore implements DurableWorkflowExecutionStore {
        private final InMemoryWorkflowExecutionStore delegate = new InMemoryWorkflowExecutionStore();
        private final java.util.Map<String, WorkflowLease> leases = new java.util.HashMap<>();
        private final AtomicInteger renewals = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean rejectSaves =
                new java.util.concurrent.atomic.AtomicBoolean();

        @Override public void retainLocal(WorkflowExecution execution) { delegate.retainLocal(execution); }
        @Override public synchronized void saveLeased(
                WorkflowExecutionSnapshot snapshot, WorkflowLease lease) {
            if (rejectSaves.get()) {
                throw new WorkflowException(WorkflowException.LEASE_LOST,
                        "Workflow execution lease was lost before persistence");
            }
            WorkflowLease current = current(lease.workflowId(), lease.idempotencyKey());
            if (current == null || !current.token().equals(lease.token())
                    || !current.ownerId().equals(lease.ownerId())) {
                throw new WorkflowException(WorkflowException.LEASE_LOST,
                        "Workflow execution lease was lost before persistence");
            }
            delegate.saveSnapshot(snapshot);
        }
        @Override public void saveSnapshot(WorkflowExecutionSnapshot snapshot) {
            delegate.saveSnapshot(snapshot);
        }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshot(String id) {
            return delegate.findSnapshot(id);
        }
        @Override public java.util.List<WorkflowExecutionSnapshot> listSnapshots(String id) {
            return delegate.listSnapshots(id);
        }
        @Override public java.util.Optional<WorkflowExecution> find(String id) {
            return delegate.find(id);
        }
        @Override public java.util.List<WorkflowExecution> list(String id) {
            return delegate.list(id);
        }
        @Override public java.util.Optional<WorkflowExecution> findByIdempotencyKey(
                String flow, String key) {
            return delegate.findByIdempotencyKey(flow, key);
        }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshotByIdempotencyKey(
                String flow, String key) {
            return delegate.findSnapshotByIdempotencyKey(flow, key);
        }
        @Override public synchronized java.util.Optional<WorkflowLease> tryAcquire(
                String flow, String key, String owner, java.time.Duration ttl) {
            if (current(flow, key) != null) return java.util.Optional.empty();
            WorkflowLease lease = new WorkflowLease(
                    flow, key, owner, "1", java.time.Instant.now().plus(ttl));
            leases.put(leaseKey(flow, key), lease);
            return java.util.Optional.of(lease);
        }
        @Override public synchronized java.util.Optional<WorkflowLease> renew(
                WorkflowLease lease, java.time.Duration ttl) {
            WorkflowLease current = current(lease.workflowId(), lease.idempotencyKey());
            if (current == null || !current.token().equals(lease.token())
                    || !current.ownerId().equals(lease.ownerId())) {
                return java.util.Optional.empty();
            }
            WorkflowLease renewed = new WorkflowLease(
                    lease.workflowId(), lease.idempotencyKey(), lease.ownerId(),
                    lease.token(), java.time.Instant.now().plus(ttl));
            leases.put(leaseKey(lease.workflowId(), lease.idempotencyKey()), renewed);
            renewals.incrementAndGet();
            return java.util.Optional.of(renewed);
        }
        @Override public synchronized void release(WorkflowLease lease) {
            WorkflowLease current = current(lease.workflowId(), lease.idempotencyKey());
            if (current != null && current.token().equals(lease.token())
                    && current.ownerId().equals(lease.ownerId())) {
                leases.remove(leaseKey(lease.workflowId(), lease.idempotencyKey()));
            }
        }
        @Override public synchronized java.util.Optional<WorkflowLease> currentLease(
                String flow, String key) {
            return java.util.Optional.ofNullable(current(flow, key));
        }
        private WorkflowLease current(String flow, String key) {
            String leaseKey = leaseKey(flow, key);
            WorkflowLease current = leases.get(leaseKey);
            if (current != null && current.expiredAt(java.time.Instant.now())) {
                leases.remove(leaseKey);
                return null;
            }
            return current;
        }
        private static String leaseKey(String flow, String key) {
            return flow + "\u0000" + key;
        }
    }

    private static final class SharedSnapshotBackend {
        private final InMemoryWorkflowExecutionStore delegate = new InMemoryWorkflowExecutionStore();
    }

    private static final class SnapshotOnlyStore implements DurableWorkflowExecutionStore {
        private final InMemoryWorkflowExecutionStore delegate;

        private SnapshotOnlyStore(SharedSnapshotBackend backend) {
            this.delegate = backend.delegate;
        }

        @Override public void retainLocal(WorkflowExecution execution) { delegate.retainLocal(execution); }
        @Override public void saveLeased(WorkflowExecutionSnapshot snapshot,
                                         WorkflowLease lease) {
            delegate.saveLeased(snapshot, lease);
        }
        @Override public void saveSnapshot(WorkflowExecutionSnapshot snapshot) { delegate.saveSnapshot(snapshot); }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshot(String id) { return delegate.findSnapshot(id); }
        @Override public java.util.List<WorkflowExecutionSnapshot> listSnapshots(String id) { return delegate.listSnapshots(id); }
        @Override public java.util.Optional<WorkflowExecution> find(String id) { return java.util.Optional.empty(); }
        @Override public java.util.List<WorkflowExecution> list(String id) { return java.util.List.of(); }
        @Override public java.util.Optional<WorkflowExecution> findByIdempotencyKey(String flow, String key) {
            return java.util.Optional.empty();
        }
        @Override public java.util.Optional<WorkflowExecutionSnapshot> findSnapshotByIdempotencyKey(String flow, String key) {
            return delegate.findSnapshotByIdempotencyKey(flow, key);
        }
        @Override public java.util.Optional<WorkflowLease> tryAcquire(String flow, String key, String owner, java.time.Duration ttl) {
            return delegate.tryAcquire(flow, key, owner, ttl);
        }
        @Override public java.util.Optional<WorkflowLease> renew(WorkflowLease lease, java.time.Duration ttl) {
            return delegate.renew(lease, ttl);
        }
        @Override public void release(WorkflowLease lease) { delegate.release(lease); }
        @Override public java.util.Optional<WorkflowLease> currentLease(String flow, String key) {
            return delegate.currentLease(flow, key);
        }
    }
}

package io.aura.workflow;

import org.junit.jupiter.api.Test;
import io.aura.Aura;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.CancellationToken;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        Aura app = Aura.create()
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

        app.prepareModules();
        @SuppressWarnings("unchecked")
        WorkflowRuntime<List<String>> runtime = (WorkflowRuntime<List<String>>) (WorkflowRuntime<?>) app.getBean(WorkflowRuntime.class);
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
            assertThat(runtime.execution(result.execution().executionId())).contains(result.execution());
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
    void synchronousRuntimeExecutionIsInspectableThroughTheSameStore() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(Workflow.<Object>builder("sync-flow")
                .step("one", context -> "done")
                .build());
        WorkflowRuntime<Object> runtime = new WorkflowRuntime<>(registry);
        try {
            WorkflowResult result = runtime.execute("sync-flow", new Object());

            assertThat(runtime.execution(result.execution().executionId())).contains(result.execution());
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
            @Override public void save(WorkflowExecution execution) { }
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

        store.save(first);
        store.save(second);
        store.save(third);

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
        store.save(execution);

        assertThat(store.findSnapshot(execution.executionId())).isPresent();
        assertThat(store.findSnapshot(execution.executionId()).orElseThrow().executionId())
                .isEqualTo(execution.executionId());
    }

    @Test
    void idempotencyKeyDeduplicatesConcurrentAndRetainedExecutions() throws Exception {
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
            WorkflowResult retained = runtime.executeAsync("idempotent", new Object(), "message-42")
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(runs).hasValue(1);
            assertThat(retained.execution().executionId()).isEqualTo(result.execution().executionId());
            assertThat(result.execution().idempotencyKey()).isEqualTo("message-42");
        } finally {
            runtime.close();
        }
    }
}

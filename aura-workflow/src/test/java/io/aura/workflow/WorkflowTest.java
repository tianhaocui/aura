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
}

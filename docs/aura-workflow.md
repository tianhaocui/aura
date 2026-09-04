# Aura Workflow

`aura-workflow` provides typed, explicit process building blocks for Aura applications. A workflow is a named graph of `WorkflowStep<C>` functions operating on one caller-owned context `C`.

The runtime can be installed as a standard Aura module. A business module can then require the shared registry from `ModuleContext` and register its own workflows without an adapter layer:

```java
Aura.create()
    .use(WorkflowModule.create())
    .use(OrderModule.create())
    .start();
```

`OrderModule` declares `requires("workflow")` and calls `context.require(WorkflowRegistry.class)` during installation. Aura validates dependencies and installation order before the HTTP server starts.

## Recommended shape

```java
WorkflowDefinition<OrderContext> workflow = Workflow.<OrderContext>builder("place-order")
    .step("place-order", Orders::place)
    .step("outbound", Orders::outbound)
    .step("deduct-inventory", Orders::deductInventory)
    .before("place-order", "outbound")
    .before("outbound", "deduct-inventory")
    .variant("customer-b", v -> v
        .insertBefore("outbound", "stocktake", Orders::stocktake)
        .insertBefore("outbound", "quality-check", Orders::qualityCheck))
    .build();

WorkflowResult result = workflow.execute(context, WorkflowSelector.by(OrderContext::variant));
```

The default graph is `place-order -> outbound -> deduct-inventory`. A selected variant is a full resolved graph copied from the default and extended with explicit steps and order constraints.

## Runtime guarantees

- Topological sorting produces deterministic insertion-order output when constraints do not distinguish independent steps.
- Missing dependencies, duplicate steps, contradictory order constraints, and dependency cycles fail with `WorkflowException.code()` values such as `MISSING_STEP`, `DUPLICATE_STEP`, `ORDER_CONFLICT`, and `DEPENDENCY_CYCLE`.
- A selector is passed to `execute`; no global static tenant/customer state is consulted.
- Each step has visible `StepExecution` state and attempt count in `WorkflowExecution`.
- `RetryPolicy.none()` is the default. Retry metadata never silently repeats a step outside its configured attempts.
- `StepMode.IDEMPOTENT` and compensation metadata describe business guarantees. The framework does not infer idempotency or replay side effects on its own.

Business modules define what “place”, “outbound”, “stocktake”, or “deduct inventory” mean. This module only composes and observes those operations; it does not own domain transactions, persistence, or external API semantics.

For background work, use the bounded runtime executor and query the execution store:

```java
WorkflowRuntime<OrderContext> runtime = context.require(WorkflowRuntime.class);
CompletableFuture<WorkflowResult> future = runtime.executeAsync(
        "order.fulfillment", orderContext, WorkflowSelector.by(OrderContext::variant));
```

For cooperative cancellation, pass a shared token. The runtime persists the execution as
`PENDING`, `RUNNING`, and finally `SUCCEEDED`, `FAILED`, or `CANCELLED`:

```java
CancellationToken token = new CancellationToken();
CompletableFuture<WorkflowResult> future = runtime.executeAsync(
        "order.fulfillment", orderContext,
        WorkflowSelector.by(OrderContext::variant), token);
token.cancel();
```

The same token overload is available for synchronous `execute(...)` calls.

When cancellation is observed, the workflow and the currently running step are both marked
`CANCELLED`; steps that have not started remain `PENDING`.

Both `WorkflowRuntime.execute(...)` and `executeAsync(...)` persist the same execution state
transitions. Calling `WorkflowDefinition.execute(...)` directly remains a lightweight operation
without a runtime store.

An asynchronous execution can also be cancelled by its execution ID:

```java
String executionId = runtime.executions("order.fulfillment").stream()
        .findFirst().orElseThrow().executionId();
runtime.cancel(executionId);
```

`cancel(...)` returns `false` when the execution is already terminal or unknown. Cancellation is
cooperative, so business steps should honor interruption or inspect their own shared token.

The default store is in-memory for tests and single-process use. Production modules can provide a
`WorkflowExecutionStore` implementation to persist state; distributed workers and resume policies
remain explicit extension responsibilities. Cancellation is cooperative: a running step must
honor interruption or inspect its token through the surrounding context to stop promptly.

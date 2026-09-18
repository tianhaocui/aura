# Aura Workflow

`aura-workflow` provides typed, explicit process building blocks for Aura applications. A workflow is a named graph of `WorkflowStep<C>` functions operating on one caller-owned context `C`.

The runtime can be installed as a standard Aura module. A business module can then require the shared registry from `ModuleContext` and register its own workflows without an adapter layer:

```java
AuraApp.create()
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

The default runtime uses a bounded worker pool and queue. Production modules can choose explicit
limits with `new WorkflowRuntime<>(registry, store, workers, queueCapacity)` and observe
`activeExecutions()`, `queuedExecutions()`, `completedExecutions()`, and
`rejectedExecutions()` for admission and operational checks.

For cooperative cancellation, pass a shared token. The runtime persists the execution as
`PENDING`, `RUNNING`, and finally `SUCCEEDED`, `FAILED`, or `CANCELLED`:

```java
CancellationToken token = new CancellationToken();
CompletableFuture<WorkflowResult> future = runtime.executeAsync(
        "order.fulfillment", orderContext,
        WorkflowSelector.by(OrderContext::variant), token);
token.cancel();
```

`WorkflowRuntime` links the caller token with its own runtime-shutdown token.
Closing the runtime therefore cancels queued or running executions without
mutating a caller-owned token; a workflow step can still inspect the token it
receives through its surrounding context and honor interruption promptly.

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

For message consumers and retried clients, pass an idempotency key to deduplicate concurrent and
retained executions:

```java
CompletableFuture<WorkflowResult> future = runtime.executeAsync(
        "order.fulfillment", orderContext, "order-event-42");
```

The key is scoped to the workflow ID. Concurrent calls share one future. After
that future completes, later calls receive `IDEMPOTENCY_RETAINED` without
running the steps again; `WorkflowException.details().get("executionId")`
identifies the retained snapshot. The default bounded store releases the key
when that snapshot is evicted.

The default in-memory store is bounded to 10,000 execution records and evicts the oldest records
when full. Production modules can provide a
`DurableWorkflowExecutionStore` implementation to persist state and provide
atomic lease operations. Executions with an idempotency key acquire and renew a
lease before business steps run; another runtime receives `LEASE_UNAVAILABLE`
until the current owner persists a visible execution or the lease expires.
Store failures use `STORE_FAILURE`, and lease renewal loss uses `LEASE_LOST`.
Cancellation is cooperative: a running step must honor interruption or inspect
its token through the surrounding context to stop promptly.

A durable adapter also implements `findSnapshotByIdempotencyKey`. After process
restart the original arbitrary Java return value is unavailable. For one
deterministic rule, Aura applies the same behavior in-process: concurrent calls
share the same future, while a later call receives `IDEMPOTENCY_RETAINED` with
the retained execution ID instead of replaying the workflow or pretending the
original value was `null`. Keyed durable executions are inspectable through
`executionSnapshot(...)`; raw step results are not retained by the store.

`saveLeased(snapshot, lease)` is the durable fencing boundary. The adapter must
atomically verify the unexpired token, enforce a unique
`(workflowId, idempotencyKey)` owner, and persist the redacted snapshot in one
transaction. The SPI deliberately does not expose `WorkflowExecution` here, so
adapters cannot accidentally serialize arbitrary step results or raw
exceptions. Renew keeps the token stable; every reacquisition creates a new
token. A stale owner must receive `LEASE_LOST` and must not overwrite the new
owner's state.

Use `WorkflowExecution.snapshot()` when a store needs an immutable, JSON-friendly
projection. It contains lifecycle and step metadata plus stable error codes, but
intentionally excludes arbitrary step results and raw exception messages. Aura
does not automatically replay a persisted execution; recovery must be an explicit,
idempotent business operation.

```java
for (WorkflowRecoveryCandidate candidate : runtime.recoveryCandidates("order.fulfillment")) {
    // Reconcile external side effects before starting a new idempotent operation.
}
```

`recoveryCandidates(...)` reports persisted `PENDING`/`RUNNING` snapshots and
whether another runtime currently holds their lease. It never resumes a step or
reuses arbitrary step results automatically.

Inside a leased business step, `runtime.currentLease().orElseThrow().token()` is
the fencing token for a database or service boundary that can atomically reject
an older token/epoch. It is thread-scoped and cleared before the execution
future becomes terminal. Do not use this token as an upstream idempotency key:
reacquisition deliberately creates a new token. Use a stable business key such
as `(workflowId, idempotencyKey, operation)` for upstream deduplication. If an
upstream only supports ordinary idempotency keys and cannot compare fencing
epochs, the lease token cannot prevent an already accepted request from being
applied twice.

Durable adapters implement `saveSnapshot`, `findSnapshot`,
`findSnapshotByIdempotencyKey`, `listSnapshots`, and
atomic `tryAcquire`, `renew`, `release`, and `saveLeased` methods. `WorkflowRuntime` publishes
the projection for synchronous and asynchronous executions. The optional
`retainLocal(WorkflowExecution)` hook is process-local inspection only and must
never serialize the mutable object; persistent adapters use the snapshot APIs.
`executorMetrics()`, `leaseConflicts()`, and `lostLeases()` expose bounded queue
and ownership health without requiring a metrics SDK.

`WorkflowRegistry.schema()` returns the structured workflow/variant description
for an authorized application route or MCP resource. `WorkflowModule` does not
publish a schema HTTP endpoint automatically; schema exposure must use the same
authentication policy as the rest of the application.

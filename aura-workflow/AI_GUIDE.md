# AI guide

Prefer the builder form:

```java
WorkflowDefinition<C> workflow = Workflow.<C>builder("id")
    .step("first", context -> result)
    .step("second", context -> result, StepMode.IDEMPOTENT, RetryPolicy.of(2))
    .before("first", "second")
    .variant("tenant-x", v -> v.insertAfter("first", "extra", context -> null))
    .build();
```

Use `WorkflowSelector.by(context -> variant)` for tenant/customer selection. Do not read a global tenant variable. A missing step, duplicate step, cycle, contradictory order, or unknown variant raises `WorkflowException`; inspect `code()` for a stable machine-readable code.

Retries are per-step metadata and default to one attempt. The runtime retries only according to `RetryPolicy`; `StepMode.IDEMPOTENT` documents that repeating the operation is acceptable but does not make a non-idempotent operation safe. Compensation is explicit with `StepMode.COMPENSATABLE` and `variant.compensate(...)`, and runs in reverse completion order after a later step fails.

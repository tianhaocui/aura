# aura-workflow

`aura-workflow` is a small, explicit Java workflow orchestrator. It models a workflow as typed context plus named steps, then resolves a deterministic dependency order at runtime.

```java
WorkflowDefinition<OrderContext> placeOrder = Workflow.<OrderContext>builder("place-order")
    .step("place-order", Orders::place)
    .step("outbound", Orders::outbound)
    .step("deduct-inventory", Orders::deductInventory)
    .before("place-order", "outbound")
    .before("outbound", "deduct-inventory")
    .variant("customer-b", v -> v
        .insertBefore("outbound", "stocktake", Orders::stocktake)
        .insertBefore("outbound", "quality-check", Orders::qualityCheck))
    .build();

WorkflowResult result = placeOrder.execute(context, WorkflowSelector.by(OrderContext::customerVariant));
```

The framework owns orchestration concerns only: dependency validation, variant selection, execution state, retries, and optional compensation. Business meaning, persistence, transactions, and external side effects remain in the business module.

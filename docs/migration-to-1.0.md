# Migrating to Aura 1.0

Aura 1.0 makes explicit module composition the recommended application model. The release allows
breaking changes so that one capability has one discoverable API and one verification path.

## Entry point

Use `AuraApp` for new applications:

```java
AuraApp app = AuraApp.create()
        .use(WorkflowModule.create())
        .use(new OrderModule())
        .start();
```

The old `Aura` builder remains an implementation and migration escape hatch. New modules must
implement `AuraModule`, declare a stable descriptor, and obtain dependencies from `ModuleContext`.
Do not add new code that reaches through `AuraApp.runtime()`.

Adapters should depend on the replaceable `AuraRuntime` surface and its stable `RuntimeState`
values rather than on the legacy Builder implementation.

## API mapping

| Before | Aura 1.0 |
|---|---|
| `Aura.create().plugin(...)` | `AuraApp.create().use(capabilityModule)` |
| global/static service lookup | `context.require(Type.class)` or `context.find(Type.class)` |
| implicit package discovery | explicit `.use(new BusinessModule())` |
| numeric workflow positions | named steps plus `.before(...)` / `.after(...)` |
| customer-specific branching in handlers | `WorkflowDefinition.variant(...)` and `WorkflowSelector` |
| ad-hoc API descriptions | shared `ApiRoute` / `ApiParameter` metadata |
| external HTTP tests | `TestClient.of(app)` plus real Undertow smoke tests |
| string-parsed errors | stable error `code`, field, reason, and remediation details |

## Module contract

Each module owns one boundary:

```java
public final class OrderModule implements AuraModule {
    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("orders", "1.0.0")
                .requires("workflow")
                .provides("orders.api");
    }

    @Override public void install(ModuleContext context) {
        WorkflowRegistry workflows = context.require(WorkflowRegistry.class);
        context.routes(routes -> routes.get("/orders", request -> request.text("ok")));
    }
}
```

`install(...)` registers deterministic contributions. `start(...)` opens external resources and
`stop(...)` releases them. Dependencies are checked and topologically ordered before installation.

## Configuration and permissions

Module properties are scoped to `aura.<module-id>.*` and can be bound to a record:

```java
OrderConfig config = context.config(OrderConfig.class);
```

Permissions are registered once through `context.permissions()`. Routes use the stable permission
middleware and the application supplies the authorization function.

## Workflow migration

Replace positional or customer-specific handler branches with a named graph:

```java
Workflow.<OrderContext>builder("order.fulfillment")
        .step("place-order", Orders::place)
        .step("outbound", Orders::outbound)
        .step("deduct-inventory", Orders::deductInventory)
        .before("place-order", "outbound")
        .before("outbound", "deduct-inventory")
        .variant("quality", flow -> flow
                .insertBefore("outbound", "stocktake", Orders::stocktake)
                .insertBefore("outbound", "quality-check", Orders::qualityCheck))
        .build();
```

Use `WorkflowRuntime` when execution status, persistence, bounded background execution, or
cancellation is required. Supply a durable `WorkflowExecutionStore` before relying on restart
recovery; the default in-memory store is for tests and single-process applications.

## Verification checklist

1. Run `app.prepare()` or `TestClient.of(app)` to validate module installation without opening a listener.
2. Assert route behavior and structured errors with `TestClient`.
3. Inspect `/__schema__`; enable `/openapi.json` explicitly in production.
4. Run `mvn test`, `mvn -DskipTests package`, and `bash scripts/verify-aura-contracts.sh`.
5. Keep provider, database, MCP transport, TLS, metrics, and migration implementations in optional modules.

The migration is intentionally direct: remove deprecated API usage rather than adding an adapter
layer that hides which module owns a capability.

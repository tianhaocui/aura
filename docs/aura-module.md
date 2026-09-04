# Aura Modules

Aura 1.0 modules are explicit Maven-installed building blocks. A module has one public `AuraModule` entry point and contributes behavior through `ModuleContext`.

```java
public final class OrderModule implements AuraModule {
    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("order", "1.0.0")
                .requires("workflow")
                .provides("order.api", "order.workflow")
                .build();
    }

    @Override public void install(ModuleContext context) {
        context.routes(routes -> routes.get("/orders", ctx -> ctx.text("ok")));
        context.register(new OrderService());
    }
}

AuraApp.create()
        .use(WorkflowModule.create())
        .use(DatabaseModule.create(db))
        .use(AiModule.create(provider))
        .use(McpModule.create(mcpRouter))
        .use(new OrderModule())
        .start();
```

## Rules

- `descriptor().id()` is stable and unique within one application.
- `descriptor().provides(...)` documents the capabilities contributed by the module for diagnostics and tooling.
- The shared `ApiRoute` / `ApiParameter` contract is the source for schema adapters, including the dependency-free `OpenApiGenerator` in `aura-web`.
- Dependencies are declared with `requires(...)`; Aura checks missing modules and cycles before starting the server.
- `install(...)` only registers services, routes and capabilities. `start(...)` opens external resources; `stop(...)` releases them.
- Module properties must use `aura.<module-id>.*` and are read through `context.property(...)`.
- `TestClient.of(app)` runs module installation but does not start external resources, so module routes can be tested in memory.
- `aura-db` provides `DatabaseModule.create(db)`. A business module can declare `requires("db")` and obtain the same `Db` instance with `context.require(Db.class)`.
- New applications should use `AuraApp`; the older `Aura` builder is an implementation detail and is not the recommended module authoring API.
- `AiModule` contributes an `AiClient`; `McpModule` contributes an `McpRouter`. They do not silently open network listeners. Use the application's explicit MCP transport flag or configuration when starting.
- Do not use global registries, static tenant state, reflection-based module discovery, or numeric step positions.

## Business workflow extensions

Business modules may register a standard flow and add customer variants with `aura-workflow`:

```java
WorkflowRegistry registry = context.require(WorkflowRegistry.class);
registry.register(Workflow.<OrderContext>builder("order.fulfillment")
        .step("place-order", Orders::place)
        .step("outbound", Orders::outbound)
        .step("deduct-inventory", Orders::deductInventory)
        .before("place-order", "outbound")
        .before("outbound", "deduct-inventory")
        .variant("quality-fulfillment", flow -> flow
                .insertBefore("outbound", "stocktake", Orders::stocktake)
                .insertBefore("outbound", "quality-check", Orders::qualityCheck))
        .build());
```

The framework owns ordering, validation, retry metadata, execution state and explicit compensation. The business module owns the meaning and side effects of each step.

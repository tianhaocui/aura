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
        context.route(new RouteContribution("GET", "/orders",
                (BaseHandler) ctx -> ctx.text("ok")));
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
- Lambda routes can declare parameters explicitly when reflection cannot infer them:

```java
routes.post("/orders", handler)
        .param("customerId", "string", "query", true, "Customer identifier")
        .param("order", "Order", "body", true, "Order payload");
```

  These declarations feed `/__schema__`, OpenAPI, and other adapters from the same contract.
- New transport-neutral modules contribute `RouteContribution` values through `context.route(...)`;
  the Web adapter executes the handler and preserves the same metadata. The older
  Web-specific `ModuleContext` route/middleware methods are not part of the 1.0 contract.
- Dependencies are declared with `requires(...)`; Aura checks missing modules and cycles before starting the server.
- `install(...)` only registers services, routes and capabilities. `start(...)` opens external resources; `stop(...)` releases them.
- Module properties must use `aura.<module-id>.*` and are read through `context.property(...)`.
- Record configuration is bound with `context.config(OrderConfig.class)`, using only the current module's `aura.<module-id>.*` prefix.
- Permissions are declared through the shared `context.permissions()` registry; duplicate IDs fail during module installation.
- The application supplies one authorization function and routes use stable permission middleware:

```java
AuraApp.create()
        .authorize((userId, permission) -> acl.allows(userId, permission))
        .authenticate(ctx -> tokenService.userId(ctx.header("Authorization")))
        .use(new OrderModule())
        .start();
```

Web runtime settings belong to the same central configuration passed to `AuraApp`:

```java
AuraConfig config = new AuraConfig()
        .cors(cors -> cors.origins("https://console.example").credentials(true))
        .gzip(true)
        .accessLog("json");
AuraApp.create(config).use(new OrderModule()).start();
```

`AuraConfig` is the build-time fluent object. Once an application is created,
`app.config()` and `runtime.config()` expose an immutable `AuraConfigSnapshot`;
changing the original builder or a returned nested CORS/JSON/Snapshot object
does not change the runtime.

Use `HealthModule.create()` when an application needs the standard health
contract. It contributes `GET /health/live`, `GET /health/ready`, and
`GET /health`; liveness reports whether shutdown has begun, readiness requires
the runtime to be `RUNNING` and all registered checks to be `UP`, and the
aggregate endpoint reports dependency health without exposing check errors.

Inside a route group, use `Aura.requirePermission("orders.read")` after `Aura.requireAuth()`.
- `TestClient.of(app)` runs module installation but does not start external resources, so module routes can be tested in memory.
- `healthCheck(...)` registrations are available through `AuraWebRuntime.healthChecks()` and the
  fail-closed `healthStatus()` aggregate for readiness adapters.
- For application-level capability lookup, call `app.prepare()` followed by `app.find(Type.class)` or `app.require(Type.class)`; use `app.runtimeView()` when an adapter needs the stable runtime contract.
- `aura-db` provides `DatabaseModule.create(db)`. A business module can declare `requires("db")` and obtain the same `Db` instance with `context.require(Db.class)`.
- New applications should use `AuraApp`; the older `Aura` builder is an implementation detail and is not the recommended module authoring API.
- `AiModule` contributes an `AiClient`; `McpModule` contributes an `McpRouter`. They do not silently open network listeners. Use the application's explicit MCP transport flag or configuration when starting.
- Do not use global registries, static tenant state, reflection-based module discovery, or numeric step positions.

Snapshot storage is bounded by default. Configure `maxSnapshots(...)`, `maxStorageSize(...)`, and
`retentionTtl(...)` when needed; dropped snapshots are counted and can be observed with `onDrop(...)`.

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

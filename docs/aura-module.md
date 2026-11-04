# Aura Modules

Aura 1.0 modules are explicit Maven-installed building blocks. A module has one public `AuraModule` entry point and contributes behavior through `ModuleContext`.

<!-- aura:compile name=module-order-entry -->
```java
package docs.example;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ApiParameter;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.Route;
import io.aura.port.BaseHandler;

import java.util.List;

public final class OrderModule implements AuraModule {
    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("order", "1.0.0")
                .requires("workflow")
                .provides("order.api", "order.workflow");
    }

    @Override public void install(ModuleContext context) {
        context.register(new OrderService());
        context.route(Route.of("GET", "/orders", (BaseHandler) ctx -> ctx.text("ok"))
                .withParams(List.of(
                        new ApiParameter("customerId", "String", "query", true,
                                "Customer identifier"),
                        new ApiParameter("order", "Order", "body", true,
                                "Order payload"))));
    }

    public static void main(String[] args) {
        AuraApp.create(new AuraConfig().port(8080))
                .use(new OrderModule())
                .start();
    }

    static final class OrderService { }
}
```

The same blocks install together: each adapter module is a Maven dependency and an
explicit `use(...)`, and none of them opens a listener the application did not ask
for.

<!-- aura:compile name=module-composed-application -->
```java
package docs.example;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.ai.AiModule;
import io.aura.ai.FakeAiProvider;
import io.aura.contract.ModuleMetadata;
import io.aura.db.DatabaseModule;
import io.aura.db.Db;
import io.aura.mcp.McpModule;
import io.aura.workflow.WorkflowModule;

public final class ComposedApplication {
    private ComposedApplication() { }

    public static AuraApp create() {
        Db db = Db.create("jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1", "sa", "");
        return AuraApp.create(new AuraConfig().port(8080))
                .authorize((userId, permission) -> "operator".equals(userId)
                        && "orders.read".equals(permission))
                .authenticate(ctx -> ctx.header("Authorization"))
                .use(WorkflowModule.create())
                .use(DatabaseModule.create(db))
                .use(AiModule.create(FakeAiProvider.returning("ok")))
                .use(McpModule.create())
                .use(new Orders())
                .start();
    }

    private static final class Orders implements AuraModule {
        @Override public ModuleMetadata descriptor() {
            return ModuleMetadata.of("orders", "1.0.0").requires("workflow");
        }

        @Override public void install(ModuleContext context) { }
    }
}
```

## Rules

- `descriptor().id()` is stable and unique within one application.
- `descriptor().provides(...)` documents the capabilities contributed by the module for diagnostics and tooling.
- `Route` / `ApiParameter` are the source for every adapter, including the dependency-free `OpenApiGenerator` in `aura-web`.
- A lambda route declares the parameters the compiler cannot see:

```java
context.route(Route.of("POST", "/orders", handler)
        .withParams(List.of(
                new ApiParameter("customerId", "String", "query", true,
                        "Customer identifier"),
                new ApiParameter("order", "Order", "body", true,
                        "Order payload"))));
```

  These declarations feed `/__schema__`, OpenAPI, and other adapters from the same contract.
- New transport-neutral modules contribute `Route` values through
  `context.route(...)`; the Web adapter executes the handler and preserves the
  same metadata. `context.before/after/exception/resultWrapper` and
  `context.webSocket(...)` are explicit optional Web policy contributions.
- Dependencies are declared with `requires(...)`; Aura checks missing modules and cycles before starting the server.
- Modular startup rejects service methods compiled without Java parameter names with
  `AURA_PARAMETER_NAMES_MISSING`; compile application modules with Maven's
  `<parameters>true</parameters>` setting so binding and generated schemas agree.
- `install(...)` only registers services, routes and capabilities. `start(...)` opens external resources; `stop(...)` releases them.
- Periodic work is a contribution too: `context.cron("0 9 * * *", task)` is the only
  scheduling door, and the runtime owns the thread that runs it.
- Module properties must use `aura.<module-id>.*` and are read through `context.property(...)`.
- Record configuration is bound with `context.config(OrderConfig.class)`, using only the current module's `aura.<module-id>.*` prefix.
  Values resolve with the same precedence as `context.property(...)`: environment,
  system property, then explicit application properties; missing fields use record
  type defaults.
- Permissions are declared through the shared `context.permissions()` registry; duplicate IDs fail during module installation.
- The application supplies one authorization function and routes use stable permission middleware:

```java
AuraApp.create()
        .authorize((userId, permission) -> acl.allows(userId, permission))
        .authenticate(ctx -> tokenService.userId(ctx.header("Authorization")))
        .use(new OrderModule())
        .start();
```

Protect individual routes with the same metadata used by HTTP and AI adapters:

```java
context.route(Route.of("GET", "/orders", handler)
        .requireAuth("orders.read"));
context.route(Route.of("GET", "/profile", profileHandler)
        .requireAuth());
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
Application properties follow the same lifecycle boundary: configure them with
`AuraApp.property(...)` before `prepare()` or `start()`. Changes after module
preparation are rejected so module behavior cannot change nondeterministically.

Use the Web adapter's `io.aura.web.HealthModule.create()` when an application
needs the standard health contract. Health probes read runtime state, so the
module lives with the adapter rather than in `aura-core`. It contributes `GET /health/live`, `GET /health/ready`, and
`GET /health`; liveness reports whether shutdown has begun, readiness requires
the runtime to be `RUNNING` and all registered checks to be `UP`, and the
aggregate endpoint reports dependency health without exposing check errors.

Health probes are bounded by `AuraConfig.healthCheckTimeout()` (default one
second, configurable from `aura.health-check-timeout`) and fail closed when a
probe times out.

HTTP failures use a stable JSON contract. `ApiException` responses contain
`error`, `code`, and optional `field`, `reason`, and `hint`; validation responses
use `code: "VALIDATION_ERROR"` and expose those same fields for each invalid
property. Clients should branch on `code` and `field`, not parse the human
message.

- `TestClient.of(app)` runs module installation but does not start external resources, so module routes can be tested in memory.
- `TestClient.schema()` and `TestClient.openApi()` expose the same generated
  route documents as `/__schema__` and `/openapi.json`, which lets contract tests
  run without opening a listener.
- `healthCheck(...)` registrations are available through `AuraWebRuntime.healthChecks()` and the
  fail-closed `healthStatus()` aggregate for readiness adapters.
- For application-level capability lookup, call `app.prepare()` followed by
  `app.find(Type.class)` or `app.require(Type.class)`. Named capabilities use
  `context.register("audit", value)` and `context.require("audit", Type.class)`.
  Use `app.runtimeView()` when an adapter needs the stable runtime contract.
- `aura-db` provides `DatabaseModule.create(db)`. A business module can declare `requires("db")` and obtain the same `Db` instance with `context.require(Db.class)`.
- `AuraApp` is the only application entry point in 1.0.
- The optional `aura-processor` emits direct route code for `@Path` services,
  including the get/list/create/update/delete convention. There is no
  reflection-based route registration; contribute each route once, generated or
  handwritten.
- Request bodies may be concrete records/POJOs or parameterized collections.
  Use `RequestType.parameterized(List.class, Item.class)` for a generic body;
  empty bodies remain nullable unless the handler rejects them.
- `AiModule` contributes an `AiClient`; `McpModule` contributes an `McpRouter`.
  They do not silently open network listeners. Enable MCP transport explicitly
  with `McpModule.create(router).http()` and optionally `.sse()`.
- Do not use global registries, static tenant state, reflection-based module discovery, or numeric step positions.

Snapshot storage is bounded by default. Configure `maxSnapshots(...)`, `maxStorageSize(...)`, and
`retentionTtl(...)` when needed; dropped snapshots are counted and can be observed with `onDrop(...)`.

## Scheduled work

`context.cron(expression, task)` is the only scheduling door. A module contributes
its timers during `install`, the runtime arms them when it starts, and the pool
that holds them shuts down when the runtime stops, so no module builds a
`ScheduledExecutorService` of its own.

<!-- aura:compile name=module-cron-reminders -->
```java
package docs.example;

import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;

public final class ReminderModule implements AuraModule {
    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("reminder", "1.0.0");
    }

    @Override public void install(ModuleContext context) {
        context.cron("0 9 * * *", ReminderModule::checkOverdue);
        context.cron("*/5 * * * *", ReminderModule::warnLowStock);
    }

    static void checkOverdue() { }

    static void warnLowStock() { }
}
```

The expression is the standard five-field crontab subset:
`minute hour day-of-month month day-of-week`, with `*`, lists (`1,15`), ranges
(`8-10`) and steps (`*/5`, `5-40/10`, `20/5`). Day of week accepts 0 and 7 for
Sunday, and when both day-of-month and day-of-week name days a day matches
either one, which is what a crontab line does. Fire times are wall-clock times in
the runtime's zone: `0 9 * * *` means 09:00 local, and a fire time inside a
daylight-saving gap runs at the shifted time rather than being skipped.

Failures are coded and named for the contribution that caused them:

| Code | When |
| --- | --- |
| `AURA_CRON_CONTRIBUTION_INVALID` | the expression or the task is missing |
| `AURA_CRON_EXPRESSION_INVALID` | the expression is not five fields, a field is out of range, or the days and months it selects can never meet |
| `AURA_CRON_CONTRIBUTION_TOO_LATE` | the task was contributed after the runtime armed its schedule |
| `AURA_CRON_TASK_FAILED` | the task threw; reported as a `runtime.cron.failure` event and the next occurrence stays armed |

A task that throws does not end its schedule, and a task never runs concurrently
with itself: it is re-armed from the clock reading taken after the previous run
finished, so a slow run skips the occurrences it missed instead of catching up on
them. Different tasks do run at the same time, because the pool is sized to the
number of contributions.

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
Asynchronous workflow executions receive an ID before queue admission, so a
queued execution can be cancelled and persisted as `CANCELLED` without
running its steps.

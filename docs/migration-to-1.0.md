# Migrating to Aura 1.0

Aura 1.0 makes explicit module composition the recommended application model. The release allows
breaking changes so that one capability has one discoverable API and one verification path.

Import `aura-bom` once and replace a direct `aura-web` dependency with
`aura-starter`. Optional Aura modules then omit individual versions. New
projects can be generated from `aura-archetype`; its generated test uses
`TestClient` before the real server is started.

## Entry point

Use `AuraApp` for new applications:

```java
AuraApp app = AuraApp.create()
        .use(WorkflowModule.create())
        .use(new OrderModule())
        .start();
```

The old `Aura` builder, `AuraPlugin`, `Reloadable`, and `BeforeBuilder` APIs are
removed in 1.0. This is an intentional major-version compile break. New modules
implement `AuraModule`, declare a stable descriptor, and obtain dependencies
from `ModuleContext`. Replace old construction directly with
`AuraApp.create().use(...)`; do not add a compatibility adapter.

Adapters should depend on the replaceable `AuraRuntime` surface and its stable `RuntimeState`
values rather than on an application facade implementation.

From an application, obtain that surface with `app.runtimeView()` and use `isRunning()` or
`state()` for lifecycle checks.
The returned runtime is an adapter-owned implementation of the stable contract; applications and
transport adapters should not cast it back to a concrete application facade.

The in-memory verification adapter accepts the same seam:

```java
TestClient.of(app).get("/orders").expect(200);
```

## API mapping

| Before | Aura 1.0 |
|---|---|
| `Aura.create().plugin(...)` | `AuraApp.create().use(capabilityModule)` |
| global/static service lookup | `context.require(Type.class)` or `context.find(Type.class)` |
| `Aura.auth(...)` | `AuraApp.authenticate(...)` |
| implicit package discovery | explicit `.use(new BusinessModule())` |
| numeric workflow positions | named steps plus `.before(...)` / `.after(...)` |
| customer-specific branching in handlers | `WorkflowDefinition.variant(...)` and `WorkflowSelector` |
| ad-hoc API descriptions | shared `ApiRoute` / `ApiParameter` metadata |
| external HTTP tests | `TestClient.of(app)` plus real Undertow smoke tests |
| string-parsed errors | stable error `code`, field, reason, and remediation details |
| unordered `Query.paginate(page, size)` | `.orderBy(..., uniqueTieBreaker).paginate(page, size, strategy)` |
| raw `Db.paginate(sql, params, page, size)` | `Db.paginate(sql, params, PaginationOrder, page, size, TotalStrategy)` |
| `Page.total(): long` | `Page.total(): OptionalLong` |
| `Page.totalPages(): int` | `Page.totalPages(): OptionalInt` |
| provider calls `AiRequest.redactor()` repeatedly | provider reads frozen `redactedPrompt()`, `redactedSystemPrompt()`, `redactedParameters()`, and `redactedTools()` |
| `McpSessionManager.create(userId)` | `create(userId, protocolVersion)`, then `activate(...)` after `notifications/initialized` |
| `db.migrate(source)` / `DatabaseModule.migrations(...)` | `DatabaseModule.create(db).migrateWith(SchemaMigrator)` |

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
        context.route(new RouteContribution("GET", "/orders",
                (BaseHandler) request -> request.text("ok")));
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
cancellation is required. Supply a `DurableWorkflowExecutionStore` before relying on restart
recovery. Durable adapters persist only `WorkflowExecutionSnapshot`; `retainLocal` is an optional
process-local inspection hook and must never serialize the mutable execution. The default
in-memory store is for tests and single-process applications.

## Database pagination

Aura 1.0 refuses unordered pagination. Add an explicit stable order and finish
with a unique tie-breaker so rows with equal business sort values cannot move
between pages:

```java
Page<Row> page = db.table("orders")
        .where("customer_id", customerId)
        .orderBy("created_at DESC", "id ASC")
        .paginate(pageNum, pageSize, TotalStrategy.EXACT);
```

Raw and dynamic SQL pass ordering separately with `PaginationOrder.by(...)`.
`TotalStrategy.EXACT` executes a count query. `TotalStrategy.NONE` skips it,
fetches one additional row to compute `hasNext()`, returns
`OptionalLong.empty()` from `total()`, and serializes the unknown total as JSON
`null`. Do not substitute `0`; that means an exact empty result.

## Database migrations

Migration execution is now a module lifecycle concern, not a utility method on
`Db`. For Aura's lightweight SQL implementation use:

```java
DatabaseModule.create(db)
        .migrateWith(SqlSchemaMigrator.from(source));
```

Production applications can install `aura-db-migration-flyway` and use
`FlywaySchemaMigrator.classpath("db/migration")`, or install
`aura-db-migration-liquibase` and use
`LiquibaseSchemaMigrator.classpath("db/changelog.xml")`. Implement
`SchemaMigrator` only for another engine. Adapters receive a `MigrationContext`
and return a `SchemaMigrationResult`; they must not close the Aura-owned `Db` or
`DataSource`. `SqlMigrationSource` is only an input to `SqlSchemaMigrator` and
is no longer an external engine SPI.

The Aura Flyway adapter includes PostgreSQL and MySQL/MariaDB database-type
plugins; applications still provide their JDBC driver. Other databases require
their matching Flyway plugin. Aura's Liquibase adapter uses the Apache-2.0
licensed Liquibase 4.33 line.

## AI request transport

Aura 1.0 removes `AiRequest.redactor()`. Redaction now runs once while building
the immutable request so input-budget accounting and provider transport observe
the same values. Custom providers must read the frozen `redacted*` accessors;
they must not transform request inputs again. This is an intentional 1.0 source
and binary break for pre-release provider implementations.

## MCP HTTP sessions

Aura 1.0 models the protocol handshake explicitly. An initialize response creates
a `NEGOTIATING` session; clients must send a standalone, uncorrelated
`notifications/initialized` before tools, resources, prompts, batch requests, or
SSE are admitted. Adapter code that directly creates sessions must supply the
negotiated version and perform the same activation transition. Session close is
linearized with request admission, so no new business call starts after close.

## Verification checklist

1. Run `app.prepare()` or `TestClient.of(app)` to validate module installation without opening a listener.
2. Assert route behavior and structured errors with `TestClient`.
3. Inspect `/__schema__`; enable `/openapi.json` explicitly in production.
4. Run `mvn test`, `mvn -DskipTests package`, and `bash scripts/verify-aura-contracts.sh`.
5. Keep provider, database, MCP transport, TLS, metrics, and migration implementations in optional modules.

The migration is intentionally direct: remove deprecated API usage rather than adding an adapter
layer that hides which module owns a capability.

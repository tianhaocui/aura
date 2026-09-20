# Aura 1.0 AI Development Guide

Aura is a Java 17 backend framework with one deterministic development loop:

```text
AI writes a module -> Maven compiles it -> TestClient verifies it -> Schema/MCP describes it
```

Stability is the first priority. The API choices below are the only recommended
authoring path for new applications. The pre-1.0 guide is retained at
`docs/legacy-ai-guide-0.6.md` only for migration reference.
The legacy `Aura` builder is deprecated for removal; do not generate new code
with it.

## Maven Setup

```xml
<dependency>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-web</artifactId>
    <version>1.0.0</version>
</dependency>
```

Add `aura-db`, `aura-ai`, `aura-mcp`, or `aura-workflow` only when needed.
Every application module must compile with preserved Java parameter names:

```xml
<parameters>true</parameters>
```

## Compose Modules

```java
AuraApp app = AuraApp.create()
        .use(HealthModule.create())
        .use(DatabaseModule.create(db))
        .use(new OrderModule())
        .start();
```

```java
public final class OrderModule implements AuraModule {
    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("orders", "1.0.0")
                .requires("db")
                .provides("orders.api");
    }

    @Override public void install(ModuleContext context) {
        Db db = context.require(Db.class);
        context.route(new RouteContribution("GET", "/orders",
                (BaseHandler) ctx -> ctx.json(db.find("SELECT * FROM orders"))));
    }
}
```

`descriptor()` declares identity, version, dependencies, and capabilities.
`install()` registers routes, services, permissions, and checks. `start()` and
`stop()` own external resources. Missing dependencies, cycles, duplicate IDs,
and duplicate routes fail before the server starts.

Use `context.require(Type.class)` for required capabilities and
`context.find(Type.class)` for optional capabilities. Do not use global
registries, reflection-based module discovery, or numeric workflow positions.

## Generated Routes

For production services with route annotations, add the optional
`aura-processor` annotation processor. It emits an inspectable
`<ServiceName>AuraRoutes` contributor with direct method calls and fixed
parameter binding. Reflection registration remains useful for prototypes; do
not combine both registrations for the same service.

```xml
<dependency>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-processor</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.github.tianhaocui</groupId>
                <artifactId>aura-processor</artifactId>
                <version>1.0.0</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

```java
@Path("/orders")
public final class OrderService {
    @Get("/{id}")
    public Order get(long id) { return orders.get(id); }

    @Post("")
    public Order create(CreateOrder request) { return orders.create(request); }
}

public final class OrderModule implements AuraModule {
    @Override public void install(ModuleContext context) {
        OrderService service = context.require(OrderService.class);
        new OrderServiceAuraRoutes(service).routes().forEach(context::route);
    }
}
```

Generated sources are regular Java under Maven's generated-sources directory.
Compile them, inspect them, and verify their routes through `TestClient` like
any handwritten module code.

For annotated services, the processor also preserves the convention routes
get/list/create/update/delete: GET /orders/{id}, GET /orders, POST /orders,
PUT /orders/{id}, and DELETE /orders/{id}. Mark the service with `@Path` so
generation is explicit; do not register the same service through both
reflection and generated routes.

## Configuration

```java
AuraConfig config = new AuraConfig()
        .port(8080)
        .workers(100)
        .maxBodySize(10 * 1024 * 1024L)
        .maxUploadSize(1024 * 1024L)
        .healthCheckTimeout(1)
        .securityHeaders(true)
        .openApi(true);

AuraApp app = AuraApp.create(config)
        .property("aura.orders.timeout", "5")
        .use(new OrderModule());
```

For deployment files, use `AuraConfig.fromProperties(java.nio.file.Path.of("aura.properties"))`.
Framework keys use the `aura.*` namespace; environment variables and JVM system
properties override file values. Invalid values fail while creating the config,
before modules or listeners are started.

The equivalent application entry point is `AuraApp.create(Path.of("aura.properties"))`
or `AuraApp.create(properties)`. Configuration is frozen when the app is created;
do not load or mutate configuration after module preparation.

Call `start()` after all modules and properties are configured. `app.config()`
returns an immutable `AuraConfigSnapshot`; changing the builder or nested
configuration afterwards has no effect. Property changes after `prepare()` are
rejected. Module properties use `aura.<module-id>.*` and resolve in this order:

```text
environment variable -> system property -> AuraApp.property(...)
```

Prefer record binding inside a module:

```java
record OrderConfig(int timeout, boolean audit) {}
OrderConfig cfg = context.config(OrderConfig.class);
```

## Routes and Errors

```java
context.route(new RouteContribution("GET", "/orders/{id}",
        (BaseHandler) ctx -> ctx.json(orderService.get(ctx.pathLong("id")))));
```

For lambda routes, declare metadata explicitly when reflection cannot infer it:

```java
context.route(new RouteContribution("GET", "/orders/{id}", handler, Map.of(
        "description", "Get one order",
        "parameters", List.of(new ApiParameter(
                "id", "long", "path", true, "Order identifier"))))
        .permission("orders.read"));
```

Binding is fixed: `int`/`long`/`String` use path then query parameters;
records/POJOs use JSON bodies; concrete `List<T>` and `Map<K,V>` parameters
also use JSON bodies; non-void results become JSON responses. Empty bodies
remain nullable unless the business handler rejects them explicitly. Use
`ctx.bodyOrThrow(Type.class)` for a required validated body, and
`ctx.body(RequestType.parameterized(List.class, Order.class))` when a generic
body type is needed. The generated processor emits the same `RequestType`
expression automatically for concrete type arguments.

Clients must branch on stable error fields, not exception text:

```json
{"error":"Validation failed","code":"VALIDATION_ERROR",
 "field":"email","reason":"must be valid","hint":"Provide a valid email"}
```

Register permissions through `context.permissions()`. Configure authentication
and authorization explicitly on `AuraApp`:

```java
AuraApp.create()
        .authenticate(ctx -> tokenService.userId(ctx.header("Authorization")))
        .authorize((user, permission) -> acl.allows(user, permission))
        .use(new OrderModule())
        .start();
```

## Verify with TestClient

`TestClient` installs modules without opening a listener and uses the same route
assembly, parameter binding, middleware, and error semantics as Undertow.

```java
AuraApp app = AuraApp.create().use(new OrderModule());
TestClient test = TestClient.of(app);

test.get("/orders/1").expect(200);
test.post("/orders").body(order).expect(201);
test.get("/missing").expect(404);

Map<String, Object> schema = test.schema();
Map<String, Object> openApi = test.openApi();
```

The same contract is served at `/__schema__` and `/openapi.json`. Run the
verification gate after changes:

```bash
mvn -q test
mvn -q -DskipTests package
bash scripts/verify-aura-contracts.sh
```

Route metadata includes both request body fields and the JSON response shape
when a service method returns a record/POJO. AI clients should use these fields
instead of inferring response properties from example strings.

## Database

Use Query Builder for simple CRUD and keep values bound as parameters:

```java
List<Row> rows = db.table("users")
        .where("active", true)
        .orderBy("id")
        .find();
```

Pass `ctx.cancellationToken()` to downstream work:

```java
List<Row> rows = db.table("users").find(ctx.cancellationToken());
int changed = db.executeCancellable(
        "UPDATE users SET active = ? WHERE id = ?",
        ctx.cancellationToken(), true, id);
```

Database calls validate identifiers, bound response work, and cancel JDBC
statements cooperatively. The default page-size maximum is 500.

Use one explicit schema migration executor during startup:

```java
DatabaseModule database = DatabaseModule.create(db)
        .migrateWith(SqlSchemaMigrator.classpath("db/migration"));
```

For production, install exactly one adapter and replace only the migrator:

```xml
<dependency>
  <groupId>io.github.tianhaocui</groupId>
  <artifactId>aura-db-migration-flyway</artifactId>
  <version>1.0.0</version>
</dependency>
```

```java
DatabaseModule database = DatabaseModule.create(db)
        .migrateWith(FlywaySchemaMigrator.classpath("db/migration"));
```

Liquibase users select `aura-db-migration-liquibase` and call
`LiquibaseSchemaMigrator.classpath("db/changelog.xml")`. Both adapters fail
startup for missing resources, return an exact applied migration count, and
leave the Aura-owned `DataSource` open. Custom engines implement
`SchemaMigrator`; do not adapt them through `SqlMigrationSource`, which only
feeds the lightweight Aura SQL migrator.

The Flyway adapter includes database-type plugins for PostgreSQL, MySQL, and
MariaDB. Applications still add their selected JDBC driver. Other databases
require the matching Flyway database plugin explicitly; never assume
`flyway-core` supports every production database.

## AI, MCP, and Workflows

Install optional capabilities explicitly:

```java
AuraApp.create()
        .use(AiModule.create(provider))
        .use(McpModule.create(router))
        .use(WorkflowModule.create())
        .use(new OrderModule())
        .start();
```

AI requests support timeout, retry, cancellation, input budgets, redaction,
structured output, and tool calling. Pass `ctx.cancellationToken()` to AI and
external HTTP calls. `SnapshotHttpClient` accepts only relative paths, disables
redirects, bounds response bodies, and enforces an absolute timeout.

Use explicit budgets when an AI request has a hard execution allowance:

```java
AiRequest request = AiRequest.builder()
        .prompt("Summarize this order")
        .budget(AiBudget.builder()
                .maxInputCharacters(8_000)
                .maxOutputCharacters(2_000)
                .maxTotalTokens(4_000)
                .maxCostMicros(50_000)
                .build())
        .build();
```

When token or cost limits are configured, a provider must report usage. Missing
usage fails closed with `AI_BUDGET_USAGE_MISSING`; an exceeded limit produces
`AI_BUDGET_EXCEEDED` with a stable dimension, limit, and actual value. Streaming
providers receive cooperative cancellation as soon as the character limit is exceeded.
The input-character limit covers the full immutable raw and redacted request snapshots, including
the system prompt, parameters, tools, and structured-output schema, and fails
before a provider call. Streaming retries are allowed only before any token is
visible; provider callback lifecycle violations fail with
`AI_PROVIDER_STREAM_CONTRACT`.

MCP tools are explicitly selected and can declare permissions:

```java
mcp.tool("delete_order", "Delete an order")
   .param("id", long.class, "Order ID")
   .permission("orders.delete")
   .handler(ctx -> orderService.delete(ctx.getLong("id")));
```

Permissioned tools fail closed unless an authenticated transport calls the
four-argument `invoke` boundary with an identity and authorizer. MCP transport
errors use stable JSON-RPC codes `-32700`, `-32600`, `-32601`, and `-32602`.
Enable authenticated HTTP JSON-RPC explicitly with
`McpModule.create(router).http()`; its sessions are identity-bound, capacity
bounded, idle-expiring, and re-authenticated on every request.
After `initialize`, send a standalone notification without an `id`:
`notifications/initialized`. Business methods are admitted only after the
session becomes `READY`. Send the negotiated `MCP-Protocol-Version` on later
requests; an explicit mismatch fails with `-32008` before session TTL changes.
SSE publishers enqueue into bounded per-session buffers and never write sockets
directly. Queue overflow closes the slow connection after recording the event in
bounded replay history; clients must reconnect with `Last-Event-ID` and treat a
`replay-gap` event as a required full resynchronization.
For one correlated POST response, prefer SSE with
`Accept: text/event-stream, application/json;q=0.5`; Aura emits a finite SSE
`message` containing the original JSON-RPC `id` and closes it. Equal quality
keeps the JSON fast path. Initialize and batch calls
must accept JSON, and notifications always return `202` without a response body.

Customer-specific processes use named workflow variants:

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

The framework owns ordering, validation, retries, compensation, execution
state, bounded workers, cancellation, and idempotency. Queued executions get
an execution ID before entering the worker queue and can be cancelled without
running business steps. Business modules own domain meaning and side effects.

Health checks run with a bounded timeout and fail closed. Configure
`aura.health-check-timeout` (1–300 seconds) when dependency probes need a
different deadline; check errors are never returned in health response bodies.

## Generated-Code Rules

- Use `AuraApp`, `AuraModule`, `ModuleContext`, and `TestClient` for new code.
- Prefer one explicit API over compatibility aliases.
- Pass cancellation tokens to HTTP, AI, database, and workflow calls.
- Branch on stable error codes and fields.
- Keep snapshots free of secrets, raw request bodies, and full exception details.
- Avoid unbounded reads, unbounded executors, global mutable state, and implicit discovery.

Read [docs/migration-to-1.0.md](docs/migration-to-1.0.md) for direct API migration.

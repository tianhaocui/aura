# Aura 1.0 AI Development Guide

Aura is a Java 17 backend framework with one deterministic development loop:

```text
AI writes a module -> Maven compiles it -> TestClient verifies it -> Schema/MCP describes it
```

Stability is the first priority. The API choices below are the only recommended
authoring path for new applications. The pre-1.0 guide is retained at
`docs/legacy-ai-guide-0.6.md` only for migration reference.

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

## Configuration

```java
AuraConfig config = new AuraConfig()
        .port(8080)
        .workers(100)
        .maxBodySize(10 * 1024 * 1024L)
        .maxUploadSize(1024 * 1024L)
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
router.get("/orders/{id}", handler)
        .describe("Get one order")
        .param("id", "long", "path", true, "Order identifier");
```

Binding is fixed: `int`/`long`/`String` use path then query parameters;
records/POJOs use JSON bodies; non-void results become JSON responses. Use
`ctx.bodyOrThrow(Type.class)` for a required validated body, and
`ctx.pageNum()`/`ctx.pageSize()` for bounded pagination.

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
state, bounded workers, cancellation, and idempotency. Business modules own
domain meaning and side effects.

## Generated-Code Rules

- Use `AuraApp`, `AuraModule`, `ModuleContext`, and `TestClient` for new code.
- Prefer one explicit API over compatibility aliases.
- Pass cancellation tokens to HTTP, AI, database, and workflow calls.
- Branch on stable error codes and fields.
- Keep snapshots free of secrets, raw request bodies, and full exception details.
- Avoid unbounded reads, unbounded executors, global mutable state, and implicit discovery.

Read [docs/migration-to-1.0.md](docs/migration-to-1.0.md) for direct API migration.

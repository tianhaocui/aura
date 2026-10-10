[中文版](README_CN.md)

# Aura

**A**I · **U**sable · **R**apid · **A**utonomous

Aura is an AI-native Java 17+ backend runtime. Stability is the first design
constraint. The second is making AI development accurate, fast, verifiable,
token-efficient, and easy to learn.

Aura 1.0 has one application model: reusable capability modules and business
modules are composed explicitly with `use(...)`. The same route contract drives
in-memory tests, Undertow, `/__schema__`, OpenAPI, generated bindings, and MCP.

## Install

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.tianhaocui</groupId>
      <artifactId>aura-bom</artifactId>
      <version>1.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-starter</artifactId>
  </dependency>
</dependencies>
```

Add `aura-db`, one database migration adapter, `aura-workflow`, `aura-ai`,
`aura-mcp`, or `aura-processor` only when the application needs that
capability.

Generate the same minimal, tested structure with Maven:

```bash
mvn archetype:generate -B \
  -DarchetypeGroupId=io.github.tianhaocui \
  -DarchetypeArtifactId=aura-archetype \
  -DarchetypeVersion=1.0.0 \
  -DgroupId=com.example -DartifactId=my-app \
  -Dversion=1.0.0-SNAPSHOT -Dpackage=com.example \
  -DdeploymentName=my-app
```

The generated project includes a multi-stage non-root Dockerfile and a
Kubernetes manifest with resource limits, rolling updates, graceful termination,
and startup/liveness/readiness probes. Replace the example public origin, host,
and image before deployment. Kubernetes resource names use the separate
lowercase `deploymentName` property, so a Maven artifactId may retain its own
valid Maven naming.

## Build An Application

```java
AuraApp app = AuraApp.create(new AuraConfig().port(8080))
    .use(HealthModule.create())
    .use(new AuraModule() {
        @Override public ModuleMetadata descriptor() {
            return ModuleMetadata.of("hello", "1.0.0");
        }

        @Override public void install(ModuleContext context) {
            context.route(new RouteContribution(
                "GET", "/hello",
                (BaseHandler) request -> request.text("hello world")));
        }
    });

app.start();
```

Each module has an ID, version, declared requirements, and explicit
contributions. Missing dependencies, version mismatches, cycles, and duplicate
routes fail at startup with stable error codes.

## Compose Capabilities

```java
AuraApp.create()
    .use(HealthModule.create())
    .use(DatabaseModule.create(db))
    .use(WorkflowModule.create())
    .use(AiModule.create(provider))
    .use(McpModule.create(router).http().sse())
    .use(OrderModule.create())
    .start();
```

Modules can be published as normal Maven artifacts. A user installs a complete
feature by adding the dependency and one `use(...)` call; adapters and hardcoded
bootstrap code are not required.

## Extend A Workflow Per User

A base workflow owns the common order path. A variant inserts customer-specific
steps without copying or branching the entire workflow:

```java
WorkflowDefinition<OrderContext> order = Workflow
    .<OrderContext>builder("order")
    .step("place", steps::place)
    .step("ship", steps::ship)
    .step("decrement-stock", steps::decrementStock)
    .variant("quality-customer", variant -> variant
        .insertAfter("place", "inventory-check", steps::inventoryCheck)
        .insertAfter("inventory-check", "quality-check", steps::qualityCheck))
    .build();
```

Choose the variant with a `WorkflowSelector` from tenant or customer context.
The framework validates the final graph before execution and keeps retry,
cancellation, compensation, and idempotency semantics consistent.

## Verify Without A Server

```java
AuraApp app = AuraApp.create()
    .use(OrderModule.create())
    .prepare();

TestClient test = TestClient.of(app);
test.post("/orders").body(new CreateOrder("sku-1", 2)).expect(200);
test.get("/orders/1").expect(200).bodyContains("sku-1");

Map<String, Object> schema = test.schema();
Map<String, Object> openApi = test.openApi();
```

This is the standard AI feedback loop: generate code, compile, run `TestClient`,
inspect structured failures, and only then start the real server.

## Production Boundaries

- Immutable, validated `AuraConfigSnapshot`.
- Deterministic `NEW -> STARTING -> RUNNING -> STOPPING -> STOPPED` lifecycle.
- Bounded request bodies, uploads, external responses, executors, snapshots,
  MCP batches/replay, and rate-limit keys.
- Cancellation propagation for timeouts, disconnects, shutdown, workflows,
  database work, and AI calls.
- Explicit CORS, WebSocket Origin, trusted proxy, Host, TLS, and schema controls.
- Stable structured error codes for startup, binding, routing, and runtime errors.

## Modules

| Module | Purpose |
|---|---|
| `aura-contract` | Dependency-light module, route, error, health, and cancellation contracts |
| `aura-core` | Configuration, lifecycle, module graph, capability registry |
| `aura-web` | Undertow adapter, routing, schema/OpenAPI, snapshots, `TestClient` |
| `aura-db` | Lightweight SQL, transactions, pagination, schema migrator SPI |
| `aura-db-migration-flyway` | Optional Flyway adapter for production migrations |
| `aura-db-migration-liquibase` | Optional Liquibase adapter for production migrations |
| `aura-bom` | Aligns every Aura module to one tested release |
| `aura-starter` | Recommended minimal HTTP application dependency |
| `aura-archetype` | Generates a compilable, tested, executable application |
| `aura-workflow` | Typed DAGs, variants, retries, compensation, cancellation |
| `aura-ai` | Provider SPI, sync/SSE calls, tools, structured output, budgets |
| `aura-mcp` | Tools/resources/prompts, authenticated HTTP/SSE/stdio transports |
| `aura-processor` | Optional generated route metadata and parameter binding |

## Documentation

- [AI guide](AI_GUIDE.md)
- [1.0 documentation](docs/DOCUMENTATION.md)
- [Module development](docs/aura-module.md)
- [Migration from pre-1.0](docs/migration-to-1.0.md)
- [Operations and release gates](AI_GUIDE_OPS.md)
- [Changelog](CHANGELOG.md)
- [1.0 readiness checklist](docs/1.0-readiness.md)
- [Runnable composed example](examples/aura-composed)
- [Runnable generated-route example](examples/aura-generated)

Pre-1.0 examples are retained only in versioned legacy documents for migration
reference. New applications should use `AuraApp` and explicit modules.

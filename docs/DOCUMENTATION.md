# Aura 1.0 Documentation

Aura is a Java 17+ backend runtime designed around two priorities: stable
production behavior and an AI-friendly development loop. The public 1.0 path
has one composition model: install explicit capability and business modules
with `use(...)`, verify them with `TestClient`, then start the same runtime over
real HTTP.

## Install

Add only the capability modules the application uses. `aura-web` is the
smallest HTTP module; database, Flyway/Liquibase migration adapters, workflow,
AI, MCP, and the annotation processor are optional Maven dependencies.

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

`aura-bom` is the single version declaration. Optional capability dependencies
omit their versions, preventing mixed Aura releases in generated or hand-written
projects. `aura-archetype` generates this layout together with a `TestClient`
smoke test and an executable shaded JAR.

## Compose An Application

```java
import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import io.aura.web.BaseHandler;

AuraApp app = AuraApp.create()
    .use(new AuraModule() {
        @Override public ModuleMetadata descriptor() {
            return ModuleMetadata.of("hello", "1.0.0");
        }

        @Override public void install(io.aura.ModuleContext context) {
            context.route(new RouteContribution(
                "GET", "/hello", (BaseHandler) ctx -> ctx.text("hello world")));
        }
    });

app.prepare();
```

<!-- aura:compile name=documentation-composition -->
```java
package docs.example;

import io.aura.AuraApp;
import io.aura.AuraModule;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;
import io.aura.web.BaseHandler;

public final class DocumentationComposition {
    private DocumentationComposition() { }

    public static AuraApp create() {
        return AuraApp.create().use(new HelloModule());
    }

    private static final class HelloModule implements AuraModule {
        @Override public ModuleMetadata descriptor() {
            return ModuleMetadata.of("hello", "1.0.0").provides("hello.api");
        }

        @Override public void install(ModuleContext context) {
            context.route(new RouteContribution(
                    "GET", "/hello", (BaseHandler) ctx -> ctx.text("hello")));
        }
    }
}
```

Modules are explicit and ordered by declared dependencies. A capability module
owns reusable infrastructure such as HTTP, database, health, workflow, AI, or
MCP. A business module owns application behavior and can contribute routes,
services, permissions, health checks, workflow variants, and MCP tools through
the same `ModuleContext`.

## Verify Before Start

`TestClient` uses the application's assembled modules and route metadata. It
does not open a socket, so an AI agent can compile and verify a change quickly.

```java
AuraApp app = AuraApp.create().use(HelloModule.create()).prepare();
var test = io.aura.web.TestClient.of(app);

test.get("/hello")
    .expect(200)
    .bodyContains("hello world");
```

The same route contribution is used by `TestClient`, Undertow, `/__schema__`,
OpenAPI, and MCP discovery. This prevents parameter binding and status/error
semantics from drifting between test and production paths.

## Standard Runtime

```java
AuraApp.create()
    .use(HealthModule.create())
    .use(DatabaseModule.create(db))
    .use(WorkflowModule.create())
    .use(AiModule.create(provider))
    .use(McpModule.create(router))
    .use(new OrderModule())
    .start();
```

The lifecycle is deterministic: `NEW -> STARTING -> RUNNING -> STOPPING ->
STOPPED`. Configuration is validated once and exposed to adapters as an
immutable `AuraConfigSnapshot`. Request, workflow, database, AI, health, and
snapshot execution paths use bounded resources and propagate cancellation.

Admission controls are explicit and bounded. A deployment can limit requests
per second and the number of distinct client keys retained by the limiter:

```java
AuraApp app = AuraApp.create(new AuraConfig()
    .rateLimitPerSecond(100)
    .rateLimitMaxKeys(65_536));
```

## Stable Contracts

Every failed request has a structured error with a stable `code`, field or
location, reason, and repair hint. Startup failures use searchable module,
route, configuration, and dependency error codes. The route metadata model is
the source for HTTP routes, schema, OpenAPI, generated bindings, and MCP tool
descriptions.

Useful public entry points:

- `AuraApp` and `AuraModule`: composition and lifecycle.
- `ModuleContext`: explicit module capabilities and contributions.
- `TestClient`: in-memory contract verification.
- `AuraConfig`: validated creation-time configuration.
- `RouteContribution` and `ApiRoute`: shared route metadata.
- `CancellationToken`: timeout, disconnect, and shutdown cancellation.

## Optional Modules

Read the focused guide only when the capability is installed:

- [Module development](aura-module.md)
- [HTTP and routing](aura-web.md)
- [Database](aura-db.md)
- [Workflow](aura-workflow.md)
- [AI](aura-ai.md)
- [MCP](aura-mcp.md)
- [Annotation processor](aura-processor.md)
- [Migration from pre-1.0](migration-to-1.0.md)

The former 0.6 document is retained at
[legacy-documentation-0.6.md](legacy-documentation-0.6.md) for migration
reference only. New code must not copy its legacy builder examples.

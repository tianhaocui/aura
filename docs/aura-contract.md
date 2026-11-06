# Aura Contract

`aura-contract` contains the small, framework-neutral types shared by Aura
modules and adapters. Its main artifact has no Undertow, database, MCP, or
other runtime dependency; an adapter is free to map these contracts to its
own transport and lifecycle.

```xml
<dependency>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-contract</artifactId>
    <version>1.0.0</version>
</dependency>
```

`ModuleMetadata` carries a stable module id, version, dependencies, and
capability names. `CapabilityLookup` exposes optional lookup and a consistent
required-capability failure. `Route` carries no HTTP server type, so an adapter
can describe routes without prescribing a transport.

`HealthCheck` reports `UP`, `DOWN`, or `UNKNOWN`. `CancellationToken` is a
cooperative signal that can be shared by request, AI, and workflow adapters.
Use `token.child()` for a child operation or `CancellationToken.linked(a, b)`
when an operation must stop if any parent (for example the caller or runtime
shutdown) is cancelled. Cancelling a child does not cancel its parents.
`StructuredError` and `ContractException` preserve a stable error code,
message, and details map for protocol-specific serialization.

`ExecutorMetrics` is the dependency-free queue/worker/completion/rejection/
timeout snapshot used by optional database, AI, and observability modules.

`RuntimeState` defines the stable lifecycle vocabulary used by replaceable Aura
runtime implementations: `NEW`, `STARTING`, `RUNNING`, `STOPPING`, and `STOPPED`.

`Route` is the one description of a route: method, path, handler, declared
parameters, return type, response schema, description, authentication,
permission, and whether the response streams. A handler is required by the
record itself, so a route can no longer be contributed without one, and
`context.route(Route)` is the only contribution entry point — a generated bundle
installs with `routes().forEach(context::route)`.

Every consumer — HTTP dispatch, `TestClient`, `/__schema__`, OpenAPI, and
generated MCP tools — reads that same record, so a fact declared once cannot
disagree between transports. `authenticated` and `permission` carry through all
of them; adapters must fail closed when an authenticated route has no caller
identity or a permissioned route has no authorizer.

`PermissionRegistry` provides an explicit, duplicate-checked list of permission
ids contributed by modules. Authorization implementations can consume its
snapshot without depending on Aura's runtime registry.

`ScalarType` is the single table of Java types that are JSON scalars. HTTP
binding, `/__schema__`, OpenAPI, MCP `inputSchema`, and the compile-time route
generator all resolve a type through it, so the same parameter can no longer be
a query string to one consumer and a request body to another.

The table answers two questions about the same type, and they have one source:
`jsonTypeName` says what a type renders as, `fromString(value, type)` says how a
string binds into it, and `isBody(type)` says whether a type binds from the
request line at all. A missing value binds to the type's own absence -- `0` or
`false` for a primitive, `null` for a boxed one -- and required-ness is decided
by the caller, which knows whether the handler signature can receive that
absence. Keeping the rendering next to the binding is what stops a transport
from advertising `integer` and then reading `"seven"` as `0`.

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
required-capability failure. `RouteContributor` returns `RouteContribution`
descriptors without prescribing an HTTP server implementation.

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

`RouteContribution` and `RouteContributor` let optional transport adapters receive
route descriptions without making `aura-contract` depend on Undertow or a specific
router implementation. Route metadata can include `ApiParameter` values for
handlers that cannot be inspected through reflection.

`ApiRoute.authenticated()` and `permission()` preserve the policy declared by
`RouteContribution` through Schema, OpenAPI, and generated MCP tools. Adapters
must fail closed when an authenticated route has no caller identity or a
permissioned route has no authorizer.

`PermissionRegistry` provides an explicit, duplicate-checked list of permission
ids contributed by modules. Authorization implementations can consume its
snapshot without depending on Aura's runtime registry.

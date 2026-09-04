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
`StructuredError` and `ContractException` preserve a stable error code,
message, and details map for protocol-specific serialization.

`RouteContribution` and `RouteContributor` let optional transport adapters receive
route descriptions without making `aura-contract` depend on Undertow or a specific
router implementation. Route metadata can include `ApiParameter` values for
handlers that cannot be inspected through reflection.

`PermissionRegistry` provides an explicit, duplicate-checked list of permission
ids contributed by modules. Authorization implementations can consume its
snapshot without depending on Aura's runtime registry.

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

The 0.6 return: one route contract, one entry point per decision, and a layering
gate that runs in the normal build. `docs/REFACTOR_LOG.md` carries the decision
record, the stage-by-stage log, and the migration table.

### Breaking

- `RouteContribution`, `RouteEntry`, `ApiRoute`, `RouteContributor` and the
  package-scan route entry are removed. `context.route(Route.of(method, path,
  handler))` is the only contribution, with `describedAs`, `withParams`,
  `withReturnType`, `withResponseSchema`, `withStreaming` and `requireAuth` as
  typed setters.
- `ModuleContext.routes(RouteContributor)` is removed; install a generated bundle
  with `routes().forEach(context::route)`.
- `AuraApp.create(Path)` and `AuraApp.create(Properties)` are removed. Load with
  `AuraConfig.fromProperties(...)` and pass the result to `AuraApp.create(...)`.
- `AuraApp.start()` collapses into the `start(String...)` overload.
- `io.aura.HealthModule` moved to `io.aura.web.HealthModule`.
- The reflective MCP tool binding is removed; register
  `McpRouter.tool(Route, McpHandler)` or the `tool(name, description)` builder.
- A present-but-blank route permission now fails at startup instead of publishing
  an unprotected route.
- `io.aura.WebSocketContribution` and `AuraWebRuntime.webSocketContributions()`
  are removed. A WebSocket endpoint is contributed as a `Route` with method
  `Route.WEBSOCKET` — `context.webSocket(path, configure)` is sugar over
  `context.route(Route.webSocket(path, configure))` — and `BaseRouter.WsRoute`
  now wraps that contract instead of a bare path and handler.
- A missing `int`/`long`/`double`/`boolean`/`char` or path parameter is a
  `400 AURA_PARAMETER_REQUIRED` naming the parameter, not a handler running on
  `0` or `false`. Applications that relied on the default must declare the
  boxed type instead.
- `McpParam(String, String, String, List)` and
  `McpParam(String, String, String, List, boolean)` are removed;
  `McpParam(ApiParameter, List<McpEnumValue>)` is the only constructor, and a
  hand-authored tool argument that is not a JSON scalar is refused when the tool
  is registered.

### Changed

- `/__schema__` and OpenAPI project the same `Route` record. The JSON keys stay
  as they were; absent metadata stays absent.
- HTTP parameter binding and MCP tool arguments share `ParamResolver`, so a
  malformed number is a `NumberFormatException` on both transports instead of a
  400 on one and a silent zero on the other.
- Java-to-JSON types and accepted enum values come from one table.
- Host, admission, security-header, CSRF and CORS checks run as one chain built
  by `RequestGuards.standard(app, routePattern)`, used by Undertow and
  `TestClient` alike, so in-memory verification covers the security layer.
- A guarded request carries the path its templates match — the URI without the
  query string — on both transports, so a route-scoped rule cannot be bypassed
  by appending `?x=1`.
- `MockContext.ip()` resolves the same client address the admission chain judged,
  from the peer the request was built with plus `X-Forwarded-For` and the
  configured trusted proxies.
- A `WS` route is assembled, published and authorised like an HTTP route: it
  appears in `/__schema__` as a streaming endpoint with its path parameters, is
  skipped by OpenAPI and the MCP bridge on purpose, and its handshake runs the
  same `requireAuth`/permission door before the protocol changes.
- `/__schema__` publishes `streaming`, so a request/response consumer can decline
  an endpoint instead of discovering it at call time.
- The gates on Aura's own endpoints — the schema token, the OpenAPI switch, and
  the WebSocket Origin allow-list — moved from the Undertow dispatch into the
  shared chain. `TestClient` now answers `/__schema__` and `/openapi.json` itself
  and rejects exactly what a socket rejects, so the protection around the route
  map is verifiable in milliseconds instead of only over a real port.
- `required` is the binder's rule everywhere: `ParamResolver.isRequired(type,
  source)` decides it (a primitive or a path segment, because that is what a
  signature cannot hold as `null`), `RouteCatalog` publishes exactly that, and
  the missing-primitive/path case is refused before the handler runs. A request
  body is no longer marked required, since a handler may legitimately see
  `null`.
- `McpRouter.invoke` enforces what `tools/list` advertises, and stdio, the HTTP
  transport and the bridge answer `MCP_MISSING_ARGUMENT` instead of invoking a
  tool whose required argument was withheld.
- A hand-authored MCP tool declares `ApiParameter` records and refuses a
  non-scalar argument at registration, so `tools/list` cannot publish
  `"type": "string"` for an object the handler will never receive. `McpParam`
  lost its string-typed constructors.
- OpenAPI parameter types come from `ScalarType` too, a parameter that described
  its own shape publishes that shape to OpenAPI (an enum keeps its accepted
  values), and the `example` in `/__schema__` is generated from the same table,
  so replaying the example a model copied binds.

### Fixed

- `aura.schema.enabled=true` in a non-dev environment used to be a flag that did
  nothing: without a token the endpoint answered 404 forever. The switch and the
  token are now independent doors, either of which opens the schema.
- The MCP bridge stopped handing a model choices it cannot serve: authenticated,
  streaming, `WS` and untransmittable-parameter routes are no longer advertised,
  a guessed tool name for one stays refused, an upstream status at or above 400
  becomes `MCP_UPSTREAM_HTTP_<status>` instead of a successful tool result whose
  text is an error page, and a missing required argument is named instead of
  silently dropped.

- Snapshot capture redacts URLs, query parameters, bodies and stack traces before
  they leave the request thread, and bounded external calls share one `IpSafety`
  SSRF check.
- The annotation processor no longer references itself while compiling.
- `CorsConfigTest2` matched no Surefire include pattern and never ran in a normal
  build; renamed to `CorsEnforcementTest`, and `mvn verify` runs
  `ArchitectureTest` instead of excluding it.

## [1.0.0] - 2026-10-11

Aura 1.0 is the first stable release with a complete AI-native application model.
Stability is the first design constraint; AI development accuracy, speed,
verifiability, token efficiency, and ease of learning are the second priority.

### Added

#### Module System
- `AuraApp.create().use(...)` explicit module composition with dependency graph
- `AuraModule` interface with `descriptor()`, `install()`, `start()`, `stop()` lifecycle
- `ModuleContext` for dependency injection, route contribution, and capability registration
- `ModuleMetadata` for declaring module ID, version, requires, and provides
- Topological ordering and startup/stop lifecycle management

#### Unified Route Contract
- Single route metadata model driving HTTP, TestClient, `/__schema__`, OpenAPI, and MCP
- `RouteContribution` → `RouteEntry` → `CompiledRoute` → `ApiRoute` pipeline
- `aura-processor` for compile-time route generation with reflection fallback
- `TestClient` for in-memory request verification before starting Undertow

#### Capability Modules
- `aura-ai`: Provider SPI, sync/stream calls, retry, cancellation, budgets,
  structured JSON output, tool calling, redaction, usage accounting, RAG, embeddings
- `aura-mcp`: Tools/resources/prompts/ping, stdio/HTTP/SSE transports, sessions,
  authentication, permissions, bounded queues/responses, bridge, and packager
- `aura-workflow`: Typed DAGs, named variants, step insertion, bounded executors,
  redacted durable snapshots, fencing leases, recovery, and cancellation
- `aura-db`: Explicit transaction context, bounded independent executor,
  validated pagination with stable ordering, migration SPI
- `aura-db-migration-flyway`: Production Flyway adapter
- `aura-db-migration-liquibase`: Production Liquibase adapter

#### Configuration and Lifecycle
- Immutable validated `AuraConfig` with startup rejection of invalid values
- Deterministic `NEW → STARTING → RUNNING → STOPPING → STOPPED` lifecycle
- `CancellationToken` propagation for timeouts, disconnects, shutdown, and workflows
- Bounded executors with observable queue, rejection, and timeout metrics

#### Security
- Undertow 2.3.21.Final baseline (CVE-2024-3884 fixed)
- Snapshot redaction for sensitive data with bounded retention
- SSRF protection with URL validation and path boundary checks
- Credentialed wildcard CORS rejection
- WebSocket Origin validation
- Request body, upload, and external response size limits
- Trusted proxy, Host allowlist, and TLS configuration

#### AI Development Experience
- 10 evaluation tasks covering crud, auth, validation, database, upload, sse,
  websocket, mcp, workflow, and ai capabilities
- Stable structured error codes with field, reason, and remediation details
- `AI_GUIDE.md` as the single recommended authoring path
- `migration-to-1.0.md` for direct API migration from pre-1.0 versions

#### Release Infrastructure
- `aura-bom` aligning all modules to one tested release
- `aura-starter` as recommended minimal HTTP dependency
- `aura-archetype` generating compilable, tested, executable applications
- CycloneDX SBOM with complete 13-module closed dependency graph
- GPG signature verification for all release artifacts
- SHA-256 checksum manifest

### Changed

- Entry point changed from `Aura.create().plugin(...)` to `AuraApp.create().use(...)`
- Service lookup changed from global/static to `context.require(Type.class)`
- Workflow positions changed from numeric to named steps with `before()`/`after()`
- Pagination requires explicit stable ordering with unique tie-breaker
- `Page.total()` and `Page.totalPages()` now return `OptionalLong`/`OptionalInt`
- AI redaction runs once during request building; providers read frozen `redacted*` accessors
- MCP sessions require explicit protocol negotiation and `notifications/initialized`
- Database migrations are now a module lifecycle concern via `SchemaMigrator` SPI

### Removed

- Legacy `Aura` builder, `AuraPlugin`, `Reloadable`, and `BeforeBuilder` APIs
- Implicit package discovery (replaced by explicit `use(...)`)
- Global mutable service registry
- Raw `Db.findWhere(table, where, ...)` with unvalidated SQL fragments
- Unbounded response reads and executor pools
- Provider-side request redaction (now frozen at build time)

### Security

- Upgraded Undertow from 2.3.12.Final to 2.3.21.Final (CVE-2024-3884)
- Added snapshot redaction for URLs, query parameters, request bodies, and stack traces
- Added SSRF protection for `SnapshotHttpClient` and MCP bridge
- Added credentialed wildcard CORS rejection
- Added WebSocket Origin validation with integration tests
- Added request timeout with real HTTP integration tests

## [0.6.x] and earlier

See `docs/legacy-documentation-0.6.md` and versioned PRD documents under `docs/`
for historical changes. Pre-1.0 APIs are retained only for migration reference.

[1.0.0]: https://github.com/tianhaocui/aura/releases/tag/v1.0.0

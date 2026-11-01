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

### Fixed

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

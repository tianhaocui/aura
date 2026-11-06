# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-11-06

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
- The 14 adapter ports moved from `io.aura.web` to `io.aura.port` in `aura-core`:
  `BaseHandler`, `BaseContext`, `BaseRouter`, `BaseRouteBuilder`,
  `BaseExceptionHandler`, `SseEmitter`, `UploadedFile`, `RouteResponse`,
  `RequestType`, `WsHandler`, `WsConnection`, `AdmissionPolicy`,
  `AdmissionRequest`, `AdmissionDecision`. `io.aura.web` now means the Undertow
  adapter and nothing else, so `import io.aura.web.BaseHandler` is
  `import io.aura.port.BaseHandler`. `WsHandler`'s callback fields, which the
  adapter read across the split package, are reached through `openHandler()`,
  `messageHandler()`, `closeHandler()` and `errorHandler()` instead.
- The reflective MCP tool binding is removed; `McpRouter.tool(Route)` is the only
  tool registration door.
- The reflective route door is removed. `ModuleContext.service(Object)`,
  `AuraWebRuntime.services()`, `StartupCheck`, and the reflective
  `router.<verb>(path, target, "method")` overloads and `crud(...)` on `BaseRouter`
  and `Router` are gone, together with `ServiceRegistrar`, `MethodRefHandler` and the
  reflective DI auto-wiring in `ServiceResolver`. A route is contributed with
  `context.route(Route.of(method, path, handler))` — or, for a service instance,
  `router.<verb>(path, new LambdaHandler(service))` — and nothing scans a class or
  dispatches by method name to find one.
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
- `McpToolBuilder` and `McpEnumValue` are removed, together with every
  `tool(name, description).param(...).handler(...)` chain they supported. A
  hand-authored tool is declared the same way an endpoint is:
  `McpRouter.tool(Route.of("GET", "/user", (McpHandler) ctx -> ...).describedAs(...).withParams(List.of(new ApiParameter(...))))`.
  The tool name is derived from the route (`GET /user` is `get_user`, `DELETE
  /order/{id}` is `delete_order_by_id`), so a registry and a model cannot keep
  separate spellings of it.
- `McpParam(ApiParameter, List<McpEnumValue>)` becomes `McpParam(ApiParameter)`,
  and `enumValues()` and `isEnum()` go with it. A parameter that the Java-to-JSON
  table cannot render and that declares no schema now fails at registration with
  `AURA_MCP_PARAM_SCHEMA_MISSING` instead of reaching `tools/list` as a string.
- `McpRouter.tool(Route)` refuses a route whose handler is not an `McpHandler`
  with `AURA_MCP_HANDLER_MISMATCH`. The previous two-argument overload accepted
  `route.handler()` without reading it, and the `tool(name, description)` builder
  was the door that let a second, differently-spelled declaration stand beside a
  route. Project a route served in another transport with
  `route.withHandler((McpHandler) ctx -> ...)` — it keeps every declared fact.
- Enum choices are published from the contract, not invented by MCP. A route
  catalog or an annotation processor puts `{"type":"string","enum":[names]}` on
  the parameter; `tools/list` renders it verbatim, exactly as OpenAPI does. The
  builder's `NEW(新建)` label form is gone — bind the value with
  `ctx.getEnum("status", OrderStatus.class)`.
- A hand-authored mapping from a friendly label to a code moves into the handler
  that uses it. `McpContext` no longer holds a framework-side mapping table, so
  `getString`/`getInt`/`getLong` return the argument the model sent.
- `McpTool`'s four- and five-argument convenience constructors are removed; the
  six-component record constructor is the only way to build one.
- The string-to-value table moved from `io.aura.web.ParamResolver` to
  `io.aura.contract.ScalarType`, beside the JSON names it must match.
  `ParamResolver.fromString`, `ParamResolver.isBody` and its sixteen
  `public static … parse…(String)` methods are removed; use
  `ScalarType.fromString(value, type)` and `ScalarType.isBody(type)`.
  `ParamResolver` keeps the request-shaped half: `create(Parameter)`,
  `isRequired(type, source)` and `resolveString(ctx, name)`.
- Registering the same type twice now fails at startup instead of silently
  replacing the first bean, and a named registration no longer doubles as a type
  registration: `require(Class)` must see either one candidate or none, and an
  ambiguous type fails instead of picking one. Use `require(name, Class)` or
  `find(name, type)` for named lookups.
- Calling `start(args)` after `prepare()` or after a module is installed is
  refused: arguments are part of the startup decision and must be supplied before
  the runtime freezes its configuration.
- Two routes that match the same shape (`/orders/{id}` and `/orders/{name}`,
  including two WebSocket templates) now fail at startup instead of one winning
  by sort order.
- A request body above `maxBodySize` is `413 REQUEST_BODY_TOO_LARGE`, not a 500.
- `transactionIndependent` distinguishes a timeout that rolled back, one whose
  commit completed after the deadline, and one whose outcome is unknown, instead
  of reporting every timeout the same way.
- A cron expression whose local time falls in a DST overlap fires at the later
  occurrence, and `shutdownPool()` no longer makes the shared transaction pool
  permanently unusable.

### Added

- `context.cron("0 9 * * *", () -> reminders.checkOverdue())` is the scheduling
  door. A module contributes periodic work in `install` the way it contributes a
  route, the runtime arms it from `CronSchedule.next(…)` against the wall clock,
  and the daemon pool that holds it closes with the runtime — no module builds a
  `ScheduledExecutorService` of its own. The parser takes the standard five-field
  crontab subset (`*`, lists, ranges, steps, 0 or 7 for Sunday, and a day that
  matches either field when both restrict the day) and refuses a day-of-month no
  selected month has, because that line would silently never fire.
- A cron task that throws is reported as a `runtime.cron.failure` event with
  `AURA_CRON_TASK_FAILED` and its next occurrence stays armed. A missing task or
  expression (`AURA_CRON_CONTRIBUTION_INVALID`), an expression that is not five
  fields, is out of range, or selects an empty day
  (`AURA_CRON_EXPRESSION_INVALID`) and a contribution made after the schedule was
  armed (`AURA_CRON_CONTRIBUTION_TOO_LATE`) fail at the door and name the module.
- `BaseContext.sendFile(String, Path, String)` streams a file from disk without
  reading it into a byte array, and `sendStream(...)` streams generated content
  with either a declared length or chunked transfer. The socket adapter transfers
  the file asynchronously, closes the channel or stream, and the in-memory adapter
  captures the bytes for tests.
- Downloads answer `Range` requests. A single `bytes=` range returns `206` with
  `Content-Range`, an unsatisfiable one returns `416` with `Content-Range:
  bytes */<size>`, length-delimited responses (downloads and static resources)
  advertise `Accept-Ranges: bytes`, and a chunked stream answers `Accept-Ranges:
  none`. Multiple ranges fall back to the full `200`, and gzip is skipped for a
  ranged request so the offsets describe the identity bytes.
- `aura-db` has PostgreSQL type sugar: `Jsonb.of(value)` / `Jsonb.raw(json)` bind
  `jsonb` parameters, `PgVector.of(...)` binds pgvector `vector` parameters,
  `Row.getJson`/`getJson(type)` and `Row.getVector` read both back from a
  driver's text form, and `Row.setJson`/`setVector` feed the same wrappers
  through `insert`, `execute` and `batch`.
- `AuraApp.boundPort()` reports the port the started adapter actually bound, so
  tests and embedded deployments can configure `port(0)` and still address the
  server. An in-memory or stopped runtime reports `-1`.

### Changed

- `/__schema__` and OpenAPI project the same `Route` record. The JSON keys stay
  as they were; absent metadata stays absent.
- HTTP parameter binding and MCP tool arguments share
  `ScalarType.fromString(value, type)`, so a malformed number is a
  `NumberFormatException` on both transports instead of a 400 on one and a silent
  zero on the other.
- Java-to-JSON types and accepted enum values come from one table.
- `aura-mcp` depends on `aura-web` in test scope only. The bridge talks to a
  running application over HTTP, so `aura-mcp-bridge.jar` no longer bundles the
  Undertow adapter, and `McpLayeringTest` fails if `src/main` reaches into
  `io.aura.web` again.
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
- A tool argument's declared type and its accepted values are read from the same
  `ApiParameter` the HTTP side reads, so `tools/list` and OpenAPI cannot
  disagree. `McpParam` takes nothing but that contract, and
  `McpUtil.inputSchema` renders a parameter's declared schema instead of
  assembling enum values of its own.
- OpenAPI parameter types come from `ScalarType` too, a parameter that described
  its own shape publishes that shape to OpenAPI (an enum keeps its accepted
  values), and the `example` in `/__schema__` is generated from the same table,
  so replaying the example a model copied binds.
- A route contributed without a handler fails as `AURA_ROUTE_HANDLER_MISSING`
  naming the method and path, and a `null` contribution fails as
  `AURA_ROUTE_CONTRIBUTION_INVALID` naming the module. Neither is a bare
  `NullPointerException` any more, so the assembly mistake says which door was
  wrong instead of pointing at a constructor frame.
- The types this branch added are documented where their module is documented:
  `IpSafety` in `docs/aura-core.md`, `RequestGuards.standard(...)` in
  `docs/aura-web.md`, `FakeAiProvider` and `ScriptedAiProvider` in
  `docs/aura-ai.md`, and `WorkflowClock`/`MutableClock` in
  `docs/aura-workflow.md`.
- The documentation gate compiles instead of quoting. `docs/aura-core.md`,
  `docs/aura-web.md`, `docs/aura-ai.md` and `docs/aura-workflow.md` now carry
  `aura:compile` fences, the gate builds `aura-ai` and `aura-workflow` too, and
  it drops the installed `aura-*.jar` files from the compile classpath so a
  fence resolves against this tree only. Prose that names the removed
  `io.aura.HealthModule` location fails the same gate.
- `AI_GUIDE_MCP.md` joined the canonical documents the gate reads, and the gate
  fails if any of them teaches `McpToolBuilder` or a `tool("name", ...)` chain.
  A guide that names the deleted door is as broken as code that calls it.
- The last four module guides joined them: `docs/aura-module.md`, `docs/aura-db.md`,
  `docs/aura-mcp.md` and `docs/aura-processor.md` carry `aura:compile` fences too, so
  every canonical module guide compiles against this tree (12 fences). `aura-db` and
  `aura-mcp` joined the gate's build list, and `aura-processor` deliberately did not
  join its classpath -- that module's `META-INF/services` processor registration
  would turn documentation into a build that generates sources -- so fences now
  compile with `-proc:none`, a failure names the document and the fence, and the
  contracts gate fails if one of the four leaves the compiling list.
- `ArchitectureTest` R1 forbids `io.aura.web..` as a dependency target for every
  policy package, and R2's subject is `io.aura.port..` with
  `allowEmptyShould(true)` dropped so it cannot go vacuous. The rule that can
  actually fire lives in `aura-web`: `WebLayeringTest` runs where the policy
  classes and the Undertow implementation are both loaded, so a policy type
  shipped inside the adapter fails the build instead of surviving a shared
  package name.
- The MCP tests read `tools/list` through a typed reader (`McpWire`) instead of
  casting the document into `Map<String, Object>` at every level. A renamed key
  used to become a `null` that the next assertion compared against something
  else; now it names the key it looked for. `aura-mcp` carries one unchecked
  cast (an in-Java handler result, not a wire document) and no
  `toJavaObject(Map.class)` — a fastjson `JSONObject` is already the map it was
  cast into. The contracts gate holds all three: each schema test names
  `McpWire`, at most one unchecked cast may sit in the module, and
  `toJavaObject(Map.class)` may not return.

### Security

- CSRF protection is enforced outside `dev` by default and grows teeth: an
  enforced configuration must name `aura.public-origin`, and neither the request's
  `Host` nor `X-Forwarded-Host` can stand in for it. A trusted proxy explains how
  a request arrived, not which origin the operator intended. Opt out only with an
  explicit `aura.csrf.enabled=false`.
- CORS answers a preflight only when the request carries both `Origin` and
  `Access-Control-Request-Method`. Ordinary `OPTIONS` requests now reach their
  route, and an explicit allow-list is no longer widened by echoing every header
  the caller asked for.
- JWT verification pins `alg` to `HS256` and `typ` to `JWT`, checks `exp` and
  `nbf` with a configurable clock skew, optionally matches `iss`/`aud`, and
  refuses a subject that decodes to control characters.
- An AI provider refuses to send an API key, and an MCP bridge refuses to send a
  schema token, over plaintext `http://` unless the caller opts in explicitly.
- `ctx.header()` rejects a name or value containing CR/LF instead of letting the
  container split it into a second header.
- A plain `IllegalArgumentException` no longer reflects its message to the client;
  the response carries `VALIDATION_ERROR` and a generic reason.

### Changed

- `aura.cors=false` disables CORS instead of becoming the literal allowed origin
  `"false"`; `maxBodySize` is bounded at 1 GiB; an unknown `aura.*` property is
  logged at startup rather than silently ignored.
- Module configuration binding reports `AURA_CONFIG_INVALID` with the offending
  key, and a boolean must be `true` or `false` rather than anything non-`true`
  becoming `false`.
- `BeanValidator` runs the `Validatable` hook for plain objects as well as
  records.

### Fixed

- Undertow moves from 2.3.21.Final to 2.3.25.Final for three high-severity
  request/response smuggling advisories (CVE-2026-28367, CVE-2026-28368,
  CVE-2026-28369). The contract gate now refuses anything below 2.3.25.Final.
- The PostgreSQL JDBC driver moves from 42.7.7 to 42.7.12 for two high-severity
  authentication advisories (CVE-2026-42198, CVE-2026-54291).
- The generated deployment image now pins `maven-resources-plugin` 3.3.1 and
  `maven-jar-plugin` 3.4.1. The smoke build runs Maven 3.8.7 on the host and
  3.9.11 in the container, whose lifecycle defaults differ (2.6/2.4 against
  3.3.1/3.4.1), so the mirror-only repository could not serve the plugin the
  container asked for. The `dependency:go-offline` layer is gone with it: it
  resolves every lifecycle plugin, including ones the build never runs, which a
  repository holding only the host's downloads cannot satisfy.
- `CronSchedulerTest.closingTheRealPoolEndsItsThreadsWithoutRunningAnything` no
  longer fails when the worker is still listed by `Thread.getAllStackTraces()`
  for a moment after the pool reports termination.
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
- `WebSocketTest.connectionLimitRejectsExcessAndReleasesPermitOnClose` asserted
  the handler's open count immediately after the client's handshake `join()`, but
  the count is incremented on an Undertow thread. It failed once in a full
  reactor run and passed on the retry; it now waits for the count it checks, so
  the limit it tests cannot be verified against a race.
- `CorsConfigTest2` matched no Surefire include pattern and never ran in a normal
  build; renamed to `CorsEnforcementTest`, and `mvn verify` runs
  `ArchitectureTest` instead of excluding it.
- `ModuleGraph` follows SemVer for caret ranges (`^0.2.3` no longer accepts
  `0.3.0`), accepts open-ended ranges such as `[,2.0)`, and no longer reads a
  non-numeric version suffix as a major version.
- `AuraApp.start(String...)` parses every argument before applying any of them, so
  a rejected `--config=` no longer leaves `--dev` or a partial property override
  behind.
- `HealthProbe` no longer shares one static worker pool across runtimes: each
  invocation owns its bounded executor, and the deadline alone decides a check
  whose task could not start in time.
- `SnapshotRecorder` enforces its collection caps while the snapshot is written,
  so a concurrent scope mutation cannot grow the redacted document past the
  bound. `SnapshotHttpClient` reports caller interruption as cancellation
  instead of a 502 dependency failure and restores the interrupt flag.
- An AI retry charges provider-reported usage from the failed attempt against the
  request budget before the next attempt, instead of only counting a successful
  response.
- `IpSafety` unwraps NAT64 (`64:ff9b::/96`) to its embedded IPv4 address and
  refuses Teredo tunnels (`2001::/32`).
- A retained idempotency key is `IDEMPOTENCY_RETAINED` for every
  `WorkflowExecutionStore`, including terminal executions in stores that do not
  implement the durable lease SPI; the previous non-durable path silently
  returned the old result.
- Schema migrations order non-numeric versions by SemVer, split PostgreSQL
  dollar-quoted bodies without breaking on inner semicolons, and serialise
  concurrent runs across processes through a `_migration_lock` row. The runner
  creates that table on first use.
- The Liquibase adapter serialises updates within a JVM. Liquibase keeps per-run
  lock bookkeeping on shared command-step singletons, so two concurrent updates
  could leave the changelog lock held and block every later run; cross-process
  updates still serialise through Liquibase's own `DATABASECHANGELOGLOCK`.
- `Row.getBool` reads numeric `TINYINT`/`Byte` flags as booleans, and
  `getInt`/`getLong` accept integral `BigDecimal`/`BigInteger` values while
  logging a warning when the conversion truncates or overflows.
- OpenAPI generates unique `operationId` values when distinct paths normalise to
  the same name, and publishes `400`, `401`/`403` when applicable, and `500`
  alongside `200`.
- Generated routes reject a missing primitive or `char` parameter with the same
  400 as reflection (`BaseContext.requireParameter`), `MockGuardedRequest.hostName`
  strips IPv6 brackets, an after-handler failure is logged instead of swallowed,
  and `InMemoryConversationStore` bounds conversation count and identifier
  length.
- The Flyway and Liquibase adapter tests run against disposable PostgreSQL
  containers through Testcontainers instead of skipping unless
  `AURA_MIGRATION_JDBC_URL` is set.

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

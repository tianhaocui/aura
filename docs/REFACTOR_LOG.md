# Refactor log: the 0.6 return

This branch takes Aura 1.0 back to the two constraints that made 0.6 readable:
the model should have as few choices as possible, and a change must be
verifiable in memory in milliseconds. Every stage below is a commit on
`refactor/stage1-security`.

## What the philosophy requires

| Constraint | Consequence in this branch |
| --- | --- |
| Minimize the model's choices | one route record, one contribution call, one application entry point, one string→value binder, one tool-schema builder |
| Millisecond self-verification | `TestClient` and the fake AI providers run the real contract without sockets; `prepare()` installs modules without opening listeners |
| One contract, many consumers | `Route` is read by HTTP dispatch, `TestClient`, `/__schema__`, OpenAPI, and MCP; each fact is declared once |
| Policy in Core, mechanism in Web | the guard chain and admission policy are decided in core types and executed by the Undertow adapter; the health module moved to the adapter |

## Stages

| Stage | Commit | Work |
| --- | --- | --- |
| ① | `45a5484` | snapshot redaction and SSRF bounds for external calls, `IpSafety` abstraction, ArchUnit layering gate, annotation-processor self-reference fix |
| ②-A | `8bb2e4c` | introduced `io.aura.contract.Route` additively, so nothing broke while consumers moved |
| ②-B | `9a6213e` | the four Web consumers read `Route` |
| ②-C | `836b083` | MCP stopped stripping the contract; one `McpUtil.inputSchema` builder for every tool |
| ②-D | `751d577` | one Java→JSON type table (`ScalarType`) and one binder (`ParamResolver`) shared by HTTP and MCP |
| ③ | `17ffae7` | pre-route guards extracted to `RequestGuards.standard(app, routePattern)`, consumed by both Undertow and `TestClient` |
| ④-ab | `a78630a` | deleted the reflection MCP tool binding and the package-scan route entry |
| ④-cf | `69f4eb9` | deleted `RouteContribution`, `RouteEntry`, `ApiRoute`, `RouteContributor`; `/__schema__` became an explicit JSON projection; `AuraApp` kept `create()` + `create(AuraConfig)` and one varargs `start` |
| ④-g | `5994f96` | `HealthModule` moved to `io.aura.web`; `ArchitectureTest` R5 turned from a recorded failure into a boundary |
| ⑤-ai | `ab7622c` | in-memory AI providers for self-verification without a model endpoint |
| ⑤-workflow | `2f15688` | injectable, virtual workflow clock |
| review-A/B | `d88ab00`, `fb06686` | guarded requests match route templates on the path alone; one mock request resolves one client IP |
| review-C | `a14410e` | the MCP bridge stopped offering and calling routes it cannot serve |
| review-D | `2b63207` | a WebSocket endpoint became a `Route` |
| review-E | `9dfb9c2` | the schema token, OpenAPI switch and WebSocket Origin allow-list moved into the shared guard chain, so `TestClient` proves them without a port |

## Decisions that differed from the plan

**`McpToolBuilder` stayed; the reflection overload went.** The plan said to
delete the builder and keep `McpRouter.tool(Route, McpHandler)`. In practice the
builder is the only hand-authored MCP entry a user needs when a tool has no HTTP
route, while the reflective overload was a *second* way to describe the same
tool. Deleting the duplicate, not the ergonomics, is what the constraint asks
for.

**`ParamResolver` throws `NumberFormatException`, not a new exception type.**
Unifying the binder risked turning a 400 on one transport into a silent zero on
the other. `NumberFormatException` is an `IllegalArgumentException`, so the Web
400 mapping and the MCP callers keep the behaviour they had, and the two
transports cannot disagree about a malformed value.

**`AuraApp` keeps `create()` and `create(AuraConfig)`, not one method.**
Loading a deployment file is an `AuraConfig` concern again
(`AuraConfig.fromProperties(path)`), so the facade no longer offers four doors.
Collapsing to a single `create(AuraConfig)` would have rewritten 86 call sites to
say `create(AuraConfig.fromProperties(new Properties()))` for no clarity gain.
`start()` disappeared on its own: the varargs overload already accepts it.

**`SnapshotHttpClient` stays in `aura-web` main.** A follow-up task proposed
moving it to test support, on the reading that it only records dependency
traffic. It does not: its public surface is `get`/`post`/`send` for live calls,
`AI_GUIDE.md` documents it as the sanctioned bounded client, and
`AI_GUIDE_OPS.md` shows application code registering it. Recording and replaying
a snapshot is what makes it safe, not what makes it a test tool. The name is the
only arguable part, and renaming a security-reviewed class was not worth the
churn; the class javadoc now states the role instead, so the reading that
spawned the task cannot be reached again from the file alone.

**`HealthModule` moved instead of being excused.** Stage ① wrote R5 to fail
until Stage ②/④ resolved it, so `mvn test` had carried
`-Dtest='!io.aura.ArchitectureTest'` since then. Health status stays policy in
core; contributing `GET /health/live` is HTTP mechanism, so the class moved to
`io.aura.web` and the exclusion is gone.

**A WebSocket endpoint became a `Route` instead of a second contribution type.**
Review found `WebSocketContribution` travelling beside the contract, so nothing
in `/__schema__`, OpenAPI, or the MCP bridge knew it existed and its handshake
skipped the auth door entirely — `compileWsRoutes` applied no `secure()`. It is
now `Route.webSocket(path, configure)` with method `WS`, which forces
`streaming` because that is the fact that makes it unreadable to a
request/response consumer, publishes its path parameters, and appends the same
`RouteAssembler.secure(...)` handler to the handshake's before-chain, so a
`requireAuth` endpoint answers 401/403 before the protocol changes.
`context.webSocket(...)` survived as sugar over `context.route(...)` because the
callback shape is the ergonomics; the parallel record and the
`webSocketContributions()` accessor did not.

**`required` became the binder's rule instead of a declaration.** Review found
that `required` was published by `RouteCatalog` and by `McpToolBuilder` but
enforced nowhere: a primitive `int limit` was marked required, then bound to
`0` when the client withheld it, so the schema that told a model "you must send
this" was the same schema that rewarded not sending it. One rule now answers it
— `ParamResolver.isRequired(type, source)`, i.e. required is what the handler's
signature cannot express as `null`, a primitive or a path segment — and the
binder enforces it with `RequiredParameterException` (400,
`AURA_PARAMETER_REQUIRED`, the offending name in `field`). A request body stays
`required: false` even though it used to be marked mandatory: a handler may
legitimately see `null` there, and
`GenericBodyBindingTest.missingCollectionBodyPreservesNullableBodyBinding`
pins that, so the declaration moved to match the runtime rather than the other
way round. Both MCP doors now keep the promise `tools/list` makes:
`McpRouter.invoke` refuses before the handler runs, and stdio, the HTTP
transport and the bridge all answer `MCP_MISSING_ARGUMENT` instead of invoking
a tool with a zero.

**`McpToolBuilder` lost its private type table; the door itself is still open.**
The same review note recorded that the builder is a second *declaration* path,
and unlike the other findings this one is only half-closed, so it is written
down: hand-authored tools still name their arguments in Java rather than
deriving them from a `Route`, which is a choice the route path never asks an AI
to make. What disappeared is the disagreement — the builder holds
`ApiParameter` records and leaves every Java-to-JSON decision to
`McpUtil.inputSchema`, and it now refuses a non-scalar argument at
registration instead of publishing `"type": "string"` for an object the handler
cannot receive. `McpParam`'s two string-typed constructors went with it, so
`ApiParameter` is the only way to describe an argument. The two copies of the
type table that the MCP one was beside got the same treatment: `OpenApiGenerator`
asks `ScalarType` (its private switch called a `BigDecimal` query parameter a
string while the schema document called it a number), a parameter that
described its own shape now publishes that shape to OpenAPI instead of an
unbounded string, and `RouteContract.exampleValue` comes from the same table so
the example a model copies is a value the binder accepts.

**The built-in endpoint gates joined the chain rather than staying in the
dispatch.** Review found three decisions living inside
`UndertowStarter.dispatch` — the schema token, the OpenAPI switch, and the
WebSocket Origin allow-list — after the shared chain had already run. Their
consequence was that Stage ③'s promise held only up to the application's own
routes: `TestClient` could prove a CSRF rejection but could not prove whether the
route map was readable, and the only way to test that was to bind a port. Moving
them into `RequestGuards` as `BuiltinEndpointsGuard` keeps the order the server
already used (chain, then built-in decision, then dispatch), keeps the rejections
bodyless so a 404 says no more than a 404, and lets `TestClient` serve
`/__schema__` and `/openapi.json` from the same projection the socket does. One
behaviour changed on purpose: a production app that set `aura.schema.enabled`
without a token previously got a permanent 404, so the switch and the token are
now independent doors instead of a flag that silently did nothing.

## Deleted or moved public surface

| 1.0.0 | now |
| --- | --- |
| `io.aura.contract.RouteContribution` | `Route.of(method, path, handler)` + `describedAs`, `withParams`, `withReturnType`, `withResponseSchema`, `withStreaming`, `requireAuth` |
| `RouteEntry`, `PackageScanner` | gone, no replacement: `context.route(Route)` is the only contribution |
| `io.aura.WebSocketContribution`, `AuraWebRuntime.webSocketContributions()` | `Route.webSocket(path, configure)` (method `WS`), reached through `context.route`; `context.webSocket` is sugar over it |
| `io.aura.contract.ApiRoute`, `ApiRouteCatalog` | `Route`, `io.aura.web.RouteCatalog` |
| `io.aura.contract.RouteContributor`, `ModuleContext.routes(...)` | `new XAuraRoutes(service).routes().forEach(context::route)` |
| `Route.fromContribution(...)`, metadata `Map<String,Object>` keys | typed fields; a misspelled key can no longer drop a route from the schema |
| `AuraApp.create(Path)`, `create(Properties)` | `AuraApp.create(AuraConfig.fromProperties(path))` |
| `AuraApp.start()` | `start(String...)` |
| `AuraApp::authenticate` reflection route scanning | unchanged; `@Get`/`@Post` + `aura-processor` is the production path |
| `io.aura.HealthModule` | `io.aura.web.HealthModule` |
| `McpRouter.tool(Object, String)` reflective binding | `McpRouter.tool(Route, McpHandler)` |
| `McpParam(String, String, String, List)` and `McpParam(String, String, String, List, boolean)` | `McpParam(ApiParameter, List<McpEnumValue>)` — a tool argument is declared with the shared parameter record, so it cannot carry its own Java-to-JSON opinion |

`/__schema__` keeps its JSON keys — `method`, `path`, `description`,
`returnType`, `params`, `example`, `responseSchema`, `authenticated`,
`permission` — because `McpBridge` and the tests read them; only the Java type
behind them changed. Absent metadata stays absent rather than arriving as
`null`, and a present-but-blank permission now fails at startup instead of
publishing an unprotected route.

One published value changed meaning on purpose. `params[].required` is the
binder's rule now, so a request body reads `required: false` where 1.0 said
`true`, and a missing primitive or path parameter is answered with
`400 AURA_PARAMETER_REQUIRED` (and `MCP_MISSING_ARGUMENT` over MCP) instead of
binding to `0` or `false`.

## Tests that disappeared, and why

- `ModuleAssemblyTest.routeContributionWithoutHandlerFailsDuringModuleInstallation`
  and `routeContributorIsEvaluatedExactlyOnce`: the shapes they covered no longer
  exist; `Route` requires a handler by construction, so the error code is
  unreachable.
- `RouteTest.toApiRouteProjectsAllFieldsExceptHandler` and
  `apiRouteFromRoundTripsViaRoute`: the projection moved into the schema
  document, and `TestClientTest` / `PermissionAuthorizationTest` now assert the
  same facts on the wire keys.
- `ContractTypesTest.apiRouteContractsAreImmutableAndNormalized`: rewritten as
  `routeContractsAreImmutableAndNormalized`.
- `RuntimeFoundationTest.healthModuleDeclaresTheStandardHealthRoutes`: moved to
  `HealthModuleTest` in aura-web, which can see the moved class.
- Seven cases in `MethodRefHandlerTest`, `LambdaHandlerTest` and `McpRouterTest`
  asserted that an absent parameter defaults to `0`, `0L` or `false`. They were
  renamed to `..._namesTheRequiredParameter` and now assert the refusal and the
  parameter's name: the old expectation *was* the defect, and each one still
  runs. `RouteCatalogTest`'s body assertion flipped to `required: false` with
  the reason written next to it.

No assertion was weakened to make a stage pass.

## Verification actually run

```bash
mvn -o clean verify
bash scripts/verify-aura-contracts.sh
bash scripts/verify-docs-code.sh
bash scripts/verify-public-api-classifier.sh
```

`mvn -o clean verify` is green module by module, including `ArchitectureTest`
and the jacoco `check` rules. Two traps cost real time and are worth repeating:

1. **Stale jars lie.** `mvn -o test-compile` reported success against an old
   `aura-contract` jar in `~/.m2`. Always `clean`, always let the reactor build
   dependencies (`-am`), and confirm the per-module `Tests run:` lines rather
   than trusting a green summary.
2. **A test that never ran.** `CorsConfigTest2` matches no surefire include
   pattern, so it executed only when `-Dtest` replaced the defaults. Renamed to
   `CorsEnforcementTest`; its six cases now run in a normal build and pass.

**Review fixes were checked by reverting them, not only by adding tests.** For
`fix(review-F)` the four enforcement points were deleted one transport at a
time and the new cases were run again: removing the required loop in
`McpRouter.invoke` and the scalar refusal in `McpToolBuilder.declared` turned
exactly 8 aura-mcp cases red (`McpRouterTest` ×4, the stdio door, the HTTP
transport door, the two registration refusals); removing the binder's
`RequiredParameterException` and `RouteCatalog`'s use of
`ParamResolver.isRequired` turned exactly 11 aura-web cases red, including the
wire-document pair in `RequiredParameterTest` and the 400 that decays to
`limit=0 tag=null`; restoring `OpenApiGenerator`'s private switch and dropping
the parameter schema turned exactly its 2 new cases red. The controls stayed
green throughout — a present parameter still binds, an optional parameter still
arrives as `null`, an absent body still reads `tags=null` — so the reds name
the fix rather than the build.

Known flake: `LiquibaseSchemaMigratorTest.concurrentFirstStartsSerializeChangesetsAcrossIndependentPools`
allows 15 seconds for two serialized Liquibase runs and timed out once on this
Intel machine while passing twice. It is timing, not behaviour, but the budget
should be raised with the rest of the database work.

`scripts/verify-public-api.sh` needs `jq`, which is not installed here. The
regenerated baseline came from the script's own generation half (its first 141
lines, `jq` dropped from the tool check); the classification half still needs
`jq` locally or in CI.

## Still open

- `McpToolBuilder` is still a second declaration door for hand-authored tools.
  Its disagreements with the route path are gone (`ApiParameter` in, one
  `McpUtil.inputSchema` render, non-scalar arguments refused at registration),
  but naming arguments in Java is still a choice an AI makes that
  `McpRouter.tool(Route, McpHandler)` does not ask for. Deleting the builder is
  the review's stronger remedy and stays open with the MCP/`aura-web` dependency
  cleanup.
- `io.aura.web` is a split package: the core ports and the Undertow
  implementation share it, which is why R1 cannot forbid `io.aura.web..` yet.
  Renaming the ports to `io.aura.port..` is the next layering step.
- `aura-mcp` still declares a compile dependency on `aura-web`; R6 documents it
  and stays vacuous until copied into an aura-mcp test.
- `scripts/verify-public-api.sh` needs `jq`, which this machine does not have, so
  step 5 of `verify-local-readiness.sh` stops there. The baseline this branch was
  checked against came from a `jq`-free copy of the generator, not from that
  script. The remaining unchecked boxes in `docs/1.0-readiness.md` are CI-side
  runs (Docker/kind, service-container migrations, OSV, SBOM, signing, Central
  Portal) that need a push to `origin`, not code.

## Suggested review split

1. Security and guard chain (①, ③) — reviewable on its own, no API change.
2. Contract unification (②-A…②-D) — additive first, then consumers.
3. Entry-point collapse (④-ab, ④-cf) — the breaking part; needs the migration
   table above and the regenerated API baseline.
4. Layering boundary (④-g) — small, mechanical, unblocks dropping the
   `ArchitectureTest` exclusion.
5. AI and workflow verification tools (⑤) — independent of the route work.

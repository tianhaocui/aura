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

## Deleted or moved public surface

| 1.0.0 | now |
| --- | --- |
| `io.aura.contract.RouteContribution` | `Route.of(method, path, handler)` + `describedAs`, `withParams`, `withReturnType`, `withResponseSchema`, `withStreaming`, `requireAuth` |
| `RouteEntry`, `PackageScanner` | gone, no replacement: `context.route(Route)` is the only contribution |
| `io.aura.contract.ApiRoute`, `ApiRouteCatalog` | `Route`, `io.aura.web.RouteCatalog` |
| `io.aura.contract.RouteContributor`, `ModuleContext.routes(...)` | `new XAuraRoutes(service).routes().forEach(context::route)` |
| `Route.fromContribution(...)`, metadata `Map<String,Object>` keys | typed fields; a misspelled key can no longer drop a route from the schema |
| `AuraApp.create(Path)`, `create(Properties)` | `AuraApp.create(AuraConfig.fromProperties(path))` |
| `AuraApp.start()` | `start(String...)` |
| `AuraApp::authenticate` reflection route scanning | unchanged; `@Get`/`@Post` + `aura-processor` is the production path |
| `io.aura.HealthModule` | `io.aura.web.HealthModule` |
| `McpRouter.tool(Object, String)` reflective binding | `McpRouter.tool(Route, McpHandler)` |

`/__schema__` keeps its JSON keys — `method`, `path`, `description`,
`returnType`, `params`, `example`, `responseSchema`, `authenticated`,
`permission` — because `McpBridge` and the tests read them; only the Java type
behind them changed. Absent metadata stays absent rather than arriving as
`null`, and a present-but-blank permission now fails at startup instead of
publishing an unprotected route.

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

Known flake: `LiquibaseSchemaMigratorTest.concurrentFirstStartsSerializeChangesetsAcrossIndependentPools`
allows 15 seconds for two serialized Liquibase runs and timed out once on this
Intel machine while passing twice. It is timing, not behaviour, but the budget
should be raised with the rest of the database work.

`scripts/verify-public-api.sh` needs `jq`, which is not installed here. The
regenerated baseline came from the script's own generation half (its first 141
lines, `jq` dropped from the tool check); the classification half still needs
`jq` locally or in CI.

## Still open

- `io.aura.web` is a split package: the core ports and the Undertow
  implementation share it, which is why R1 cannot forbid `io.aura.web..` yet.
  Renaming the ports to `io.aura.port..` is the next layering step.
- `aura-mcp` still declares a compile dependency on `aura-web`; R6 documents it
  and stays vacuous until copied into an aura-mcp test.
- The readiness evidence scripts still assume the pre-refactor names in a few
  comments.

## Suggested review split

1. Security and guard chain (①, ③) — reviewable on its own, no API change.
2. Contract unification (②-A…②-D) — additive first, then consumers.
3. Entry-point collapse (④-ab, ④-cf) — the breaking part; needs the migration
   table above and the regenerated API baseline.
4. Layering boundary (④-g) — small, mechanical, unblocks dropping the
   `ArchitectureTest` exclusion.
5. AI and workflow verification tools (⑤) — independent of the route work.

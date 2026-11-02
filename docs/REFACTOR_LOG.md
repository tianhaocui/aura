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
| Policy in Core, mechanism in Web | the guard chain and admission policy are decided in core types and executed by the Undertow adapter; the health module moved to the adapter; the ports the adapter implements live in `io.aura.port`, so a package name means exactly one thing |

## Stages

| Stage | Commit | Work |
| --- | --- | --- |
| ① | `45a5484` | snapshot redaction and SSRF bounds for external calls, `IpSafety` abstraction, ArchUnit layering gate, annotation-processor self-reference fix |
| ②-A | `8bb2e4c` | introduced `io.aura.contract.Route` additively, so nothing broke while consumers moved |
| ②-B | `9a6213e` | the four Web consumers read `Route` |
| ②-C | `836b083` | MCP stopped stripping the contract; one `McpUtil.inputSchema` builder for every tool |
| ②-D | `751d577` | one Java→JSON type table (`ScalarType`) and one binder (`ParamResolver`) shared by HTTP and MCP |
| ③ | `17ffae7` | pre-route guards extracted to `RequestGuards.standard(app, routePattern)`, consumed by both Undertow and `TestClient` |
| ④-ab | `a78630a` | deleted `io.aura.RouteEntry`, `io.aura.web.PackageScanner` and the reflective
  `McpRouter.tool(name, service, method, description)` binding — one contribution door and one
  declared tool door left |
| ④-cf | `69f4eb9` | deleted `RouteContribution`, `RouteContributor`, `ApiRoute`, `ApiRouteCatalog`; `/__schema__` became an explicit JSON projection; `AuraApp` kept `create()` + `create(AuraConfig)` and one varargs `start` |
| ④-g | `5994f96` | `HealthModule` moved to `io.aura.web`; `ArchitectureTest` R5 turned from a recorded failure into a boundary |
| ⑤-ai | `ab7622c` | in-memory AI providers for self-verification without a model endpoint |
| ⑤-workflow | `2f15688` | injectable, virtual workflow clock |
| review-A/B | `d88ab00`, `fb06686` | guarded requests match route templates on the path alone; one mock request resolves one client IP |
| review-C | `a14410e` | the MCP bridge stopped offering and calling routes it cannot serve |
| review-D | `2b63207` | a WebSocket endpoint became a `Route` |
| review-E | `9dfb9c2` | the schema token, OpenAPI switch and WebSocket Origin allow-list moved into the shared guard chain, so `TestClient` proves them without a port |
| review-F | `cfa665a` | `required` became the binder's single rule on both transports, MCP stopped inventing types, and OpenAPI/examples joined `ScalarType` |
| review-G | `dca32d3` | a refused contribution names the module or the route instead of throwing `NullPointerException`; the wire documents are read through typed getters, and `SchemaProbeTest` (zero assertions) is gone |
| review-H | `6cbf8de` | the added types are documented in their module docs, and `verify-docs-code.sh` compiles the documentation against this tree instead of quoting it |
| stage2-i | `6c4f93b` | the 14 policy ports left `io.aura.web` for `io.aura.port`, so R1 can forbid the adapter package, R2 has a real subject set, and `WebLayeringTest` enforces the boundary where both sides are on the classpath |

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

**Two assembly mistakes keep two codes.** `Route`'s constructor and
`ModularRuntime.route(...)` both refuse, and it is tempting to collapse them into
one failure since both mean "you contributed something unusable". They stayed
apart because the caller repairs them in different places: a `null` handed to
`context.route` names the *module* (`AURA_ROUTE_CONTRIBUTION_INVALID` carries the
descriptor id, which is how a bundle of forty routes is narrowed to one install
method), while a `Route` built without a handler names the *method and path*
(`AURA_ROUTE_HANDLER_MISSING`, because a stack frame inside a record's
constructor says nothing about which route was being assembled). Both are coded
`ContractException`/`ModuleException` rather than the `NullPointerException` they
replaced, for the reason the whole 0.6 return runs on: an AI that reads the
failure has to be able to repair it without reading the source it failed in.

**Documentation compiles against the tree, and that required fixing the gate
first.** `IpSafety`, `RequestGuards.standard`, `FakeAiProvider`/
`ScriptedAiProvider` and `WorkflowClock`/`MutableClock` were added by this branch
and documented nowhere, so the first pass at review-H wrote fences for them and
marked them `aura:compile`. The gate then passed a deliberately wrong import:
`docs/aura-core.md` naming `io.aura.HealthModule` -- the location ④-g deleted --
compiled, because `dependency:build-classpath` puts the installed
`io.github.tianhaocui:aura-core:1.0.0` jar on the classpath and that jar still
contains `io/aura/HealthModule.class`. The gate was checking the documentation
against `~/.m2`, the same lie trap #1 above describes for the reactor. It now
builds `aura-ai` and `aura-workflow` alongside `aura-web`, takes their
`target/classes` first, and drops every installed `aura-*.jar` from the compile
classpath, so a fence resolves against this checkout or not at all. The floor
moved from a total count to a per-document count for the same kind of reason: a
single total falls by exactly as much as a deleted marker lowers the count of
markers found, so deleting a fence could not fail a whole-list floor. Prose is
covered by the same gate now too, because only the fence compiles and a reader
copies the sentence next to it.

**The ports left `io.aura.web`, and the split package was load-bearing.** The
plan recorded R1's missing `io.aura.web..` entry as a chore deferred because the
package name meant two things at once. It was worse than a naming problem:
`WsHandler`'s four callback fields were package-private and `CompiledWsRoute`, in
another module, read them directly -- which compiled only because `aura-core` and
`aura-web` agreed on the package name. A boundary the compiler can cross is not
a boundary, and the source said so in a comment. The 14 port types (`BaseHandler`,
`BaseContext`, `BaseRouter`, `BaseRouteBuilder`, `BaseExceptionHandler`,
`SseEmitter`, `UploadedFile`, `RouteResponse`, `RequestType`, `WsHandler`,
`WsConnection`, and the three `Admission*` types) now live in `io.aura.port` in
`aura-core`, and the adapter reads the handler fields through accessors.

`io.aura.contract` was the other candidate, rejected on a constraint rather than
taste: the ports depend only on `java.*` and `io.aura.contract.*` today, so they
would pass R3 now, but a port that ever needs a core type -- a cancellation
token, a runtime handle -- would become unmovable, because R3 is what makes
`aura-contract` the zero-dependency vocabulary. `io.aura.port` keeps that door
open and leaves R3 untouched.

The tightened rules say what they can and cannot catch. In `aura-core`, R1's new
`io.aura.web..` entry is a tripwire, not the guarantee: `aura-core` has no
compile dependency on `aura-web`, so the real bar is Maven's and the rule cannot
fire there. What does fire is the same rule copied into `aura-web`
(`WebLayeringTest`), where the policy classes and the Undertow implementation are
both loaded -- that is where "a policy type shipped inside the adapter" becomes a
build failure. R2 dropped its `allowEmptyShould(true)`, and neither R2 nor
`WebLayeringTest` carries one: a rule whose subject set went empty has stopped
checking something, and a green build would report that as a pass.

**The binder's table moved, and it took the last `mcp -> web` edge with it.**
`ParamResolver` held two unrelated things: how a value is pulled out of an HTTP
request, and the string-to-value table every transport needs. The second half is
policy -- it is the promise `/__schema__` publishes when it says `integer` -- and
it sat in the Undertow adapter, which left `aura-mcp` importing `io.aura.web` to
coerce a tool argument and kept a compile dependency on a web server inside a
bridge that only speaks HTTP to one. `fromString`, `isBody` and the sixteen
private parse helpers now live in `ScalarType`, beside the JSON names they have to
agree with: a type that renders as `integer` is a type that binds as an integer,
and adding a scalar means answering both questions in one place. `ParamResolver`
keeps the request-shaped half -- `create(Parameter)`, `isRequired`,
`resolveString` -- and `aura-mcp`'s dependency on `aura-web` became test-scoped,
because the tests mount an application and `src/main` has no reason to.

That made R6 expressible, so R6 moved rather than being tightened in place. The
copy in `aura-core` carried `allowEmptyShould(true)` and a javadoc admitting it
was vacuous: no `io.aura.mcp` class is on that classpath, so the rule had passed
by inspecting nothing. `McpLayeringTest` in `aura-mcp` runs the same assertion
over the module where the subject set exists, and the core copy is gone -- a rule
kept in a file where it cannot fire is documentation filed under tests. The
bridge jar is smaller for it: `aura-mcp-bridge.jar` went from 1560
`io/undertow/` classes and 67 `io/aura/web/` classes to none of either, and from
6,172,757 bytes to 2,354,235 -- the adapter was being shipped inside a bridge that
only speaks HTTP to an application running it somewhere else.

## Deleted or moved public surface

| 1.0.0 | now |
| --- | --- |
| `io.aura.contract.RouteContribution` | `Route.of(method, path, handler)` + `describedAs`, `withParams`, `withReturnType`, `withResponseSchema`, `withStreaming`, `requireAuth` |
| `io.aura.RouteEntry` (public) and `io.aura.web.PackageScanner` (package-private, called by
  `RouteAssembler`) | gone, no replacement: `context.route(Route)` is the only contribution, and
  the scan door is closed rather than hidden behind a flag |
| `io.aura.WebSocketContribution`, `AuraWebRuntime.webSocketContributions()` | `Route.webSocket(path, configure)` (method `WS`), reached through `context.route`; `context.webSocket` is sugar over it |
| `io.aura.contract.ApiRoute`, `ApiRouteCatalog` | `Route`, `io.aura.web.RouteCatalog` |
| `io.aura.contract.RouteContributor`, `ModuleContext.routes(...)` | `new XAuraRoutes(service).routes().forEach(context::route)` |
| `RouteContribution`'s metadata `Map<String,Object>` keys | typed fields; a misspelled key can no longer drop a route from the schema |
| `AuraApp.create(Path)`, `create(Properties)` | `AuraApp.create(AuraConfig.fromProperties(path))` |
| `AuraApp.start()` | `start(String...)` |
| `AuraApp::authenticate` reflection route scanning | unchanged; `@Get`/`@Post` + `aura-processor` is the production path |
| `io.aura.HealthModule` | `io.aura.web.HealthModule` |
| `io.aura.web.BaseHandler`, `BaseContext`, `BaseRouter`, `BaseRouteBuilder`, `BaseExceptionHandler`, `SseEmitter`, `UploadedFile`, `RouteResponse`, `RequestType`, `WsHandler`, `WsConnection`, `AdmissionPolicy`, `AdmissionRequest`, `AdmissionDecision` | same types in `io.aura.port`; `io.aura.web` is the Undertow adapter only |
| `io.aura.web.ParamResolver.fromString`, `.isBody`, `.parseInt`/`.parseLong`/`.parseBool`/`.parseDouble`/`.parseFloat`/`.parseShort`/`.parseByte`/`.parseChar` and the eight boxed overloads | `io.aura.contract.ScalarType.fromString(value, type)` and `ScalarType.isBody(type)`; the coercion rules are private, so no transport asks a parse method for one type again |
| `McpRouter.tool(String name, Object service, String method, String description)` reflective binding | `McpRouter.tool(Route, McpHandler)` |
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
  exist — there is no contributor lambda to evaluate twice, and a route without a
  handler is refused where it is built. `fix(review-G)` gave that refusal back a
  name: `Route`'s constructor answers `AURA_ROUTE_HANDLER_MISSING` with the method
  and path, `ModularRuntime` answers `AURA_ROUTE_CONTRIBUTION_INVALID` with the
  module id, and `RouteTest` / `AuraModuleTest` assert both codes. Neither was a
  `NullPointerException` from `Objects.requireNonNull` any more, which named
  nothing and pointed at a constructor frame instead of the door.
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
- `SchemaProbeTest` was deleted, not rewritten. It started an app, printed
  `/__schema__` to `/tmp/aura-schema-probe.json` and asserted nothing, so it
  could not fail and cost a real port to find out. What it stood for is now
  `SchemaWire` — a test-only reader that parses `TestClient.schema()` and
  `TestClient.openApi()` into typed JSON — used by `TestClientTest`,
  `WebSocketContractTest`, `OpenApiGeneratorTest`, `RequiredParameterTest`,
  `SchemaExampleTest`, `PermissionAuthorizationTest` and the generated example.
  Reading the projection with `getBoolean`/`getString` is what makes a moved key
  a failure; the previous unchecked casts handed a `null` to the next assertion
  and the `toString().contains` form matched text that had nothing to do with the
  field its name claimed.

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

`fix(review-G)` was checked the same way at both doors. Replacing
`Route`'s `ContractException` with the `Objects.requireNonNull` it displaced
turned exactly 3 cases red — 1 in `RouteTest`, and in aura-core
`AuraModuleTest.moduleContributesValidatedWebSocketEndpoint` plus
`aHandlerlessRouteContributionFailsWithTheRouteNamed` — which is the point of
the change: the failure has to name the route, and only a coded exception says
it in a form a caller can act on. The typed wire reader was checked against the
drift it exists to catch: renaming `/__schema__`'s `description` key to `summary`
— a key move a consumer would notice — turned 3 cases red
(`TestClientTest`, `WebSocketContractTest`, and the generated example), while the
`toString().contains` version of the generated-example assertions it replaced ran
green on the same document. A substring of the whole projection cannot fail a
rename; the reason the old assertions survived every key change so far is the
reason they are gone.

`fix(review-H)` was checked at the gate, since its subject is the gate. Three
mutations, one per rule it now enforces: the `io.aura.web.HealthModule` import in
the new `docs/aura-core.md` fence changed to the removed `io.aura.HealthModule`
failed the run with `错误: 找不到符号` at that fence's line 8 and 15 (and failed
only after the installed `aura-*.jar` files came off the classpath -- before that
fix the same mutation passed, which is the finding); deleting the
`workflow-virtual-time` marker line failed with `no compilable fence marked in
docs/aura-workflow.md`; and the same removed location written into `README.md`
prose failed with `documentation names the removed io.aura.HealthModule
location`. Restoring each file returned the gate to `7 compiled fences`. The
per-document floor is what makes the second one fail: under the earlier total
floor the mutation was invisible, because the count of markers found dropped with
the count of fences compiled.

The ports move was checked at the new rule and at the baseline. `WebLayeringTest`
was proven to bite rather than merely pass by shipping a temporary
`io.aura.snapshot.LeakProbe` inside `aura-web` -- a policy-named class with an
`io.aura.web.Router` parameter and return type -- which failed
`policyStaysOutOfTheAdapter` on both of those lines; deleting the probe returned
one green case, so the rule has a subject set and a trigger. The probe was never
committed. The API baseline was regenerated with the generator's own first 140
lines (the `jq`-free half, as before) rather than edited: its type set is 244
entries both before and after, the only members that changed are the 18
`io.aura.web.*` port entries (14 types plus 4 nested), and after sorting the body
lines every one of the 248 differing lines names `io.aura.web` or `io.aura.port`
-- proof the move carried no other surface with it. Hand-editing the baseline
first and comparing against the generated file was deliberate: it showed the two
disagree on ordering, which is the difference between a rename and a regeneration.

`verify-aura-contracts.sh` now refuses the old arrangement on five independent
grounds, and each was falsified by reintroducing exactly the drift it names:
adding back `aura-core/src/main/java/io/aura/web/BaseContext.java` failed with
`the split io.aura.web package returned`; a core source file importing
`io.aura.web.BaseHandler` failed with `a policy port was addressed through the
adapter package`; a `docs/aura-core.md` line naming `io.aura.web.BaseHandler`
failed with `canonical documentation still points a policy port at io.aura.web`;
making `WsHandler.onOpen` package-private again failed with `WsHandler callbacks
are package-private again`; and moving `SseEmitter.java` out of `io.aura.port`
failed with `policy port is not in io.aura.port: SseEmitter`. Restoring each file
returned the gate to green. The last two cases initially exited 1 silently, because
they were written as bare `rg -q` assertions: a release gate that fails without
naming the invariant is a gate someone has to debug at 2 a.m., so both now carry
their own message.

`McpLayeringTest` was falsified the way only a rule about a dependency can be: with
`aura-web` back at compile scope and a `LayeringProbe` in `aura-mcp/src/main`
calling `TestClient.schema()`, it failed with
`Method <io.aura.mcp.LayeringProbe.client(io.aura.web.TestClient)> calls method
<io.aura.web.TestClient.schema()> in (LayeringProbe.java:7)`; putting the scope
back and deleting the probe returned one green case. Neither the probe nor the pom
edit is committed. The five new release-gate checks were falsified one mutation
apiece -- `ParamResolver` regrowing `parseInt`, `McpContext` importing
`io.aura.web.ParamResolver`, the pom's test scope removed, `McpLayeringTest.java`
deleted, and `.allowEmptyShould(true)` added back to a layering rule -- each failing
with its own message and each green again on restore. The binding table gained unit
tests where it now lives (`ScalarTypeTest` went from 3 cases to 8: the table binds
every type it renders, absent values bind to the type's own absence, a malformed
value names itself, enums bind by `name()`, and `isBody` is the mirror of the scalar
table), which is the same rule review-G applied to the wire documents: a check
belongs next to the code it covers.

Verified without `-Dmaven.test.failure.ignore` (`mvn -o verify`), and note the trap
that run avoids: `mvn -o -pl aura-mcp test` without `-am` resolves `aura-core` from
`~/.m2`, where the installed 1.0.0 jar still has the ports in `io.aura.web`, and the
failure surfaces as `TestEngine with ID 'junit-jupiter' failed to discover tests`
rather than as a stale-jar message. Every module run here carries `-am`.

Fixed flake: `LiquibaseSchemaMigratorTest.concurrentFirstStartsSerializeChangesetsAcrossIndependentPools`
allowed 15 seconds for two serialized Liquibase runs and timed out at 16.4 s on
this Intel machine during the `stage2-j1` reactor run, which broke a full
`mvn -o clean verify` rather than a rerun. The budget is 60 seconds now. The
invariant the case checks is which pool applied the changesets -- exactly one,
because the lock serialises them -- so a timeout is a deadlock guard, and a
deadline that a slow changelog parse can cross tests machine speed instead.

Known flake: `DeploymentSecurityTest.hostAllowlistRejectsUnexpectedHost` answered
`404` where it asserts `421` once, during the run that followed three aborted
`-Dmaven.test.failure.ignore` reactor runs, and passed on the full reactor and
three further aura-web runs before and after. `availablePort()` reserves a port
and releases it before the app binds, so a listener that outlived one of those
abandoned runs could own it — and a server without that route answers `404` for a
Host the allowlist would have rejected. The cause is not proven, only the shape
that fits: a `404` says a different process answered, not that the guard missed.
The class proves behaviour over a real socket, so the port cannot be dropped; the
fix is to bind the accepted socket rather than pick a number, and it belongs with
the rest of the socket-backed tests.

`scripts/verify-public-api.sh` needs `jq`, which is not installed here. The
regenerated baseline came from the script's own generation half (its first 141
lines, `jq` dropped from the tool check); the classification half still needs
`jq` locally or in CI.

## Still open

- The compiling list covers `docs/DOCUMENTATION.md`, `AI_GUIDE.md` and the four
  module documents that gained fences; `docs/aura-module.md`, `aura-db.md`,
  `aura-mcp.md` and `aura-processor.md` are still only scanned for legacy API
  names, so a moved type in their prose is still invisible. `aura-db` and
  `aura-mcp` also need their modules added to the gate's build list first.
- `McpRouterTest` and the MCP schema tests still read `buildSchema()` through
  unchecked casts and string lookups. `fix(review-G)` typed the readers of the
  two wire documents it could reach (`TestClient.schema()`/`openApi()` and
  `OpenApiGenerator.generate(...)`); the MCP tool schema is a third projection
  of the same `Route` and belongs with them.
- `McpToolBuilder` is still a second declaration door for hand-authored tools.
  Its disagreements with the route path are gone (`ApiParameter` in, one
  `McpUtil.inputSchema` render, non-scalar arguments refused at registration),
  and it no longer reaches into the adapter for a coercion rule, but naming
  arguments in Java is still a choice an AI makes that
  `McpRouter.tool(Route, McpHandler)` does not ask for. Deleting the builder is
  the review's stronger remedy and stays open.
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

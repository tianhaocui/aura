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
| stage2-j1 | `66bff04` | the string-to-value table moved from `io.aura.web.ParamResolver` to `io.aura.contract.ScalarType`, beside the JSON names it must match, and `aura-mcp` dropped its compile-scope dependency on the Undertow adapter |
| stage2-j2 | `f7fb8f0` | `McpToolBuilder`, `McpEnumValue` and `McpRouter.tool(String, String)` are deleted so `McpRouter.tool(Route)` is the only tool door, enum choices and body shapes are projected from the contract's own schema, label→code mapping moved into the handler, and both silent failures got codes |
| stage2-j3 | `78c2873` | the MCP schema tests read `tools/list` through `McpWire` instead of unchecked casts (`24 + 4` suppressions to `1 + 0`, no `toJavaObject(Map.class)` left), the absent-metadata invariant became assertable, and the gate holds all three bounds |
| stage2-j4 | `bcc893b` | `docs/aura-module.md`, `aura-db.md`, `aura-mcp.md` and `aura-processor.md` gained `aura:compile` fences (7 fences to 12), so every module guide compiles against this tree; fences build with `-proc:none`, a failure names its document and fence, and the contracts gate guards the compiling list |
| stage2-k1 | `fd6c206` | `ModuleContext.cron(expression, task)` is the only scheduling door and `CronSchedule`/`CronScheduler`/`CronContribution` stay package-private, so the API baseline grew by one line: the runtime arms wall-clock crontab fire times on daemon threads, closes the pool as the first cleanup component, refuses an expression that could never fire, and reports a throwing task as `runtime.cron.failure` without disarming its next occurrence |

## Decisions that differed from the plan

**`McpToolBuilder` stayed; the reflection overload went.** The plan said to
delete the builder and keep `McpRouter.tool(Route, McpHandler)`. In practice the
builder is the only hand-authored MCP entry a user needs when a tool has no HTTP
route, while the reflective overload was a *second* way to describe the same
tool. Deleting the duplicate, not the ergonomics, is what the constraint asks
for.

*That reasoning expired in `stage2-j2`, and the builder is gone.* Two premises
failed: `Route` is a record, so a tool with no HTTP route is still a route —
`Route.of("GET", "/user", (McpHandler) ctx -> ...)` is authorable without ever
being assembled or served, which is exactly what the builder was for — and the
two-argument door it was kept in favour of did not read `route.handler()` at
all, so tests could hand it `new Object()` and pass. The ergonomics the paragraph
defended were not the builder's: a tool's name, parameters, description and
permission are all spelled by the same contract the HTTP side spells, and the
builder's two remaining capabilities moved to where the fact already lives
(accepted enum values → the parameter's declared `schema()`, friendly
label→code translation → the handler that uses it). `McpRouter.tool(Route)` is
one door now.

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

**`McpToolBuilder` lost its private type table; the door itself closed one stage
later.**
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
`stage2-j2` finished the half that was left open: the builder, `McpEnumValue` and
`McpRouter.tool(String, String)` are deleted, `McpParam` takes the contract alone,
and the sentence above about naming arguments in Java is now history rather than
a known gap.

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

**Every module guide compiles now, and the list that decides that is guarded.**
`stage2-j4` gave the last four guides -- `docs/aura-module.md`, `aura-db.md`,
`aura-mcp.md`, `aura-processor.md` -- `aura:compile` fences, which took the gate
from 7 fences to 12 and made its `docs=(...)` list the thing worth defending: a
guide dropped from that list stops being compiled and nothing else notices, so the
contracts gate now fails with `documentation stopped compiling a module guide`. Two
details the new coverage produced rather than expected. `aura-processor` is
deliberately kept off the fence classpath: the first run after it was added printed
"annotation processing is enabled because one or more processors were found on the
class path" for every fence, because that module's own
`META-INF/services/javax.annotation.processing.Processor` entry made the doc gate a
build that generates sources; the javac call now says `-proc:none`, so processing
cannot come back through a dependency. And a fence failure used to be only a javac
symbol error, which names a class but not the page a reader would copy it from --
`verify-docs-code.sh` now prints which document and which fence failed.

What the four fences actually pin is different from what the older ones pinned.
`module-composed-application` is the first place all four adapter factories
(`WorkflowModule.create()`, `DatabaseModule.create(db)`,
`AiModule.create(FakeAiProvider.returning(...))`, `McpModule.create()`) are checked
beside `authorize(...)`/`authenticate(...)` in one expression -- the document's
headline claim that a module is a Maven dependency and an explicit `use(...)`, which
prose could only assert. `mcp-tool-from-route` compiles the `stage2-j2` door
end to end: a `Route` with an `McpHandler`, an enum parameter carrying its declared
`{"type":"string","enum":[...]}` schema, and `withHandler` projecting an already
served HTTP route. `db-query-builder-and-bounded-page` compiles the chain the
reference lists separately (`where`, `whereIf`, `IN` expansion, `orderBy`, `limit`,
`offset`) together with `paginateDynamic(..., PaginationOrder, int, int,
TotalStrategy)` and `Page.total()` read as the `OptionalLong` it is, so the
"no total is not zero rows" promise is a compiling statement rather than a comment.
`processor-annotated-service` compiles the annotation surface -- `@Path`, `@Get`,
`@Post`, `@Desc`, `@NotBlank` on a record component -- which is the input the
processor reads and the only half of that document a reader writes by hand; the
generated class it mentions cannot be fenced, because it exists only after the
processor runs.

One prose line changed with it: `docs/aura-module.md` declared a lambda route's query
parameter as `"string"`, while the compiled fence says `"String"`. The gate did not
force that -- `McpUtil.isWireName` accepts a JSON wire name, so both render -- but the
document held two vocabularies for the same field, and the catalog's own vocabulary is
the Java type name.

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

**MCP got one declaration door, and the two facts the builder kept moved to the
places that already held them.** `McpRouter.tool(String, String)` and
`McpToolBuilder` are deleted, so a tool is a `Route` -- and a `Route` is a record
that can be built without being served, which removes the only reason the builder
was defended. What the builder still did for itself had to go somewhere, and each
destination was already in the system:

- *Accepted values.* The builder turned an enum class or a `Map` of labels into
  `McpEnumValue` records and `McpUtil.inputSchema` published them as `enum`, so
  the same set of choices existed in three representations with MCP owning one of
  them. Now `RouteCatalog` and `AuraRouteProcessor` put
  `{"type":"string","enum":[names]}` in the parameter's `schema()` for a body or
  an enum, OpenAPI renders it, and MCP renders the identical map through the
  identical `ApiParameter`. `ctx.getEnum(name, Type.class)` binds by `name()`, so
  the strings a model is told to send are the strings the handler accepts. Two
  mutations, one per link: `RouteCatalog`'s `body || type.isEnum` reduced to
  `body` turned exactly `RouteCatalogTest.describesCollectionAndEnumParametersWhereTheBinderReadsThem`
  red (`expected: "string" but was: null`), which is the declaration side; and
  `McpUtil.inputSchema` made to ignore `contract.schema()` turned exactly 5
  aura-mcp cases red -- the enum projection in `McpRouterTest`,
  `McpUtilTest.inputSchema_projectsTheEnumChoicesItsContractDeclares`, the body
  passthrough in both, and the protocol conformance case -- which is the render
  side. `OpenApiGeneratorTest` stayed green under the first mutation because it
  declares the schema itself in the fixture, so the assertion there is the
  generator's, not the catalog's; the catalog line is the one the release gate
  checks.
- *Label-to-code translation.* The builder's `param("city", String.class, "城市",
  Map.of("北京","010"))` stored a framework-side map and `McpContext.resolve`
  applied it before every getter, which meant `getString("city")` returned a
  value the model never sent. The mapping is the handler's business now -- a plain
  lookup in the lambda that uses it -- and `McpContext` has no mapping table, so
  the arguments it exposes are the arguments that arrived.
- *The name.* `McpUtil.buildToolName(method, path)` derives `get_user` from
  `GET /user`, the way the HTTP bridge already named a proxied route. A name an
  author types and a name the registry derives are two spellings that can drift;
  there is one spelling now, and `Route.withHandler(...)` is what projects an
  already-served endpoint instead of re-declaring it.

Both refusals are coded, because both were silent before: a route whose handler
belongs to another transport failed as a `ClassCastException` at invoke time
(`AURA_MCP_HANDLER_MISMATCH` names the method, the path and the fix), and a
parameter the Java-to-JSON table cannot render published itself as a string
(`AURA_MCP_PARAM_SCHEMA_MISSING` names the parameter and its declared type). The
second lives on `McpParam`'s constructor so the HTTP bridge cannot skip it, and
`McpRouter.tool` re-throws it with the route that produced it.

**The tools/list document is read as a document now, not as a bag of maps.** J2
closed the declaration side of MCP; the checking side still cast `buildSchema()`
into `Map<String, Object>` at every level -- 24 suppressions in its tests and 4 in
`src/main`, plus five `toJavaObject(Map.class)` calls on
`com.alibaba.fastjson2.JSONObject` values -- a type that already *is* a
`Map<String, Object>`, so each call copied the document to declare what it was --
and one suppression that sat on `McpContext.getEnum`, a method with no cast in it.
An unchecked cast is not a neutral way to read a wire document: when `properties`
became `params`, the cast did not complain, `schema.get("properties")` returned
`null`, and the failure surfaced two lines later as `NullPointerException` or as an
assertion about the wrong thing. `McpWire` -- test code only, no production
dependency -- walks the document by key names, so a renamed or missing key reports
the key that was looked for and the keys the document actually holds. The module
now carries one suppression, in
`McpRouterIntegrationTest`, and it is not a wire document: that test asserts on the
`Map` a handler returned inside the JVM, where the generic type is the handler's
own signature.

Reading the document honestly also let one invariant be stated. A tool published
for a route with no security metadata carries neither `x-aura-authenticated` nor
`x-aura-permission` -- `routeSecurityMetadataProtectsGeneratedTool` now asserts the
metadata for `requireAuth("orders.delete")` and the absence of both keys for a plain
route in the same case. The half that was missing was not assertable through a cast:
`tool.get("x-aura-authenticated")` is `null` when the key is absent, when the key is
renamed, and when an enclosing cast produced a `null` three levels up, so an
"absent" check could not tell a guarantee from a typo. `McpWire` reports the keys the
document actually has, which is what makes absence a fact worth checking.

**Cron is a contribution, and only the door is public.** `docs/ROADMAP_ANALYSIS.md`
§1.3 asked for `context.cron("0 9 * * *", () -> ...)` beside `context.route(...)`, and
that is what `stage2-k1` is: the module names a wall-clock expression and a `Runnable`
during `install`, the runtime arms it when it starts, and the pool closes on the same
path that stops the adapters and the modules. The alternative the philosophy might have
picked -- contribute a `Scheduler` capability and let each module own it -- is the hand
written `ScheduledExecutorService` that section's pain point complains about, with a
`shutdown` the module has to remember and a task that outlives the runtime if it does
not.

What stayed out of the public surface is the deliberate half. `CronSchedule`,
`CronScheduler` and `CronContribution` are package-private in `io.aura`, so the only new
line in the API baseline is the door itself. Publishing `next(…)` would hand an
application a fire time to arm its own pool with, which is a second scheduling path
wearing the semantics of the first.

Two crontab rules were chosen over the alternatives a reader might expect. A day
matches day-of-month **or** day-of-week when both restrict it, because that is what a
crontab line does; Quartz's exclusivity would need a `?` field Aura does not have. And a
day-of-month that no selected month has -- `0 0 31 2 *` -- is refused at parse time
rather than accepted and never run: crontab tolerates the typo and goes silent, and a
schedule that silently never fires is the failure mode a model cannot notice. The refusal
applies only when the day comes from that narrowing: with a restricted day-of-week the
union keeps `0 0 31 2 1` alive on February Mondays, which the tests pin so the check
cannot be widened by accident.

`@Schedule` -- §1.3's second form, generated by `aura-processor` -- is not part of this
stage. It compiles to the same door, so it adds an authoring form rather than a
capability, and it is recorded under Still open instead of being assumed done.

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
| Reflective route registration — `ModuleContext.service(Object)` and `AuraWebRuntime.services()` (the list whose `@Path` entries were scanned into routes), `BaseRouter`/`Router`'s `<verb>(path, target, "method")` overloads and `crud(...)` | removed, no replacement: `context.route(Route.of(method, path, handler))` — or `router.<verb>(path, new LambdaHandler(service))`, or a generated `new XAuraRoutes(service).routes().forEach(context::route)` — is the only door; nothing dispatches by method name or scans a class to find a route. `AuraApp::authenticate` itself is unchanged; only the reflection beside that row's old label is gone |
| `ServiceRegistrar`, `MethodRefHandler`, `StartupCheck` and the reflective DI auto-wiring in `ServiceResolver` (all package-private) | gone, no replacement: the runtime instantiates nothing by reflection. Objects arrive through the explicit `context.register(value)` door and leave through `require`/`find` |
| `io.aura.HealthModule` | `io.aura.web.HealthModule` |
| `io.aura.web.BaseHandler`, `BaseContext`, `BaseRouter`, `BaseRouteBuilder`, `BaseExceptionHandler`, `SseEmitter`, `UploadedFile`, `RouteResponse`, `RequestType`, `WsHandler`, `WsConnection`, `AdmissionPolicy`, `AdmissionRequest`, `AdmissionDecision` | same types in `io.aura.port`; `io.aura.web` is the Undertow adapter only |
| `io.aura.web.ParamResolver.fromString`, `.isBody`, `.parseInt`/`.parseLong`/`.parseBool`/`.parseDouble`/`.parseFloat`/`.parseShort`/`.parseByte`/`.parseChar` and the eight boxed overloads | `io.aura.contract.ScalarType.fromString(value, type)` and `ScalarType.isBody(type)`; the coercion rules are private, so no transport asks a parse method for one type again |
| `McpRouter.tool(String name, Object service, String method, String description)` reflective binding | `McpRouter.tool(Route)` |
| `McpRouter.tool(String, String)` and the `McpToolBuilder` it returned | the same `Route` spelling an endpoint uses: `Route.of("GET", "/user", (McpHandler) ctx -> ...).describedAs(...).withParams(List.of(new ApiParameter(...)))` — one declaration door, with the name derived from method and path |
| `io.aura.mcp.McpEnumValue`, `McpParam.enumValues()`, `McpParam.isEnum()` | the parameter's declared `schema()`, which `RouteCatalog`/`AuraRouteProcessor` fill from `TypeSchema.describe` and both OpenAPI and `tools/list` render |
| `McpParam(String, String, String, List)` and `McpParam(String, String, String, List, boolean)` | `McpParam(ApiParameter)` — a tool argument is declared with the shared parameter record and nothing else, so it cannot carry its own Java-to-JSON opinion |
| `McpTool(String, String, List, McpHandler)` and the five-argument overload | the record's six-component constructor; `permission` and `authenticated` are no longer optional at the type level |
| `McpContext`'s label→code `mappings` table and `resolve()` | a lookup in the handler that needs it; `getString`/`getInt`/`getLong` now return the argument the model sent |

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

- `McpToolBuilderTest` (21 cases) and 8 cases of `McpContextTest` disappeared with
  the surfaces they tested, and the facts they pinned are asserted again at the
  door that replaced them: the builder's parameter-type, enum-projection and
  required/optional cases are now `McpRouterTest`/`McpRouterIntegrationTest`
  cases spelled as a `Route`, and the label→code behaviour is
  `labelMapping_livesInTheHandlerThatUsesIt` plus
  `tool_handlerOwnsTheLabelToCodeMapping`. Four cases went for the reason they
  should be said out loud: `McpRouterTest` had both
  `tool_enumParam_schemaContainsEnumValues` and `tool_enumParam_schemaOutputCorrect`
  for one projection, and they are one case now
  (`tool_enumParam_projectsTheChoicesItsContractDeclares`);
  `tool_enumWithoutLabel_usesNameOnly` tested a label format the system no longer
  produces. Two new refusals joined them --
  `tool_routeHandledByAnotherTransport_failsAndNamesTheRoute` and
  `tool_paramTheJsonTableCannotRender_failsInsteadOfCallingItAString` -- neither
  of which had a builder equivalent, because the builder could not express the
  mistake. aura-mcp goes 215 to 182 and the per-class deltas account for all 33:
  -21 the deleted class, -8 the mapping table's own tests in `McpContextTest`,
  -7/+4 in `McpRouterTest` (four `tool_mapMapping_*` cases and
  `tool_enumWithoutLabel_usesNameOnly` gone, the two enum-schema cases merged),
  -4/+2 in `McpRouterIntegrationTest` (two renames plus the two cases whose
  subject was a mapping the framework applied), +1 in `McpUtilTest`.

No assertion was weakened to make a stage pass.

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
transport door, the two registration refusals -- `McpToolBuilder.declared` was
that refusal's home when review-F ran, and `stage2-j2` moved it to `McpParam`,
which the HTTP bridge passes through as well); removing the binder's
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

`stage2-j2` was falsified at the gate, because its subject is a surface that no
longer exists to be called. Eight assertions, one mutation apiece, each green
again on restore: reintroducing a `McpParam(ApiParameter, List)` constructor
failed with `McpParam grew a second way to declare an argument besides
ApiParameter`; re-adding a `McpToolBuilder.java` failed with
`the second MCP declaration door returned: McpToolBuilder`, and the same check
fired for `McpEnumValue.java`; adding a `tool(String name, String description)`
overload back to `McpRouter` failed with `McpRouter grew a second tool
registration door`; deleting the `AURA_MCP_HANDLER_MISMATCH` refusal failed with
`McpRouter accepts a route whose handler it cannot invoke without naming the mistake`;
removing `AURA_MCP_PARAM_SCHEMA_MISSING` from `McpParam` failed with
`an undescribable MCP parameter reaches the model as a string again`; putting
`prop.put("enum", ...)` back into `McpUtil` failed with
`MCP invented enum values instead of projecting the contract's schema`; and
dropping the schema declaration failed with its own message at each producer --
`the route catalog stopped declaring the parameter schema that MCP and OpenAPI
project`, and `the annotation processor stopped declaring ...`.

Two of those runs taught something about how tight a `rg` assertion should be,
and both are recorded rather than smoothed over. The first attempt at the
`RouteCatalog` check matched on `TypeSchema.describe` alone and stayed green:
the method appears twice in the file, once for a parameter and once for a
response, so the pattern was tightened to the parameter ternary
`? TypeSchema.describe(type) : Map.of()`. Even then, narrowing the condition
(`body || type.isEnum` down to `body`) leaves the declaration site standing and
so leaves the gate green -- correctly, because a gate pins where a fact is
declared and a test pins what the declaration says. That second case is the
`RouteCatalogTest` red described below, and the pair is what makes the check
worth keeping.

The control mutation renamed `rendersWithoutSchema` in `McpUtil` and its caller
together: the gate stayed green, because the invariant still held -- a rename is
not a regression, and a check that fired on one would be a check on spelling.

Every new check is written `if ! rg -q ...; then echo "<the invariant>" >&2; exit
1; fi`. The two bare `rg -q` assertions from the ports stage exited 1 silently and
were rewritten for the same reason; the J2 block was written in the named form
from the start.

The projection itself was falsified at both links: `RouteCatalogTest` ×1 and five
aura-mcp cases, described in the decision above.

The documentation half was falsified at its own gate. `AI_GUIDE_MCP.md` -- the file
that taught the builder chain to every reader who came through MCP -- joined
`canonical_docs`, and one line of builder prose written back into
`docs/aura-mcp.md` failed `verify-docs-code.sh` with
`documentation teaches the deleted MCP tool builder` and exit 1 after the line's
file and number; removing it returned the gate to `7 compiled fences`. The ban
covers `McpToolBuilder`, `mcp.tool("` and `router.tool("` in the fourteen canonical
documents and in `.claude/skills/aura/SKILL.md`, which is the file an AI actually
reads.

`mvn -o clean verify` at the end of `stage2-j2` is green with no failure-ignore:
aura-contract 30, aura-core 213, aura-processor 5, aura-web 479, aura-db 182,
aura-mcp 182, aura-ai 93, aura-workflow 53, examples 2 + 2, zero `[ERROR]` lines.
Both gates pass, and the API baseline was regenerated rather than edited: 2683
lines to 2663, with the diff exactly 24 removals and 4 additions -- `McpEnumValue`
and `McpToolBuilder` in full, `McpParam(ApiParameter, List)`, `enumValues()`,
`isEnum()`, `McpRouter.tool(String, String)`, `McpRouter.tool(Route, McpHandler)`
and the two extra `McpTool` constructors out; `McpParam(ApiParameter)`,
`McpRouter.tool(Route)`, `McpUtil.isWireName` and `McpUtil.rendersWithoutSchema`
in. Nothing else moved, which is the check that says a deletion deleted only what
it named.

`stage2-j3` added three assertions to the same gate and falsified each one. The
per-file check was turned red by restoring the pre-stage `McpUtilTest` from
`HEAD` -- the version that read `buildSchema()` with casts and named no `McpWire` --
which failed with `MCP schema tests read the tools/list document with unchecked
casts again: McpUtilTest` and returned to green on restore. The bound was turned
red by putting one unchecked read back into `McpOutputTest` while every file still
named the reader, which failed with `the MCP module carries 2 unchecked casts; its
wire documents are read through McpWire` -- the message that fires names the count,
so a regression is reported as a quantity rather than as a boolean. The third
assertion was falsified by the stage itself rather than by a mutation: the first
gate run after the block was written exited 1 with `the MCP module cast a JSON
object into a raw Map instead of using the Map it already is`, because two of the
five `toJavaObject(Map.class)` calls were still in `src/main`; the check caught an
incomplete fix, which is the only evidence that matters from a gate.

The stage moved no case count. The four schema tests hold the same 52 cases before
and after (`McpUtilTest` 17, `McpRouterTest` 24, `McpRouterIntegrationTest` 9,
`McpOutputTest` 2) and the module still runs 182; what changed is how those cases
reach the document. `McpWire` is test code, so the public API baseline did not move
-- the gate's classifier half reported `Aura public API classifier passed` against
the `stage2-j2` snapshot unchanged. `mvn -o clean verify` is green at the end of the
stage with the same per-module numbers as J2, and `verify-docs-code.sh` still reports
its then-count, `7 compiled fences` (`stage2-j4` raised it to 12).

`WebSocketTest.connectionLimitRejectsExcessAndReleasesPermitOnClose` broke the first
full reactor run of this stage -- `Expecting AtomicInteger(1) to have value: 1 but
did not`, a message that is itself the proof of the race, because the counter was
read once for the comparison and again for the report and disagreed with itself in
between. The client's `buildAsync(...).join()` completes at handshake; the
`onOpen` handler increments on an Undertow thread, so the assertion at the top of
the case tested a deadline, not the limit. It now waits for the count it checks,
like the reconnect loop further down in the same case already did. The wait is
load-bearing and was shown so: `awaitCount(opens, 1)` changed to expect a count the
limit cannot reach at that point failed with `Expecting AtomicInteger(1) to have
value: 2`, and the rerun with the original bound is green. This is the second flake
found by a full reactor run rather than a rerun, and it is the same shape as the
first (`LiquibaseSchemaMigratorTest`'s budget): a socket-backed test whose timing
assumption was machine-dependent. `DeploymentSecurityTest` is still open.

`stage2-j4` was falsified four times, twice against the documentation gate and twice
against the contracts gate. Renaming one marker (`<!-- aura:compile name=... -->` to
`<!-- compile ... -->`) failed with `no compilable fence marked in
.../docs/aura-db.md` and exit 1 -- the per-document floor written in the earlier
documentation stage did its job for free, which is the sign a floor was the right
shape. Changing a call inside that same fence (`PaginationOrder.by(...)` to
`PaginationOrder.sort(...)`) failed with javac's `错误: 找不到符号` and then the new
line `documentation fence 'db-query-builder-and-bounded-page' in
.../docs/aura-db.md no longer compiles`. Dropping `aura-db` from the gate's
`doc_modules` -- the list that decides which `target/classes` a fence may name --
failed five symbols deep in `module-composed-application`, before the db fence was
even reached, which is the honest version of that evidence: the composing guide
borrows the database module's types too, so the classpath list is load-bearing for
documents other than the one that motivated it. The fourth mutation removed
`docs/aura-mcp.md` from the gate's `docs` list while leaving its marker in place, and
only the new contracts-gate assertion noticed: `documentation stopped compiling a
module guide: docs/aura-mcp.md is not in the compiling list`. Restoring each returned
the gate to `12 compiled fences`, and `mvn -o clean verify` is green behind it with
the same per-module numbers as `stage2-j3` -- the stage added no test and changed no
public surface, because a fence is documentation the compiler reads.

`stage2-k1` added 24 aura-core tests and 2 aura-web tests, and the core three classes
run in 0.12 s -- the schedule arithmetic is pure, and `CronSchedulerTest`
injects a `ScheduledExecutorService` that records arming instead of waiting on it, so no
test in the repository waits for a minute boundary. Fourteen mutations made the new code
prove itself. Six were against the contracts gate -- giving `AuraApp` its own
`schedule(...)` method, arming with `scheduleAtFixedRate`, dropping `setDaemon(true)`,
renaming `AURA_CRON_TASK_FAILED`'s event, deleting a code from the module guide's failure
table, and adding a second `cron` overload to `ModuleContext` -- each failing with its
own message. Eight were against the tests -- removing the `closed` guard, the arming
call, the `stopCronScheduler` calls, the strictly-after minute, the `% 7` Sunday alias,
the union rule, the empty-day check and the blank-expression check -- each turning the
matching test red. Every mutation restored
green, `docs/aura-module.md` gained the `module-cron-reminders` fence (the documentation
gate now compiles 13 fences), and the regenerated public API baseline differs from the
committed one by the single `cron(java.lang.String, java.lang.Runnable)` line.

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

- `@Schedule`, the second form `docs/ROADMAP_ANALYSIS.md` §1.3 sketches, is not
  generated: `aura-processor` visits `@Path` services only, so an annotated method on a
  class the processor never sees would contribute nothing. It compiles down to the same
  `context.cron(...)` door, so the work is teaching the processor to visit a second
  annotation and where a scheduled method without a `@Path` owner lives -- not a new
  runtime path.
- An armed schedule has no read-only surface. Routes appear in `/__schema__`; a timer is
  visible through its daemon thread name, its `runtime.cron.failure` event and the log,
  which is enough to notice one that stopped but not to list what an application armed.

- Every canonical module guide now compiles: `stage2-j4` added the last four, so a
  moved type in a guide's example fails the build. What is still prose-only is the
  entry documents -- `README.md`, `README_CN.md`, `AI_GUIDE_MCP.md`,
  `AI_GUIDE_OPS.md` -- which the gate scans for legacy API names but does not
  compile, because their blocks are JSON, bash and properties rather than Java.
- The ten compiled documents hold one fence each at the floor; a guide whose example
  is deleted entirely falls to the `no compilable fence marked in` message, but a
  guide that keeps one thin fence can still describe the rest of its module in words
  the compiler never sees.
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

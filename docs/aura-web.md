# aura-web 1.0

`aura-web` is the optional Undertow adapter. It compiles the `Route` values
contributed by `AuraModule`s and uses that single record for real HTTP,
`TestClient`, `/__schema__`, OpenAPI, and MCP discovery.

Everything in this package is mechanism. The handler and context types a module
implements -- `BaseHandler`, `BaseContext`, `SseEmitter`, `WsHandler`,
`AdmissionPolicy` -- are ports in `io.aura.port` (`aura-core`), so the package
name alone tells you whether a type carries Undertow with it.

## Routes

```java
public final class OrdersModule implements AuraModule {
    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("orders", "1.0.0");
    }

    @Override public void install(ModuleContext context) {
        context.route(Route.of("POST", "/orders",
                (BaseHandler) request -> {
                    CreateOrder body = request.bodyOrThrow(CreateOrder.class);
                    request.status(201).json(orderService.create(body));
                })
                .describedAs("Create an order"));
    }
}
```

A handler is required, so a route cannot be contributed without one.
Description, parameters, return type, response schema, streaming, and auth are
typed fields on the same `Route`; there is no metadata map, so a misspelled key
cannot drop a route from the schema.

A route is contributed either by the optional `aura-processor` (annotations
compiled into a `Route` set) or by hand through `context.route(...)` and the
router; `ApiParameter` declarations supply the request metadata the compiler
cannot see. There is no reflection-based route registration — the `Route` value
is the only thing the runtime reads, so what you contribute is exactly what is
published.

## Parameter binding and `required`

`ParamResolver.isRequired(type, source)` is the one rule that decides whether a
parameter is required: a primitive or a path segment, because that is what a
handler's signature cannot hold as `null`. The binder enforces it — a missing
`int`/`long`/`double`/`boolean`/`char` or path parameter answers
`400 AURA_PARAMETER_REQUIRED` with the parameter's name in `field` — and
`RouteCatalog` publishes exactly that answer in `/__schema__` and OpenAPI, so the
document a model reads and the server it calls cannot disagree. Everything else
is nullable and arrives as `null`: a `String` query parameter the client skipped,
and a request body, which is why a body is not marked required even though the
handler can choose to reject `null` itself with `bodyOrThrow`. Declare the boxed
type when a missing value is a value you want to see.

The Java type on a parameter is what the schema renderers turn into JSON:
`ScalarType` answers for `openapi.json`, `/__schema__` and MCP `inputSchema`
alike, and the `example` curl in the schema document is generated from the same
table, so replaying an example a model copied binds.

## Context

Common request methods:

| Method | Purpose |
|---|---|
| `path`, `pathInt`, `pathLong` | Bound path parameters |
| `query`, `queryRequired`, `queryInt`, `queryLong`, `queryBool` | Query values |
| `body`, `bodyOrThrow` | Bounded JSON body binding and validation |
| `header`, `cookie`, `formField`, `file` | Request metadata and uploads |
| `pageNum`, `pageSize` | Validated pagination |
| `ip` | Peer address or trusted-proxy forwarded address |
| `cancellationToken`, `isCancelled` | Timeout/disconnect/shutdown cancellation |

Common response methods:

| Method | Purpose |
|---|---|
| `status` | Set status code |
| `json`, `jsonRaw`, `text`, `html`, `raw` | Write a response |
| `header`, `cookie` | Response metadata |
| `redirect` | Validated redirect without CRLF injection |
| `sendFile` | Attachment response |
| `sse` | Server-Sent Events emitter |

Request bodies, form fields, uploads, and external response reads have explicit
limits. Upload APIs do not expose an unbounded `readAllBytes()` path.

## TestClient

```java
AuraApp app = AuraApp.create()
        .use(new OrdersModule())
        .prepare();

TestClient client = TestClient.of(app);
client.post("/orders")
        .body(new CreateOrder("sku-1", 2))
        .expect(201);

Map<String, Object> schema = client.schema();
Map<String, Object> openApi = client.openApi();
```

`Request.remoteAddr(ip)` names the TCP peer and `Request.secure(true)` marks the
request as TLS, which is how a test exercises trusted-proxy rules and HSTS.
Without `remoteAddr`, a trusted-proxy `X-Forwarded-For` is ignored exactly as it
is in production.

`TestClient` installs the same modules, compiles the same routes, runs the same
pre-route guard chain (Host allow-list, admission and rate limits, security
headers, CSRF, CORS, and the gates on `/__schema__`, `/openapi.json` and a
WebSocket `Origin`), applies the same binding and exception resolution, and
returns the same structured errors as Undertow. Asking it for `GET /__schema__`
reads the route map through the same projection a socket serves, so a schema
token is testable in milliseconds; asking for `GET /openapi.json` honours the
`openApi` switch. It can only observe one request at a time, so concurrency
bulkheads still need a socket test. Use a real Undertow socket test for transport
behavior such as TLS, compression, multipart parsing, SSE, WebSocket, and
disconnects.

## Structured Errors

Use `ApiException` or another `CodedException` for expected failures. Responses
include a stable `code`, location/field when relevant, reason, and repair hint.
Production 500 responses do not include raw exception messages or stack traces.

## Schema And OpenAPI

`/__schema__` is disabled in production unless explicitly protected or enabled
by the application policy. `/openapi.json` requires `AuraConfig.openApi(true)`
outside explicit dev mode. Both are generated from the same compiled routes.
Both gates are guard-chain decisions (`BuiltinEndpointsGuard`), so `TestClient`
applies the schema token, the OpenAPI switch and the WebSocket Origin allow-list
exactly as a socket does — see the verification section above.

## SSE And WebSocket

`request.sse()` returns an `SseEmitter`. Event names and IDs are sanitized,
streaming routes are excluded from the normal request timeout and gzip path,
and application protocols must bound queues and replay history. Declare such
routes with `Route.of(...).withStreaming(true)`.

WebSocket handshakes enforce the configured Origin policy. Missing Origin is
not implicitly trusted when the application requires browser-origin checks.
Business modules contribute endpoints explicitly, and an endpoint is one
`Route` like any other: it is published in `/__schema__` as a streaming `WS`
route, stays out of the OpenAPI document, and is refused before the protocol
changes when it requires a user it cannot identify.

```java
context.webSocket("/events", socket -> socket
        .onOpen(connection -> connection.send("ready"))
        .onMessage((connection, text) -> connection.send("echo:" + text))
        .onClose((connection, code, reason) -> { }));

// A handshake that needs a permission declares it through the same contract:
context.route(Route.webSocket("/rooms/{roomId}", socket -> socket
        .onMessage((connection, text) -> connection.send("echo:" + text)))
        .requireAuth("rooms.write"));
```

## Production Controls

Configure controls once with `AuraConfig` before creating the app:

```java
AuraConfig config = new AuraConfig()
        .allowedHosts("api.example.com")
        .trustedProxies("10.0.0.0/8", "fd00::/8")
        .publicOrigin("https://api.example.com")
        .securityHeaders(true)
        .maxBodySize(10 * 1024 * 1024L)
        .maxUploadSize(1024 * 1024L)
        .maxWebSocketMessageSize(1024 * 1024L)
        .maxWebSocketConnections(10_000)
        .webSocketIdleTimeout(60)
        .requestTimeout(30)
        .concurrencyLimit(200)
        .rateLimitPerSecond(100)
        .rateLimitMaxKeys(65_536)
        .gzip(true);

AuraApp app = AuraApp.create(config).use(new OrdersModule());
```

Trusted proxies are explicit. Forwarded headers from untrusted peers are
ignored. Credentialed CORS requires explicit origins; wildcard credentials are
rejected. Dynamic Origin responses include `Vary: Origin`.

Inbound WebSocket text and binary messages are buffered only up to
`maxWebSocketMessageSize`; oversized messages are closed with status 1009 before
the application message handler runs. `maxWebSocketConnections` is a global
permit bound across every route. Idle connections close with status 1001 after
`webSocketIdleTimeout`, and application shutdown sends 1001 to all active
connections before the listener stops.

## Admission Policy

`AuraConfig.rateLimitPerSecond` and `concurrencyLimit` provide simple default
IP/global controls. For route or API-key policies, install one explicit module
that owns the replaceable admission policy:

```java
public final class AdmissionModule implements AuraModule {
    private final StandardAdmissionPolicy policy = StandardAdmissionPolicy.builder()
            .perIp(100, 65_536)
            .perApiKey(500, 10_000)
            .routeRate("/orders/{id}", 20)
            .globalConcurrency(200)
            .routeConcurrency("/reports", 4)
            .build();

    public ModuleMetadata descriptor() {
        return ModuleMetadata.of("admission", "1.0.0");
    }

    public void install(ModuleContext context) {
        context.admissionPolicy(policy);
        context.register(policy); // optional metrics lookup
    }
}
```

When a custom policy is installed it replaces the default controls instead of
running a second hidden limiter. Requests contain method, raw path, matched
route template, validated client IP, and `X-API-Key`. Rejections return stable
`AURA_RATE_LIMITED` or `AURA_CONCURRENCY_LIMITED` codes. The standard policy
exposes bounded key count, in-flight count, and rejection counters.

## Guard Chain

Every request crosses one policy chain before it reaches a route, and both transports build that
chain from the same call: `UndertowStarter` for a live socket and `TestClient` for the in-memory
client. `TestClient` therefore rejects exactly what a real socket rejects instead of skipping
straight to the handler.

<!-- aura:compile name=web-standard-guard-chain -->
```java
package docs.example;

import io.aura.AuraWebRuntime;
import io.aura.web.RequestGuards;

public final class StandardGuardChain {
    private StandardGuardChain() { }

    /** The chain shape both transports install; the adapter supplies the route-template lookup. */
    public static RequestGuards chain(AuraWebRuntime app, RequestGuards.RoutePattern routePattern) {
        return RequestGuards.standard(app, routePattern);
    }
}
```

The order is fixed, because each step assumes the one before it already ran: Host (421),
Admission (concurrency, rate limit, or the installed `AdmissionPolicy`), SecurityHeaders, CSRF,
CORS (an `OPTIONS` preflight stops here with 204), and finally the gates on Aura's own endpoints —
`/__schema__`, `/openapi.json`, and a browser `Upgrade: websocket` handshake.

Draining is deliberately absent from this chain. Rejecting new traffic while in-flight requests
finish is a transport lifecycle job, and a per-request rule here would silence the `/health`
probes whose purpose is to answer `DOWN`.

Each rejection on an Aura endpoint is bodyless on purpose: a 404 and a 401 are the whole signal
for `/__schema__`, and an error document that names the reason discloses more than the status
code does. The schema switch and its token are independent doors — either one alone opens the
endpoint.

## Snapshots

Snapshot recording redacts credentials, query values, bodies, exception text,
stacks, and external URLs according to `SnapshotOptions`. Retention is bounded
by TTL, count, total bytes, per-file bytes, sampling, and writer queue capacity.

Replay uses the canonical application facade:

```java
SnapshotReplayer.of(app)
        .load(snapshotPath)
        .replay()
        .expectSameStatus()
        .expectSameCode();
```

`SnapshotMetrics.of(app)` exposes dropped snapshots, current queue depth,
configured capacity, async mode, and writer liveness without starting a writer.
Use `SnapshotOptions.onDrop(...)` and `onWriteFailure(...)` for alert callbacks.

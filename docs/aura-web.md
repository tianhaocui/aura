# aura-web 1.0

`aura-web` is the optional Undertow adapter. It compiles the route contributions
installed by `AuraModule`s and uses the same compiled contract for real HTTP,
`TestClient`, `/__schema__`, OpenAPI, and MCP discovery.

## Route Contributions

```java
public final class OrdersModule implements AuraModule {
    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("orders", "1.0.0");
    }

    @Override public void install(ModuleContext context) {
        context.route(new RouteContribution(
                "POST", "/orders",
                (BaseHandler) request -> {
                    CreateOrder body = request.bodyOrThrow(CreateOrder.class);
                    request.status(201).json(orderService.create(body));
                },
                "Create an order"));
    }
}
```

Generated and reflective routes both produce `ApiRoute`/`ApiParameter`
metadata. Prefer the optional `aura-processor` for production builds and keep
reflection for prototypes.

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

`TestClient` installs the same modules, compiles the same routes, applies the
same binding and exception resolution, and returns the same structured errors
as Undertow. Use a real Undertow socket test only for transport behavior such as
TLS, compression, multipart parsing, SSE, WebSocket, disconnects, and headers.

## Structured Errors

Use `ApiException` or another `CodedException` for expected failures. Responses
include a stable `code`, location/field when relevant, reason, and repair hint.
Production 500 responses do not include raw exception messages or stack traces.

## Schema And OpenAPI

`/__schema__` is disabled in production unless explicitly protected or enabled
by the application policy. `/openapi.json` requires `AuraConfig.openApi(true)`
outside explicit dev mode. Both are generated from the same compiled routes.

## SSE And WebSocket

`request.sse()` returns an `SseEmitter`. Event names and IDs are sanitized,
streaming routes are excluded from the normal request timeout and gzip path,
and application protocols must bound queues and replay history.

WebSocket handshakes enforce the configured Origin policy. Missing Origin is
not implicitly trusted when the application requires browser-origin checks.
Business modules contribute endpoints explicitly:

```java
context.webSocket("/events", socket -> socket
        .onOpen(connection -> connection.send("ready"))
        .onMessage((connection, text) -> connection.send("echo:" + text))
        .onClose((connection, code, reason) -> { }));
```

## Production Controls

Configure controls once with `AuraConfig` before creating the app:

```java
AuraConfig config = new AuraConfig()
        .allowedHosts("api.example.com")
        .publicOrigin("https://api.example.com")
        .securityHeaders(true)
        .maxBodySize(10 * 1024 * 1024L)
        .maxUploadSize(1024 * 1024L)
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

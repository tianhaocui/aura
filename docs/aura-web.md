# aura-web

HTTP server, routing, request/response context, middleware, and testing.

## Router

Route registration with middleware, grouping, and exception handling.

### Route Methods

```java
// Lambda handler
r.get("/path", ctx -> ctx.json(data));
r.post("/path", ctx -> ctx.json(ctx.body(Req.class)));

// Method reference (auto parameter binding)
r.get("/user/{id}", userService, "get");
r.post("/user", userService, "create");

// CRUD shortcut (registers up to 5 routes)
r.crud("/user", userService);
```

**Route priority**: exact paths always beat parameterized paths regardless of registration order.
`/api/items/search` matches before `/api/items/{id}` even if `{id}` was registered first.

### RouteBuilder (chaining after route registration)

```java
r.get("/user/{id}", userService, "get")
 .describe("Get user by ID")
 .param("id", "User ID");
```

### Middleware

```java
r.before(ctx -> { /* runs before handler */ });
r.after(ctx -> { /* runs after handler, even on error */ });
```

### Grouping

```java
r.group("/api", api -> {
    api.before(authMiddleware);
    api.get("/items", itemService, "list");
    api.post("/items", itemService, "create");
});
```

### Exception Handling

```java
r.exception(NotFoundException.class, (e, ctx) -> ctx.status(404).json(Map.of("error", e.getMessage())));
r.exception(Exception.class, (e, ctx) -> ctx.status(500).json(Map.of("error", "Internal Error")));
```

### CRUD Convention

```java
r.crud("/user", userService);
// Scans service for methods named: get, list, create, update, delete
// GET    /user/{id}  → get(int id)
// GET    /user       → list()
// POST   /user       → create(...)
// PUT    /user/{id}  → update(int id, ...)
// DELETE /user/{id}  → delete(int id)
// Missing methods are skipped.
```

### Selective CRUD

```java
// Only register specific methods
r.crud("/user", userService, "get", "list");
// Valid method names: get, list, create, update, delete
// Invalid names throw IllegalArgumentException at startup
```

### Abort

```java
r.before(ctx -> {
    if (!isAuthenticated(ctx)) {
        ctx.status(401).text("Unauthorized");
        ctx.abort(); // skip handler, after-middleware still runs
    }
});
// ctx.isAborted() returns true after abort() is called
```

---

## Context

Request/response wrapper passed to handlers.

### Request

| Method | Returns | Description |
|--------|---------|-------------|
| `path(String name)` | String | Path parameter value |
| `query(String name)` | String | Query parameter value |
| `query(String name, String default)` | String | Query param with default |
| `queryInt(String name, int default)` | int | Query param as int (null/blank/invalid → default) |
| `queryLong(String name, long default)` | long | Query param as long (null/blank/invalid → default) |
| `queryBool(String name, boolean default)` | boolean | Query param as boolean (only "true" → true) |
| `header(String name)` | String | Request header |
| `cookie(String name)` | String | Cookie value |
| `body(Class<T> type)` | T | JSON-deserialized request body |
| `pageNum()` | int | `?page=` parsed as int (default 1, min 1) |
| `pageSize()` | int | `?pageSize=` parsed as int (default 20, max 500) |
| `file(String field)` | UploadedFile | Multipart file upload field |
| `method()` | String | HTTP method (GET, POST, ...) |
| `url()` | String | Request URI |
| `abort()` | void | Skip handler execution (before-middleware only) |
| `isAborted()` | boolean | Whether abort() was called |
| `set(String key, Object value)` | void | Store named attribute |
| `get(String key, Class<T> type)` | T | Retrieve named attribute |

### Response

| Method | Returns | Description |
|--------|---------|-------------|
| `status(int code)` | Context | Set status code (chainable) |
| `json(Object obj)` | void | Send JSON response |

**Note**: `ctx.json(row)` works directly — Row extends LinkedHashMap and serializes as a JSON object. No manual Map conversion needed. Use `.select("col1, col2")` in the query to control which fields appear in the output.
| `text(String text)` | void | Send plain text response |
| `redirect(String url)` | void | 302 redirect |

**Security**: `redirect(url)` throws `IllegalArgumentException` if the URL contains `\r` or `\n` (CRLF injection prevention).
| `header(String name, String value)` | Context | Set response header |
| `cookie(String name, String value, int maxAge)` | Context | Set cookie (HttpOnly + Secure) |
| `sse()` | SseEmitter | Open SSE stream (text/event-stream) |

---

## SSE (Server-Sent Events)

```java
r.get("/stream", ctx -> {
    SseEmitter sse = ctx.sse();
    sse.send("hello");                          // data: hello
    sse.send("message", "payload");             // named event
    sse.send("update", "content", "msg-1");     // with id
    sse.close();
});
```

`ctx.sse()` sets `Content-Type: text/event-stream`, disables buffering, and returns an `SseEmitter`.

### SseEmitter

| Method | Description |
|--------|-------------|
| `send(String data)` | Send `data:` event |
| `send(String event, String data)` | Send named event |
| `send(String event, String data, String id)` | Send named event with id |
| `close()` | Close the stream |

**Security**: Event names and IDs are automatically sanitized — `\r` and `\n` characters are stripped to prevent SSE injection. Multiline data is split into multiple `data:` lines per the SSE spec.

**AI streaming example:**

```java
r.post("/chat", ctx -> {
    ChatReq req = ctx.body(ChatReq.class);
    SseEmitter sse = ctx.sse();
    // stream tokens from AI model
    aiClient.streamChat(req.message(), token -> {
        sse.send("token", token);
    });
    sse.send("done", "");
    sse.close();
});
```

### Attributes

```java
ctx.set(currentUser);           // store by type
User u = ctx.get(User.class);  // retrieve by type

ctx.set("key", value);          // store by name
Object v = ctx.get("key", Object.class);

ctx.app().get(Db.class);       // access registry
```

---

## File Upload

```java
UploadedFile f = ctx.file("avatar"); // multipart/form-data field
f.name()        // original filename
f.data()        // byte[]
f.contentType() // MIME type, e.g. "image/png"
f.size()        // bytes

// Increase limit for large files (default 10MB):
Aura.create().maxBodySize(500 * 1024 * 1024L)
```

---

## Handler

```java
@FunctionalInterface
public interface BaseHandler {
    void handle(BaseContext ctx) throws Exception;
}
```

---

## ExceptionHandler

```java
@FunctionalInterface
public interface BaseExceptionHandler<T extends Exception> {
    void handle(T exception, BaseContext ctx);
}
```

---

## Error Handling

Unhandled exceptions always return JSON `{"error": "message"}`.
- `IllegalArgumentException` and `ValidationException` → 400
- All other unhandled exceptions → 500
- Dev mode (`AURA_ENV=dev`): response includes `"trace"` field with full stack trace
- Production (`AURA_ENV=prod`): only the message is shown

---

## TestClient

In-memory route execution for testing. No HTTP server needed.

```java
var app = Aura.create().service(new UserService());
var test = TestClient.of(app);

// GET
test.get("/user/1").expect(200).bodyContains("Alice");

// POST with body
test.post("/user").body(new CreateReq("tom", 25)).expect(200);

// Full response access
Response resp = test.get("/user/1").execute();
int status = resp.status();
String body = resp.body();
User user = resp.json(User.class);

// Headers
test.get("/api/data").header("Authorization", "Bearer token").expect(200);

// 404
test.get("/notfound").expect(404);

// PUT with body
test.put("/user/1").body(Map.of("name", "updated")).expect(200);

// DELETE
test.delete("/user/1").expect(204);
```

### Request

| Method | Description |
|--------|-------------|
| `header(String name, String value)` | Add request header |
| `body(Object obj)` | Set JSON request body |
| `execute()` | Execute and return Response |
| `expect(int statusCode)` | Execute and assert status |

### Response

| Method | Description |
|--------|-------------|
| `status()` | HTTP status code |
| `body()` | Response body as String |
| `json(Class<T>)` | Deserialize body to type |
| `expect(int)` | Assert status code |
| `bodyContains(String)` | Assert body contains text |

---

## MethodRefHandler

Wraps a service method for automatic parameter binding.

### Parameter Binding Rules

| Parameter type | Source | Example |
|---|---|---|
| `int`, `long`, `String` | Path param by name, then query param | `get(int id)` |
| `double`, `boolean` | Path param by name, then query param | `filter(boolean active)` |
| `Integer`, `Long`, `Boolean` | Path param by name, then query param; returns `null` when absent | `filter(Boolean active)` |
| `record` or POJO | Request body (JSON) | `create(CreateReq req)` |
| `Context` | Framework context | `handle(Context ctx)` |

### Return Value

- Non-void → auto-serialized to JSON
- void → no response body

### Direct Invocation (for MCP)

```java
Object result = handler.invokeWithArgs(Map.of("id", 1));
```

---

## Built-in Endpoints

### `GET /__schema__`

Returns full API structure as JSON:

```json
{
  "name": "App Name",
  "routes": [
    {
      "method": "GET",
      "path": "/user/{id}",
      "description": "Get user by ID",
      "returnType": "User",
      "params": [{"name": "id", "type": "int", "source": "path", "description": "User ID"}],
      "example": "curl http://localhost:8080/user/1"
    }
  ]
}
```

---

## Configuration

| Feature | How |
|---------|-----|
| CORS | `app.cors(true)` — CORS allow all origins, includes `Access-Control-Max-Age: 86400` |
| Static files | `app.staticFiles("/public")` — Serve from classpath with `Cache-Control` + `ETag` (86400s) |
| SPA mode | `app.spa(true)` — SPA mode: unknown paths fall back to `/index.html` |
| Body size limit | `app.maxBodySize(bytes)` — default 10MB |
| Graceful shutdown | `app.shutdownTimeout(seconds)` — default 30s |

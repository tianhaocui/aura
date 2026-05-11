# Aura Framework - AI Development Guide

You are developing with Aura, a lightweight Java 17+ backend framework.

## Project Setup (pom.xml)

```xml
<parent>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-parent</artifactId>
    <version>0.1.1</version>
</parent>

<dependencies>
    <dependency>
        <groupId>io.github.tianhaocui</groupId>
        <artifactId>aura-web</artifactId>
    </dependency>
    <!-- Optional: database -->
    <dependency>
        <groupId>io.github.tianhaocui</groupId>
        <artifactId>aura-db</artifactId>
    </dependency>
    <!-- Required: add your own SLF4J provider (aura-web uses slf4j-api) -->
    <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>1.5.6</version>
    </dependency>
</dependencies>
```

Always inherit `aura-parent`. It provides `-parameters` compiler flag, Java 17, and dependency version management.
Aura does not bundle an SLF4J implementation — add logback, log4j2, or slf4j-simple yourself.

## Minimal App

```java
Aura.create().port(8080)
    .get("/hello", () -> "hello world")
    .start();
```

## Route Patterns

```java
// Return value auto-serialized: String→text, Object→JSON
app.get("/path", () -> result);                    // no params
app.get("/user/{id}", (int id) -> findUser(id));   // path param
app.post("/user", (CreateReq req) -> save(req));   // body param

// Method reference (business logic)
app.routes(r -> {
    r.get("/user/{id}", userService, "get");
    r.post("/user", userService, "create");
    // crud() and other Router-specific methods require a cast inside the lambda:
    Router router = (Router) r;
    router.crud("/user", userService);  // registers get/list/create/update/delete
});

// Service class — plain Java, no annotations needed
class UserService {
    User get(int id) { ... }
    List<User> list() { ... }
    User create(CreateReq req) { ... }
    void delete(int id) { ... }
}
```

**Route priority**: exact paths always beat parameterized paths regardless of registration order.
`/api/items/search` will always match before `/api/items/{id}` even if `{id}` was registered first.

## Parameter Binding Rules

- `int/long/String` → from path param first, then query param (matched by name)
- `record/POJO` → from request body (JSON)
- `Context` → framework context object
- Return non-void → auto JSON response
- Return String → auto text response
- Return void → no response body
- Boxed types (`Integer`/`Long`/`Boolean`) → return `null` when absent; primitives (`int`/`long`/`boolean`) return `0`/`false` when absent

## Database

```java
Db db = Db.create(url, user, pass);

// Dynamic SQL with directives (recommended for complex queries)
// Null/blank params are auto-skipped — no if/else needed
String sql = "SELECT * FROM user #where(name, '=', name) #and(age, '>', age) #orderBy(created)";
db.find(sql, filterMap);                        // List<Row>
db.paginate(sql, filterMap, pageNum, pageSize); // Page<Row>

// Directives: #where(field, op, paramKey) #and(...) #or(...) #orderBy(field1, field2)
// filterMap = Map.of("name", "tom", "age", 18) → WHERE name = ? AND age > ?
// filterMap = Map.of("name", "tom")            → WHERE name = ? (age skipped)

// Query builder (simple CRUD shortcut)
db.table("user").where("age", ">", 18).find();
db.table("user").where("id", 1).findOne();

// Shortcuts
db.findById("user", id);   // returns Row with table set — can call .update()/.delete()
db.findBy("user", "active = ?", true);
db.deleteById("user", id);

// Row CRUD — insert() returns self with generated primary key populated
Row row = Row.of("user").set("name", "tom").set("age", 25).insert(db);
Object id = row.id(); // generated ID

// insertFull() — insert + re-fetch full row (includes server-generated columns like created_at)
Row full = Row.of("user").set("name", "tom").insertFull(db);
full.get("created_at"); // LocalDateTime — populated from DB

// findById → modify → update roundtrip (works natively, no type conversion needed)
Row found = db.findById("user", id);
found.set("name", "updated");
found.update(db); // timestamp columns are LocalDateTime — JDBC accepts them directly

// exclude server-managed columns from update (e.g. created_at set by DB trigger)
found.exclude("created_at", "updated_at").set("name", "updated").update(db);

// Transaction
db.transaction(() -> { db.execute(sql, args); });
```

**Type mapping**: `rsToRow` preserves JDBC types — `Timestamp` → `LocalDateTime`, `Date` → `LocalDate`, `Time` → `LocalTime`.
`row.getStr("created_at")` calls `.toString()` and works fine. `ctx.json(row)` serializes to ISO 8601 automatically.

## File Upload

```java
// multipart/form-data
UploadedFile f = ctx.file("avatar");
f.name()        // original filename
f.data()        // byte[]
f.contentType() // MIME type, e.g. "image/png"
f.size()        // bytes

// Increase limit for large files (default 10MB):
Aura.create().maxBodySize(500 * 1024 * 1024L)
```

## Pagination Helpers

```java
// Built into BaseContext — no imports needed
int page     = ctx.pageNum();   // ?page=   (default 1, min 1)
int pageSize = ctx.pageSize();  // ?pageSize= (default 20, max 500)

db.paginate(sql, params, page, pageSize); // Page<Row>
```

## Middleware and Error Handling

```java
app.routes(r -> {
    r.before(ctx -> { /* auth, logging */ });
    r.after(ctx -> { /* timing, cleanup */ });
    r.group("/api", api -> {
        api.before(authMiddleware);
        api.get("/items", itemService, "list");
    });
    r.exception(BizException.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
});
```

Unhandled exceptions always return JSON `{"error": "message"}`.
In dev mode (`AURA_ENV=dev`), a `"trace"` field with the full stack trace is added.
`IllegalArgumentException` and `ValidationException` automatically return 400.

## Configuration

```java
Aura.create()
    .port(8080)                    // or env: AURA_PORT
    .env("dev")                    // or env: AURA_ENV
    .cors(true)                    // CORS: true=allow all, or cors("https://...")
                                   // includes Access-Control-Max-Age: 86400
    .maxBodySize(10 * 1024 * 1024L) // request body limit (default: 10MB)
    .staticFiles("/public")        // serve classpath:/public with Cache-Control + ETag
    .spa(true)                     // SPA mode: unknown paths fall back to /index.html
    .mcp(true)                     // MCP Server for AI agents
    .prop("db.url", "jdbc:mysql://...")
    .onStart(a -> a.register(Db.create(...)))
    .onStop(a -> a.get(Db.class).close())
    .start(args);                  // supports --config=file --port=N --env=X
```

Properties read: env var > code `.prop()` > `aura.properties` file.

## Context API (when needed)

```java
ctx.path("id")   ctx.query("page")   ctx.header("Authorization")
ctx.body(T.class)  ctx.cookie("name")  ctx.method()  ctx.url()
ctx.pageNum()    ctx.pageSize()       ctx.file("field")   // UploadedFile
ctx.status(201)  ctx.json(obj)        ctx.text("ok")  ctx.redirect("/")
ctx.set(user)    ctx.get(User.class)  ctx.app().get(Db.class)
```

## Testing

```java
// In-memory, no HTTP server needed
TestClient client = new TestClient(app, router);
client.get("/user/1").execute().expect(200).bodyContains("alice");
client.post("/user").body(Map.of("name", "bob")).execute().expect(201);
client.put("/user/1").body(Map.of("name", "bob")).execute().expect(200);
client.delete("/user/1").execute().expect(204);
```

## Key Principles

- No XML, no YAML required, no annotations required
- Service methods are plain Java — no framework types in signatures
- One `Aura.create()...start()` is a complete app
- `aura.properties` in classpath is auto-loaded if present
- Row type roundtrip: insert with `LocalDateTime` → `findById` returns `LocalDateTime` → `update` writes back without conversion

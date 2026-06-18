# Aura Framework - AI Development Guide

You are developing with Aura, a lightweight Java 17+ backend framework.

## Query Style Decision Rule

```
db.table().where().find()          — simple CRUD (default choice)
db.findDynamic(template, map)      — multi-condition search with optional filters
db.find(sql, params)               — only for joins/subqueries
```

## Project Setup (pom.xml)

```xml
<dependency>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-web</artifactId>
    <version>0.5.3</version>
</dependency>
<!-- Optional: database -->
<dependency>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-db</artifactId>
    <version>0.5.3</version>
</dependency>
<!-- Required: add your own SLF4J provider -->
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.5.6</version>
</dependency>
```

The `-parameters` compiler flag is **required** — add `<parameters>true</parameters>` to maven-compiler-plugin.
Without it, all route parameters silently receive null/0 (app starts but every handler gets zeroed arguments).

## Minimal App

```java
Aura.create().port(8080)
    .get("/hello", () -> "hello world")
    .start();
```

## Route Patterns

```java
app.get("/path", () -> result);                    // no params
app.get("/user/{id}", (int id) -> findUser(id));   // path param
app.post("/user", (CreateReq req) -> save(req));   // body param

app.routes(r -> {
    r.get("/user/{id}", userService, "get");
    r.post("/user", userService, "create");
    r.crud("/user", userService);  // registers get/list/create/update/delete
});

// Service class — plain Java, no annotations needed
class UserService {
    User get(int id) { ... }
    List<User> list() { ... }
    User create(CreateReq req) { ... }
    void delete(int id) { ... }
}
```

**Route priority**: exact paths beat parameterized paths. `/api/items/search` matches before `/api/items/{id}`.

**`@Path` annotation**: only works with `.service()` registration. Inside `routes()` lambda, register paths explicitly.

## Parameter Binding Rules

- `int/long/String` → path param first, then query param (by name)
- `record/POJO` → request body (JSON), auto-validated if annotations present
- `Context` → framework context object
- Return non-void → auto JSON response; String → text response; void → no body
- Boxed types (`Integer`/`Long`) → `null` when absent; primitives → `0`/`false`

## Middleware and Error Handling

```java
// 全局异常处理（推荐 — 在 app 上注册，不在 router 里）
app.exception(IllegalArgumentException.class, (e, ctx) ->
    ctx.status(400).json(Result.fail(400, e.getMessage())));
app.exception(Exception.class, (e, ctx) ->
    ctx.status(500).json(Result.fail(500, "Internal error")));

app.routes(r -> {
    r.before(ctx -> { /* auth, logging */ });
    r.after(ctx -> { /* timing, cleanup */ });
    r.group("/api", api -> {
        api.before(authMiddleware);  // only /api/** requires auth
        api.get("/items", itemService, "list");
    });
});
```

Unhandled exceptions return JSON `{"error": "message"}`. In dev mode (`AURA_ENV=dev`), `"trace"` is added.
`IllegalArgumentException` and `ValidationException` automatically return 400.
**AI 写代码只管 throw，不需要 try-catch。**

## Validation Annotations

```java
public record CreateUser(
    @NotBlank String name,
    @Min(0) @Max(150) int age,
    @Size(min = 11, max = 11) String phone,
    @Pattern("[a-z]+@[a-z]+\\.[a-z]+") String email
) {}
```

Annotations: `@NotNull`, `@NotBlank`, `@Min`, `@Max`, `@Size`, `@Pattern`
Only trigger for record/POJO body parameters — NOT for path/query params.

For cross-field validation, implement `Validatable`:

```java
record DateRange(@NotNull LocalDate start, @NotNull LocalDate end) implements Validatable {
    public void validate() {
        Validate.isTrue(!end.isBefore(start), "end must be after start");
    }
}
```

## Auth

```java
// Built-in JWT (zero dependency)
app.jwt("secret");  // or env: AURA_JWT_SECRET

// Protect routes
r.group("/api", api -> {
    api.before(Aura.requireAuth());  // 401 if not authenticated
    api.get("/profile", ctx -> db.findById("user", ctx.userId()));
});

// Sign token (login endpoint)
r.post("/login", ctx -> ctx.json(Map.of("token", app.signJwt(userId))));

// Custom auth (OAuth, IAM, Redis session — anything)
app.auth(ctx -> myIamClient.verify(ctx.header("X-Token")));
```

`ctx.userId()` returns the authenticated user ID. Throws if not authenticated.

## Plugins

```java
Aura.create()
    .plugin(app -> app.register(new RedisClient("localhost")))
    .start();

public interface AuraPlugin { void install(Aura app); }
```

## Configuration

```java
Aura.create()
    .port(8080)                    // or env: AURA_PORT
    .env("dev")                    // or env: AURA_ENV
    .jwt("secret")                 // or env: AURA_JWT_SECRET
    .cors(true)                    // CORS allow all
    .cors(c -> c.origins("https://app.com").credentials(true))  // fine-grained
    .maxBodySize(10 * 1024 * 1024L)
    .requestTimeout(30)            // 503 after 30s
    .gzip(true)                    // response compression
    .accessLog("json")             // JSON structured log (or true for human-readable)
    .staticFiles("/public")
    .spa(true)                     // unknown paths → /index.html
    .mcp(true)                     // MCP Server for AI agents
    .set("db.url", "jdbc:mysql://...")
    .onStart(a -> a.register(Db.create(...)))
    .onStop(a -> a.getBean(Db.class).close())
    .start(args);
```

Properties read order: startup args > env var > `aura.properties` > code default.

## Context API

```java
// 请求
ctx.path("id")   ctx.pathInt("id")   ctx.pathLong("id")
ctx.query("page")   ctx.queryRequired("name")   ctx.queryInt("page", 1)
ctx.header("Authorization")   ctx.cookie("name")   ctx.method()   ctx.url()
ctx.body(T.class)   ctx.bodyOrThrow(T.class)
ctx.pageNum()   ctx.pageSize()   ctx.file("field")   ctx.formField("name")

// 响应
ctx.status(201)   ctx.json(obj)   ctx.text("ok")   ctx.html("<h1>Hi</h1>")
ctx.redirect("/")   ctx.sendFile("name.pdf", bytes)   ctx.sse()

// 上下文存储
ctx.set(user)   ctx.get(User.class)   ctx.app().getBean(Db.class)
```

`ctx.json(row)` works directly — Row extends LinkedHashMap, serializes as JSON object.

## Testing

```java
TestClient client = new TestClient(app, router);
client.get("/user/1").execute().expect(200).bodyContains("alice");
client.post("/user").body(Map.of("name", "bob")).execute().expect(201);
client.delete("/user/1").execute().expect(204);
```

In-memory, no HTTP server needed. AI writes code → runs TestClient → confirms it works.

## Key Principles

- No XML, no YAML, no annotations required
- Service methods are plain Java
- One `Aura.create()...start()` is a complete app
- Row type roundtrip: insert LocalDateTime → findById returns LocalDateTime → update writes back

## Important Notes

- **`-parameters` compiler flag required** — without it, all route params are null/0 with no error
- **Path param syntax is `{id}`** — NOT `:id`
- **Validation only on record body params** — path/query params are NOT auto-validated
- **DI is manual registration** — `app.register(instance)`, NOT auto-scan like Spring
- **Config is .properties only** — no YAML support
- **query params need manual access** — `ctx.query("name")`, no auto-binding
- **Record classes auto-serialize** — field name = JSON key, no annotations needed
- **`bodyOrThrow()` = body() + null check + BeanValidator** — the recommended way
- **Exception handling via `app.exception()`** — handler 里不需要 try-catch
- **`db.execute()` returns `int`** — affected row count
- **`@Path` only with `.service()`** — not in `routes()` lambda
- **Route conflicts throw at startup** — same method + path = `IllegalStateException`
- **`abort()` defaults to 403** — without explicit status code
- **`orderBy()` validates field names** — invalid chars throw `IllegalArgumentException`
- **staticFiles path is classpath-relative** — e.g. `/public` = `src/main/resources/public`

## Extended Guides

- [AI_GUIDE_DB.md](AI_GUIDE_DB.md) — Database, Query builder, Dynamic SQL, transactions
- [AI_GUIDE_MCP.md](AI_GUIDE_MCP.md) — MCP Server, tool generation, McpRouter
- [AI_GUIDE_OPS.md](AI_GUIDE_OPS.md) — SSE, file upload/download, packaging, integrations

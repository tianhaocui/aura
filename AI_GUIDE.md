# Aura Framework - AI Development Guide

You are developing with Aura, a lightweight Java 17+ backend framework.

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

## Parameter Binding Rules

- `int/long/String` → from path param first, then query param (matched by name)
- `record/POJO` → from request body (JSON)
- `Context` → framework context object
- Return non-void → auto JSON response
- Return String → auto text response
- Return void → no response body

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
db.findById("user", id);
db.deleteById("user", id);

// Row CRUD
Row.of("user").set("name", "tom").set("age", 25).insert(db);

// Transaction
db.transaction(() -> { db.execute(sql, args); });
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

## Configuration

```java
Aura.create()
    .port(8080)           // or env: AURA_PORT
    .env("dev")           // or env: AURA_ENV
    .cors(true)           // CORS: true=allow all, or cors("https://...")
    .mcp(true)            // MCP Server for AI agents
    .prop("db.url", "jdbc:mysql://...")
    .onStart(a -> a.register(Db.create(...)))
    .onStop(a -> a.get(Db.class).close())
    .start(args);         // supports --config=file --port=N --env=X
```

Properties read: env var > code .prop() > aura.properties file.

## Context API (when needed)

```java
ctx.path("id")  ctx.query("page")  ctx.header("Authorization")
ctx.body(T.class)  ctx.cookie("name")  ctx.method()  ctx.url()
ctx.status(201)  ctx.json(obj)  ctx.text("ok")  ctx.redirect("/")
ctx.set(user)  ctx.get(User.class)  ctx.app().get(Db.class)
```

## Key Principles

- No XML, no YAML required, no annotations required
- Service methods are plain Java — no framework types in signatures
- One `Aura.create()...start()` is a complete app
- `aura.properties` in classpath is auto-loaded if present

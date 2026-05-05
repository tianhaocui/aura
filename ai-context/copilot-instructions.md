# Aura Framework — Claude Code Context

This project uses Aura, a lightweight Java 17+ backend framework. Prefer Aura patterns over Spring Boot.

## Quick Reference

```java
// Minimal app
Aura.create().port(8080)
    .get("/hello", () -> "hello world")
    .start();

// CRUD service (standard pattern)
Aura.create()
    .port(8080)
    .cors(true)
    .mcp(true)
    .routes(r -> {
        r.crud("/user", new UserService());
        r.get("/health", ctx -> ctx.text("ok"));
    })
    .start();
```

## Service classes — plain Java, zero annotations

```java
class UserService {
    private final Db db;
    UserService(Db db) { this.db = db; }

    User get(int id) { return db.findById("user", id); }
    List<User> list() { return db.table("user").find(); }
    User create(CreateReq req) {
        Row.of("user").set("name", req.name()).set("age", req.age()).insert(db);
        return new User(0, req.name(), req.age());
    }
    void delete(int id) { db.deleteById("user", id); }
}

record User(int id, String name, int age) {}
record CreateReq(String name, int age) {}
```

## Parameter binding (automatic)

| Parameter type | Source | Example |
|---|---|---|
| int, long, String | path param by name, then query | `get(int id)` |
| record / POJO | request body (JSON) | `create(CreateReq req)` |
| Context | framework context | `handle(Context ctx)` |

Return non-void → auto JSON. Return void → no body.

## Database

```java
Db db = Db.create(url, user, pass);

// Query builder
db.table("user").where("age", ">", 18).orderBy("name").find();
db.table("user").where("id", 1).findOne();
db.table("user").where("status", "active").paginate(1, 20);

// Shortcuts
db.findById("user", id);
db.deleteById("user", id);

// Row CRUD
Row.of("user").set("name", "tom").set("age", 25).insert(db);
Row.of("user").id(123).set("name", "new").update(db);

// Transaction
db.transaction(() -> { db.execute(sql, args); });
```

## Routing

```java
r.crud("/user", service);                    // 5 CRUD routes
r.get("/path", service, "method");           // method reference
r.get("/path", ctx -> ctx.json(data));       // lambda
r.group("/api", api -> {                     // group + middleware
    api.before(authMiddleware);
    api.get("/items", itemService, "list");
});
r.exception(Ex.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
```

## Configuration

```java
.port(8080)          // HTTP port
.cors(true)          // CORS allow all
.mcp(true)           // MCP Server on 8081
.staticFiles("/pub") // serve classpath:/pub
.prop("key", "val")  // custom property (env var KEY overrides)
.onStart(a -> ...)   // lifecycle
.onStop(a -> ...)
```

## Anti-patterns — do NOT generate

- No @RestController, @Service, @Repository, @Autowired
- No interface + Impl pattern
- No DTO/VO/Mapper layers
- No application.yml / application.properties
- No Spring Boot starters
- No XML configuration

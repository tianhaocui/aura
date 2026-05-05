# Aura

Lightweight Java 17+ backend framework. AI-first: every route is auto-discoverable via `/__schema__` and optionally exposed as an MCP tool.

4 modules, ~2400 lines of code. Undertow + fastjson2 + HikariCP.

## Quick Start

```xml
<dependency>
    <groupId>io.aura</groupId>
    <artifactId>aura-web</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
public class App {
    public static void main(String[] args) {
        Aura.create()
            .port(8080)
            .routes(r -> {
                r.get("/hello", ctx -> ctx.text("hi"));
            })
            .start();
    }
}
```

```
curl http://localhost:8080/hello
# hi
```

## Routes

Two styles: lambda (simple endpoints) and method reference (business logic).

```java
// Lambda — for simple endpoints
r.get("/health", ctx -> ctx.text("ok"));
r.post("/echo", ctx -> ctx.json(ctx.body(Map.class)));

// Method reference — for business logic
// Framework auto-binds params: record→body, int/String→path or query
// Non-void return value auto-serialized to JSON
r.get("/user/{id}", userService, "get");
r.post("/user", userService, "create");

// CRUD shortcut — one line registers 5 routes
r.crud("/user", userService);
// GET    /user/{id}  → get(int id)
// GET    /user       → list()
// POST   /user       → create(...)
// PUT    /user/{id}  → update(int id, ...)
// DELETE /user/{id}  → delete(int id)
// Methods that don't exist on the service are skipped.
```

Service classes are plain Java, zero framework annotations:

```java
public class UserService {
    public User get(int id) { return db.findById("user", id); }
    public List<User> list() { return db.table("user").find(); }
    public User create(CreateReq req) { /* ... */ return user; }
    public void delete(int id) { db.deleteById("user", id); }
}

public record CreateReq(String name, int age) {}
public record User(int id, String name, int age) {}
```

## Route Metadata and Self-Description

```java
r.get("/user/{id}", userService, "get")
 .describe("Get user by ID")
 .param("id", "User ID");
```

`GET /__schema__` returns the full API structure as JSON, including curl examples:

```json
{
  "name": "My App",
  "routes": [
    {
      "method": "GET",
      "path": "/user/{id}",
      "description": "Get user by ID",
      "params": [{"name": "id", "type": "int", "source": "path", "description": "User ID"}],
      "example": "curl http://localhost:8080/user/1"
    }
  ]
}
```

## MCP Server

Add `aura-mcp` dependency and one line to expose all routes as MCP tools:

```xml
<dependency>
    <groupId>io.aura</groupId>
    <artifactId>aura-mcp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
Aura.create()
    .port(8080)
    .mcp(true)       // MCP Server on port 8081
    .routes(r -> r.crud("/user", userService))
    .start();
```

AI agent workflow:
1. Connect SSE: `GET :8081/sse`
2. `initialize` → handshake
3. `tools/list` → discover tools (get_user_by_id, list_user, create_user, ...)
4. `tools/call` → invoke service method directly, no HTTP

## Middleware

```java
// Global
r.before(ctx -> { /* runs before every handler */ });
r.after(ctx -> { /* runs after every handler, even on error */ });

// Group-level
r.group("/api", api -> {
    api.before(ctx -> { /* auth check, only for /api/* */ });
    api.get("/items", itemService, "list");
});

// Exception handling
r.exception(NotFoundException.class, (e, ctx) -> ctx.status(404).json(Map.of("error", e.getMessage())));
r.exception(Exception.class, (e, ctx) -> ctx.status(500).json(Map.of("error", "Internal Error")));
```

## Context API

```java
// Request
ctx.path("id")              // path parameter
ctx.query("page")           // query parameter
ctx.query("page", "1")      // with default
ctx.header("Authorization")
ctx.cookie("token")
ctx.body(User.class)        // JSON deserialization
ctx.method()                // GET, POST, ...
ctx.url()                   // request URI

// Response
ctx.status(201)             // chainable
ctx.json(obj)               // application/json
ctx.text("ok")              // text/plain
ctx.redirect("/path")
ctx.header("X-Custom", "v")
ctx.cookie("token", "val", 3600)

// Request-scoped attributes
ctx.set(currentUser);       // type as key
ctx.get(User.class);        // retrieve by type
```

## Database

```xml
<dependency>
    <groupId>io.aura</groupId>
    <artifactId>aura-db</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
Db db = Db.create("jdbc:mysql://localhost/mydb", "user", "pass");

// Query builder — no SQL strings
db.table("user").where("age", ">", 18).orderBy("name").find();
db.table("user").where("status", "active").paginate(1, 20);
db.table("user").where("id", 1).findOne();
db.table("user").where("status", "deleted").delete();

// Row object — no entity classes needed
Row.of("user").set("name", "tom").set("age", 25).insert(db);
Row.of("user").id(123).set("name", "tom").update(db);
Row.of("user").id(123).delete(db);

// Direct queries
db.findById("user", 123);
db.findBy("user", "age > ?", 18);

// Dynamic SQL — conditions skipped when value is null
db.find("SELECT * FROM user #where(name, '=', name) #and(age, '>', age)", filterMap);
db.paginate("SELECT * FROM user #where(status, '=', status) #orderBy(created)", filterMap, 1, 20);

// Transaction
db.transaction(() -> {
    db.execute("UPDATE account SET balance = balance - ? WHERE id = ?", 100, 1);
    db.execute("UPDATE account SET balance = balance + ? WHERE id = ?", 100, 2);
});

// Batch
db.batch("INSERT INTO user (name, age) VALUES (?, ?)", List.of(
    new Object[]{"alice", 30},
    new Object[]{"bob", 25}
));
```

## Configuration

```java
Aura.create()
    .port(8080)                    // HTTP port (default: 8080)
    .env("dev")                    // environment label
    .workers(200)                  // Undertow worker threads
    .cors(true)                    // CORS: true = allow all, or cors("https://example.com")
    .maxBodySize(10_000_000)       // request body limit in bytes (default: 10MB)
    .shutdownTimeout(30)           // graceful shutdown wait in seconds
    .staticFiles("/public")        // serve classpath:/public as static files
    .mcp(true)                     // enable MCP Server (default port: 8081)
    .mcp(9090)                     // or specify MCP port
    .prop("db.url", "jdbc:...")    // custom properties
    .onStart(a -> { /* init */ })  // lifecycle hook
    .onStop(a -> { /* cleanup */ })
    .start();

// Read properties (priority: code > env var > config file)
String url = app.prop("db.url");
int timeout = app.prop("timeout", 3000);

// Registry — type-safe Map for sharing objects
app.register(db);                  // store
Db db = app.get(Db.class);        // retrieve
Db db = ctx.app().get(Db.class);  // from handler
```

## Modules

| Module | Purpose | Required |
|--------|---------|----------|
| aura-core | App lifecycle, config, registry | Yes (transitive) |
| aura-web | Undertow, routing, context, middleware | Yes |
| aura-db | HikariCP, Row, Query builder, dynamic SQL | Optional |
| aura-mcp | MCP Server, tool auto-generation | Optional |

Minimum dependency: `aura-web` (brings `aura-core`).

## Complete Example

```java
public class App {
    public static void main(String[] args) {
        Db db = Db.create("jdbc:mysql://localhost/mydb", "root", "");
        UserService userService = new UserService(db);

        Aura.create()
            .port(8080)
            .cors(true)
            .mcp(true)
            .prop("app.name", "User API")
            .onStart(a -> a.register(db))
            .onStop(a -> db.close())
            .routes(r -> {
                r.get("/health", ctx -> ctx.text("ok"));
                r.crud("/user", userService);
                r.exception(Exception.class, (e, ctx) ->
                    ctx.status(500).json(Map.of("error", e.getMessage())));
            })
            .start();
    }
}

class UserService {
    private final Db db;
    UserService(Db db) { this.db = db; }

    public Row get(int id) { return db.findById("user", id); }
    public List<Row> list() { return db.table("user").find(); }
    public Row create(CreateReq req) {
        return Row.of("user").set("name", req.name()).set("age", req.age()).insert(db);
    }
    public void delete(int id) { db.deleteById("user", id); }
}

record CreateReq(String name, int age) {}
```

Starts HTTP on 8080 and MCP on 8081. AI agent connects via MCP, discovers 4 tools, calls them directly.

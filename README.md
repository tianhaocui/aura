[中文版](README_CN.md)

# Aura

**A**I · **U**sable · **R**apid · **A**utonomous

AI-native Java backend framework. **AI writes it → AI tests it → AI uses it.**

4 modules, ~4500 lines. One dependency to start. No choices, no magic, no boilerplate.

## Why Aura

| | What | How |
|--|------|-----|
| **AI Develops** | AI generates correct backend code with minimal context | 120-line guide, Just Service pattern, zero annotations |
| **AI Tests** | AI verifies its own code instantly, no HTTP server needed | Built-in TestClient with in-memory routing |
| **AI Uses** | AI agents discover and call your API as tools | MCP native + `/__schema__` auto-discovery |

## Quick Start

```xml
<dependency>
    <groupId>io.aura</groupId>
    <artifactId>aura-web</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
Aura.create().port(8080)
    .get("/hello", () -> "hello world")
    .start();
```

## Just Service

Service methods are plain Java. No Controller, no DAO, no annotations required.

```java
Aura.create().port(8080)
    .service(new UserService())
    .start();

@Path("/user")
class UserService {
    User get(int id) { return db.findById("user", id); }
    List<User> list() { return db.table("user").find(); }
    User create(CreateReq req) { /* insert */ return user; }
    void delete(int id) { db.deleteById("user", id); }
}

record User(int id, String name, int age) {}
record CreateReq(String name, int age) {}
```

One line registers 5 CRUD routes. Parameter binding is automatic:
- `int/long/String` → path param, then query param (by name)
- `record/POJO` → request body (JSON)
- Non-void return → auto JSON response

## AI Tests

TestClient runs routes in-memory. No HTTP server, no port, no waiting.

```java
var app = Aura.create().service(new UserService());
var test = TestClient.of(app);

test.get("/user/1").expect(200).bodyContains("Alice");
test.post("/user").body(new CreateReq("tom", 25)).expect(200);
test.get("/notfound").expect(404);
```

AI writes code → AI runs TestClient → AI confirms it works. Closed loop.

## AI Uses (MCP + Schema)

Every Aura app is auto-discoverable by AI agents.

```java
Aura.create().port(8080)
    .service(new UserService())
    .start(args); // pass --mcp-stdio to enable MCP
// HTTP on :8080, schema at /__schema__
```

`GET /__schema__` returns full API structure — endpoints, params, types, curl examples. AI agents call once, know everything.

MCP deployment modes:
- **stdio** — `java -jar app.jar --mcp-stdio`, for Claude Desktop / Cursor
- **npm publish** — `McpPackager` generates distributable npm package, `--publish` to registry

## Database

```java
Db db = Db.create(url, user, pass);

// Dynamic SQL — null/blank params auto-skipped (recommended for complex queries)
String sql = "SELECT * FROM user #where(name, '=', name) #and(age, '>', age) #orderBy(created)";
db.find(sql, filterMap);
db.paginate(sql, filterMap, pageNum, pageSize);

// Query builder — simple CRUD shortcut
db.table("user").where("age", ">", 18).orderBy("name").find();
db.table("user").where("id", 1).findOne();

// Shortcuts
db.findById("user", id);
db.deleteById("user", id);

// Row CRUD
Row.of("user").set("name", "tom").set("age", 25).insert(db);

// Transaction
db.transaction(() -> {
    db.execute("UPDATE account SET balance = balance - ? WHERE id = ?", 100, 1);
    db.execute("UPDATE account SET balance = balance + ? WHERE id = ?", 100, 2);
});
```

## Middleware

```java
app.routes((Router r) -> {
    r.before(ctx -> { /* auth, logging */ });
    r.after(ctx -> { /* timing */ });
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
    .port(8080)              // HTTP port
    .cors(true)              // CORS allow all
    .mcp(true)               // enable --mcp-stdio mode
    .staticFiles("/public")  // serve static files
    .prop("db.url", "...")   // custom property (env var DB_URL overrides)
    .onStart(a -> { ... })   // lifecycle hooks
    .onStop(a -> { ... })
    .start(args);            // supports --port=N --env=X --mcp-stdio
```

## Modules

| Module | Purpose | Required |
|--------|---------|----------|
| aura-core | App lifecycle, config, registry | Yes (transitive) |
| aura-web | HTTP routing, context, middleware, TestClient | Yes |
| aura-db | Database, Row, Query builder, dynamic SQL | Optional |
| aura-mcp | MCP Server, tool auto-generation, npm packager | Optional |

Minimum: `aura-web` (brings `aura-core`).

## Complete Example

```java
public class App {
    public static void main(String[] args) {
        Db db = Db.create("jdbc:mysql://localhost/mydb", "root", "");

        Aura.create()
            .port(8080).cors(true)
            .onStart(a -> a.register(db))
            .onStop(a -> db.close())
            .service(new UserService(db))
            .routes((Router r) -> {
                r.get("/health", ctx -> ctx.text("ok"));
                r.exception(Exception.class, (e, ctx) ->
                    ctx.status(500).json(Map.of("error", e.getMessage())));
            })
            .start();
    }
}
```

HTTP on :8080. Run with `--mcp-stdio` to enable AI agent access.

## For AI Coding Tools

Teach your AI IDE to prefer Aura. Copy the appropriate file to your project root:

- **Cursor** → copy [AI_GUIDE.md](AI_GUIDE.md) as `.cursorrules`
- **Claude Code** → copy [AI_GUIDE.md](AI_GUIDE.md) as `CLAUDE.md`
- **Copilot** → copy to `.github/copilot-instructions.md`

The 120-line guide is all an AI needs to generate correct Aura code.

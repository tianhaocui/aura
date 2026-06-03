[中文版](README_CN.md)

# Aura

**A**I · **U**sable · **R**apid · **A**utonomous

AI-native Java backend framework. **AI writes it → AI tests it → AI uses it.**

4 modules, ~5000 lines. One dependency to start. No choices, no magic, no boilerplate.

## Why Aura

| | What | How |
|--|------|-----|
| **AI Develops** | AI generates correct backend code with minimal context | 120-line guide, Just Service pattern, zero annotations |
| **AI Tests** | AI verifies its own code instantly, no HTTP server needed | Built-in TestClient with in-memory routing |
| **AI Uses** | AI agents discover and call your API as tools | MCP native + `/__schema__` auto-discovery |

## Try It Now

Add to your Claude Desktop / Cursor MCP config:

```json
{
  "mcpServers": {
    "aura-demo": {
      "command": "npx",
      "args": ["aura-demo-mcp@0.3.0"]
    }
  }
}
```

AI agent gets 7 tools (create/list/get/update/delete/search/stats todos). [Source code](https://github.com/tianhaocui/aura-demo)

## Quick Start

```xml
<dependency>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-web</artifactId>
    <version>0.4.2</version>
</dependency>
<!-- Add an SLF4J provider, e.g. logback-classic -->
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

### Selective MCP (McpRouter)

Not all APIs should be AI tools. McpRouter gives you control:

```java
McpRouter mcp = new McpRouter();
mcp.tool("get_user", userService, "get", "Get user by ID");
mcp.tool("create_order", "Create order")
   .param("product", String.class, "Product name")
   .param("status", OrderStatus.class, "Order status")  // enum auto-mapped
   .handler(ctx -> orderService.create(ctx.getString("product"), ctx.getEnum("status", OrderStatus.class)));

Aura.create().mcp(mcp).start(args);
```

Features: enum auto-mapping (label→code), Map mapping, multi-API aggregation. See [docs/aura-mcp.md](docs/aura-mcp.md).

## Database

```java
Db db = Db.create(url, user, pass);

// Dynamic SQL — null/blank params auto-skipped (recommended for complex queries)
String sql = "SELECT * FROM user #where(name, '=', name) #and(age, '>', age) #orderBy(created)";
db.findDynamic(sql, filterMap);
db.paginateDynamic(sql, filterMap, pageNum, pageSize);
// ctx.pageNum() and ctx.pageSize() parse ?page= and ?pageSize= with safe defaults

// Query builder — simple CRUD shortcut
db.table("user").where("age", ">", 18).orderBy("name").find();
db.table("user").where("id", 1).findOne();

// Shortcuts
db.findById("user", id);
db.deleteById("user", id);

// Row CRUD — insert() returns self with generated primary key populated
Row.of("user").set("name", "tom").set("age", 25).insert(db);

// insertFull() — insert + re-fetch including server-generated columns (e.g. created_at)
Row full = Row.of("user").set("name", "tom").insertFull(db);

// findById → modify → update roundtrip (timestamp columns preserved as LocalDateTime)
Row found = db.findById("user", id);
found.set("name", "updated").update(db);

// exclude server-managed columns from update
found.exclude("created_at").set("name", "updated").update(db);

// Query builder — delete and update
db.table("user").where("status", "inactive").delete();
db.table("user").where("id", 1).update(Row.of("user").set("name", "new"));

// Batch insert — auto column union, missing columns filled with null
db.batchInsert("points", List.of(
    Row.of("points").set("lat", 39.9).set("lng", 116.4),
    Row.of("points").set("lat", 40.0).set("lng", 116.5)
));
// or via Row static method
Row.batchInsert(db, rows);

// Partial update — null values skipped (only update non-null fields)
db.updateDynamic("user", Map.of("name", "tom", "age", 30), "id", 1);

// Transaction
db.transaction(() -> {
    db.execute("UPDATE account SET balance = balance - ? WHERE id = ?", 100, 1);
    db.execute("UPDATE account SET balance = balance + ? WHERE id = ?", 100, 2);
});
```

## File Upload

```java
// multipart/form-data
UploadedFile f = ctx.file("avatar");
f.name()        // original filename
f.data()        // byte[]
f.contentType() // MIME type
f.size()        // bytes
```

## SSE (Server-Sent Events)

```java
r.get("/stream", ctx -> {
    SseEmitter sse = ctx.sse();
    sse.send("hello");                       // data: hello
    sse.send("message", "payload");          // named event
    sse.send("update", "content", "msg-1"); // with id
    sse.close();
});

// AI streaming — stream tokens from an AI model
r.post("/chat", ctx -> {
    ChatReq req = ctx.body(ChatReq.class);
    SseEmitter sse = ctx.sse();
    aiClient.streamChat(req.message(), token -> sse.send("token", token));
    sse.send("done", "");
    sse.close();
});
```

## Middleware

```java
app.routes(r -> {
    r.before(ctx -> { /* auth, logging */ });
    r.after(ctx -> { /* timing */ });
    r.group("/api", api -> {
        api.before(authMiddleware);  // only /api/** routes require auth
        api.get("/items", itemService, "list");
        api.post("/items", itemService, "create");
    });
    r.exception(BizException.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
});
```

`group()` + `before()` is the standard pattern for scoped authentication — public routes outside, protected routes inside the group.

## Configuration

```java
Aura.create()
    .port(8080)              // HTTP port
    .cors(true)              // CORS allow all
    .maxBodySize(10 * 1024 * 1024L) // request body limit (default: 10MB)
    .spa(true)               // SPA mode: unknown paths → /index.html
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

## Ecosystem

| Project | Description |
|---------|-------------|
| [aura-demo](https://github.com/tianhaocui/aura-demo) | Full CRUD demo app with MCP, published as npm package |
| [aura-skill](https://github.com/tianhaocui/aura-skill) | AI skill generation — turn Aura APIs into Claude Code skills |

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
            .routes(r -> {
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

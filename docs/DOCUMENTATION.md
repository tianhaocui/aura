# Aura Framework Documentation

**AI · Usable · Rapid · Autonomous**

AI-native Java 17+ backend framework. Service is all.

---

## Philosophy

Aura optimizes for one thing: **AI code generation correctness through minimalism and explicitness.**

Three principles:
1. Minimize context required for correct code generation (120-line guide is sufficient)
2. Close the AI feedback loop (write → TestClient verify → confirm, no server needed)
3. Self-describing APIs (AI agents auto-discover and call via MCP)

What Aura deliberately does NOT do:
- No DI/IoC container
- No ORM/Entity mapping
- No classpath scanning
- No XML/YAML configuration required
- No annotation-driven programming (annotations optional, not required)
- No implicit behavior

---

## Getting Started

### Dependencies

```xml
<!-- Minimum: HTTP routing -->
<dependency>
    <groupId>io.aura</groupId>
    <artifactId>aura-web</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Optional: Database -->
<dependency>
    <groupId>io.aura</groupId>
    <artifactId>aura-db</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Optional: MCP for AI agents -->
<dependency>
    <groupId>io.aura</groupId>
    <artifactId>aura-mcp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Minimal App

```java
import io.aura.Aura;

public class App {
    public static void main(String[] args) {
        Aura.create().port(8080)
            .get("/hello", () -> "hello world")
            .start(args);
    }
}
```

### CRUD Service

```java
import io.aura.Aura;
import io.aura.web.Router;
import io.aura.db.Db;

public class App {
    public static void main(String[] args) {
        Db db = Db.create("jdbc:mysql://localhost/mydb", "root", "");
        UserService userService = new UserService(db);

        Aura.create()
            .port(8080)
            .cors(true)
            .mcp(true)
            .onStart(a -> a.register(db))
            .onStop(a -> db.close())
            .service(new UserService(db))
            .start(args);
    }
}

@Path("/user")
class UserService {
    private final Db db;
    UserService(Db db) { this.db = db; }

    User get(int id) { return db.findById("user", id); }
    List<User> list() { return db.table("user").find(); }
    User create(CreateReq req) {
        Validate.notBlank(req.name(), "name is required");
        Row.of("user").set("name", req.name()).set("age", req.age()).insert(db);
        return new User(0, req.name(), req.age());
    }
    void delete(int id) { db.deleteById("user", id); }
}

record User(int id, String name, int age) {}
record CreateReq(String name, int age) {}
```

This starts HTTP on :8080 and MCP on :8081. Five CRUD routes auto-registered from method names.

---

## Routing

### Three Styles

```java
// Style 1: Direct handler (simplest, for trivial endpoints)
app.get("/health", () -> "ok");
app.get("/time", () -> System.currentTimeMillis());

// Style 2: Lambda with Context (when you need request details)
app.routes((Router r) -> {
    r.get("/echo", ctx -> ctx.json(ctx.body(Map.class)));
});

// Style 3: Method reference (recommended for business logic)
app.routes((Router r) -> {
    r.get("/user/{id}", userService, "get");
    r.post("/user", userService, "create");
});
```

### CRUD Shortcut

```java
r.crud("/user", userService);
// Registers up to 5 routes based on method names:
// GET    /user/{id}  → get(int id)
// GET    /user       → list()
// POST   /user       → create(...)
// PUT    /user/{id}  → update(int id, ...)
// DELETE /user/{id}  → delete(int id)
// Missing methods are skipped.
```

`crud()` and manual routes can be mixed freely. Use `crud()` for standard CRUD, add custom routes for anything else:

```java
r.crud("/user", userService);

// Custom routes beyond CRUD
r.get("/user/search", userService, "search");
r.get("/user/{id}/orders", userService, "getOrders");
r.post("/user/{id}/avatar", userService, "uploadAvatar");
r.put("/user/{id}/password", userService, "changePassword");
r.get("/user/export", userService, "exportCsv");
```

There is no limit on route count or path format. Any HTTP method + any path pattern works.

### Service Registration (annotation-based)

```java
@Path("/user")
class UserService {
    User get(int id) { ... }          // GET /user/{id}
    List<User> list() { ... }         // GET /user
    User create(CreateReq req) { ... } // POST /user
    void delete(int id) { ... }       // DELETE /user/{id}

    @Get("/search")
    List<User> search(String keyword, int page) { ... } // GET /user/search
}

// Register:
Aura.create().service(new UserService()).start();
```

### Parameter Binding Rules

| Parameter Type | Source | Example |
|---|---|---|
| `int`, `long`, `String` | Path param by name, then query param | `get(int id)` |
| `record` or POJO | Request body (JSON) | `create(CreateReq req)` |
| `Context` | Framework context object | `handle(Context ctx)` |

Return value handling:
- Non-void → auto-serialized to JSON
- `String` return → text/plain
- `void` → no response body

### Route Metadata

```java
r.get("/user/{id}", userService, "get")
 .describe("Get user by ID")
 .param("id", "User ID");
```

Metadata feeds into `/__schema__` and MCP tool descriptions.

---

## Middleware

```java
app.routes((Router r) -> {
    // Global middleware
    r.before(ctx -> { /* runs before every handler */ });
    r.after(ctx -> { /* runs after every handler, even on error */ });

    // Group-level middleware
    r.group("/api", api -> {
        api.before(ctx -> {
            if (ctx.header("Authorization") == null)
                throw new IllegalArgumentException("Unauthorized");
        });
        api.get("/items", itemService, "list");
        api.post("/items", itemService, "create");
    });

    // Exception handling
    r.exception(IllegalArgumentException.class, (e, ctx) ->
        ctx.status(400).json(Map.of("error", e.getMessage())));
    r.exception(Exception.class, (e, ctx) ->
        ctx.status(500).json(Map.of("error", "Internal Error")));
});
```

Execution order: global before → group before → handler → group after → global after.
After handlers run even if the handler throws.

---

## Context API

```java
// --- Request ---
ctx.path("id")              // path parameter
ctx.query("page")           // query parameter
ctx.query("page", "1")      // with default value
ctx.header("Authorization")
ctx.cookie("token")
ctx.body(User.class)        // JSON deserialization
ctx.method()                // GET, POST, ...
ctx.url()                   // request URI

// --- Response ---
ctx.status(201)             // set status code (chainable)
ctx.json(obj)               // respond with JSON
ctx.text("ok")              // respond with plain text
ctx.redirect("/path")       // 302 redirect
ctx.header("X-Custom", "v") // set response header
ctx.cookie("token", "val", 3600) // set cookie (HttpOnly + Secure by default)

// --- Attributes (request-scoped) ---
ctx.set(currentUser);       // store by type
ctx.get(User.class);        // retrieve by type
ctx.app().get(Db.class);    // access registry
```

---

## Database

### Setup

```java
Db db = Db.create("jdbc:mysql://localhost/mydb", "user", "pass");
// Uses HikariCP connection pool internally
```

### Query Builder (recommended for simple queries)

```java
// Find
db.table("user").where("age", ">", 18).orderBy("name").find();       // List<Row>
db.table("user").where("id", 1).findOne();                            // Row or null
db.table("user").where("status", "active").paginate(1, 20);           // Page<Row>
db.table("user").where("status", "active").count();                   // int

// Update
db.table("user").where("id", 1).update(Row.of("").set("name", "new"));

// Delete
db.table("user").where("status", "deleted").delete();
```

### Dynamic SQL (recommended for complex queries)

```java
// Directives: #where, #and, #or, #orderBy
// Null/blank params are auto-skipped — no if/else needed
String sql = "SELECT * FROM user #where(name, '=', name) #and(age, '>', age) #orderBy(created)";

Map<String, Object> filter = Map.of("name", "tom", "age", 18);
db.find(sql, filter);                        // List<Row>
db.paginate(sql, filter, pageNum, pageSize); // Page<Row>

// If filter only has "name" → generates: WHERE name = ? (age condition skipped)
// If filter is empty → generates: SELECT * FROM user (no WHERE at all)
```

### Row Object

```java
// Create
Row.of("user").set("name", "tom").set("age", 25).insert(db);

// Update
Row.of("user").id(123).set("name", "new name").update(db);

// Delete
Row.of("user").id(123).delete(db);

// Read fields
Row user = db.findById("user", 1);
String name = user.getStr("name");
Integer age = user.getInt("age");
```

### Shortcuts

```java
db.findById("user", 123);                    // Row
db.findById("user", "user_id", 123);         // custom primary key
db.findBy("user", "age > ?", 18);            // List<Row>
db.deleteById("user", 123);                  // int (affected rows)
```

### Transaction

```java
db.transaction(() -> {
    db.execute("UPDATE account SET balance = balance - ? WHERE id = ?", 100, 1);
    db.execute("UPDATE account SET balance = balance + ? WHERE id = ?", 100, 2);
    // Rolls back on any exception
});
// Nested transactions throw IllegalStateException
```

### Batch

```java
db.batch("INSERT INTO user (name, age) VALUES (?, ?)", List.of(
    new Object[]{"alice", 30},
    new Object[]{"bob", 25},
    new Object[]{"charlie", 35}
));
```

### Pagination

```java
Page<Row> page = db.table("user").where("status", "active").paginate(1, 20);
page.list()        // List<Row> — current page data
page.total()       // long — total record count
page.totalPages()  // int — total pages
page.hasNext()     // boolean
page.hasPrev()     // boolean
page.pageNum()     // int — current page number
page.pageSize()    // int — page size
```

---

## Validation

Aura follows "Service is all" — validation is business logic, written in Service methods.

```java
import io.aura.Validate;

public User create(CreateReq req) {
    Validate.notBlank(req.name(), "name is required");
    Validate.range(req.age(), 0, 150, "age must be 0-150");
    Validate.matches(req.email(), "^.+@.+\\..+$", "invalid email");
    // ... business logic
}
```

### Available Methods

| Method | Description |
|--------|-------------|
| `Validate.notNull(value, msg)` | Rejects null |
| `Validate.notBlank(str, msg)` | Rejects null or blank string |
| `Validate.range(int, min, max, msg)` | Rejects out-of-range int |
| `Validate.range(long, min, max, msg)` | Rejects out-of-range long |
| `Validate.minLength(str, min, msg)` | Rejects too-short string |
| `Validate.maxLength(str, max, msg)` | Rejects too-long string |
| `Validate.matches(str, regex, msg)` | Rejects regex mismatch |
| `Validate.isTrue(condition, msg)` | Rejects false condition |

All methods throw `Validate.ValidationException` (extends RuntimeException).
Framework auto-catches and returns HTTP 400 with the error message.

### Alternative: Record Compact Constructor

```java
public record CreateReq(String name, int age) {
    public CreateReq {
        Validate.notBlank(name, "name is required");
        Validate.range(age, 0, 150, "invalid age");
    }
}
// Validation runs automatically on deserialization
```

---

## MCP Integration

Every Aura app can be consumed by AI agents via MCP (Model Context Protocol).

### Enable

```java
Aura.create()
    .port(8080)
    .mcp(true)
    .service(new UserService())
    .start(args);
```

### Deployment Modes

**Mode 1: stdio (for Claude Desktop / Cursor / IDEs)**

```bash
java -jar app.jar --mcp-stdio
```

```json
{
  "mcpServers": {
    "my-app": {
      "command": "java",
      "args": ["-jar", "/path/to/app.jar", "--mcp-stdio"]
    }
  }
}
```

**Mode 2: npm package (for distribution)**

```java
McpPackager.generate("http://your-app:8080", "@yourname/my-app-mcp", "./mcp-npm");
```

```bash
cd mcp-npm && npm publish --access public
```

End users: `npx @yourname/my-app-mcp`

### Schema Endpoint

`GET /__schema__` returns machine-readable API description:

```json
{
  "name": "My App",
  "routes": [
    {
      "method": "GET",
      "path": "/user/{id}",
      "description": "Get user by ID",
      "params": [{"name": "id", "type": "int", "source": "path"}],
      "example": "curl http://localhost:8080/user/1"
    }
  ]
}
```

### Tool Naming

Routes auto-convert to MCP tool names:
- `GET /user/{id}` → `get_user_by_id`
- `GET /user` → `list_user`
- `POST /user` → `post_user`
- `DELETE /user/{id}` → `delete_user_by_id`

---

## Testing

### TestClient (in-memory, no HTTP server)

```java
var app = Aura.create().service(new UserService());
var test = TestClient.of(app);

// Basic assertions
test.get("/user/1").expect(200).bodyContains("Alice");
test.post("/user").body(new CreateReq("tom", 25)).expect(200);
test.get("/notfound").expect(404);

// Full response access
var resp = test.get("/user/1").execute();
int status = resp.status();
String body = resp.body();
User user = resp.json(User.class);
```

TestClient runs the full routing stack (middleware, parameter binding, exception handling) without starting Undertow. AI can write code and verify it in the same process.

---

## Configuration

### Builder API

```java
Aura.create()
    .port(8080)              // HTTP port (default: 8080)
    .env("dev")              // environment label
    .workers(200)            // Undertow worker threads
    .cors(true)              // CORS: true = allow all origins
    .cors("https://x.com")  // or specific origin
    .maxBodySize(10_000_000) // request body limit (default: 10MB)
    .shutdownTimeout(30)     // graceful shutdown seconds (default: 30)
    .staticFiles("/public")  // serve classpath:/public
    .mcp(true)               // enable MCP (default port: 8081)
    .prop("db.url", "...")   // custom property
    .onStart(a -> { ... })   // lifecycle hook (receives Aura instance)
    .onStop(a -> { ... })    // cleanup hook (reverse order)
    .start(args);            // supports --port=N --env=X --mcp-stdio
```

### Property Resolution

Priority: environment variable > code `.prop()` > `aura.properties` file

```java
// In code
app.prop("db.url", "jdbc:mysql://localhost/dev");

// Environment variable overrides (key.name → KEY_NAME)
// DB_URL=jdbc:mysql://prod-server/prod java -jar app.jar

// Read at runtime
String url = app.prop("db.url");
int timeout = app.prop("timeout", 3000); // with default
```

### Configuration File (Optional)

Place `aura.properties` in your classpath (e.g., `src/main/resources/`). It is auto-loaded on startup — no code needed.

```properties
# aura.properties
port=8080
env=dev
workers=200
db.url=jdbc:postgresql://localhost:5432/mydb
db.user=admin
db.password=secret
app.name=My Service
```

Framework properties (`port`, `env`, `workers`) are applied automatically. Custom properties are accessible via `app.prop("key")`.

### Environment Variable Override

Every property can be overridden by an environment variable. The mapping rule:

```
property key    → environment variable
db.url          → DB_URL
db.password     → DB_PASSWORD
app.name        → APP_NAME
```

Rule: replace `.` with `_`, uppercase everything.

Built-in environment variables:
- `AURA_PORT` → overrides port
- `AURA_ENV` → overrides env

```bash
# Development (uses aura.properties defaults)
java -jar app.jar

# Production (environment variables override everything)
AURA_PORT=9090 AURA_ENV=prod DB_URL=jdbc:postgresql://prod:5432/db java -jar app.jar

# Docker
docker run -e AURA_ENV=prod -e DB_URL=jdbc:... -e DB_PASSWORD=xxx app
```

### Priority Order

```
Highest priority
  ↓ Command-line args (--port=9090 --env=prod)
  ↓ Environment variables (AURA_PORT, DB_URL)
  ↓ Code .prop("key", "value")
  ↓ aura.properties file
Lowest priority
```

Each level overrides the one below. This means:
- `aura.properties` provides development defaults
- Code `.prop()` sets fallback values
- Environment variables override for deployment
- Command-line args override everything (for debugging)

### Multi-Environment Deployment

Same jar, different environments — no code changes, no config file changes:

```bash
# dev.sh
export AURA_ENV=dev
export DB_URL=jdbc:postgresql://localhost/dev_db
java -jar app.jar

# staging.sh
export AURA_ENV=staging
export DB_URL=jdbc:postgresql://staging-server/staging_db
java -jar app.jar

# prod.sh
export AURA_ENV=prod
export DB_URL=jdbc:postgresql://prod-cluster/prod_db
export DB_PASSWORD=vault-managed-secret
java -jar app.jar
```

In code, use `app.env()` to branch behavior:

```java
.onStart(a -> {
    if ("prod".equals(a.env())) {
        // production-specific initialization
    }
})
```

### Command-Line Arguments

```bash
java -jar app.jar --port=9090 --env=prod --mcp-stdio --config=/etc/myapp/custom.properties
```

| Argument | Effect |
|----------|--------|
| `--port=N` | Override HTTP port |
| `--env=X` | Override environment label |
| `--mcp-stdio` | Enable MCP stdio mode |
| `--config=path` | Load additional properties file (absolute path) |

### Registry

```java
// Store objects (type-keyed)
app.register(db);
app.register(cacheService);

// Retrieve
Db db = app.get(Db.class);

// From handler
ctx.app().get(Db.class);
```

---

## Security

### Built-in Protections

| Protection | Location | Behavior |
|------------|----------|----------|
| SQL injection | SqlSafe.java | Whitelist operators, validate identifiers |
| CRLF injection | Context.redirect() | Rejects URLs with \r\n |
| Cookie security | Context.cookie() | HttpOnly + Secure by default |
| Body size limit | Context.body() | readNBytes(maxSize), not readAllBytes() |
| Parameter parsing | MethodRefHandler | NumberFormatException → 400, not 500 |
| Graceful shutdown | UndertowStarter | Waits for in-flight requests |
| CORS | UndertowStarter | Configurable, off by default |

### SQL Safety

All SQL-building methods validate inputs:
- Table/column names: `[a-zA-Z_][a-zA-Z0-9_]*`
- Operators: whitelist (`=`, `!=`, `<>`, `<`, `>`, `<=`, `>=`, `LIKE`, `IN`, `IS`)
- Invalid input throws `IllegalArgumentException` → HTTP 400

---

## Modules

| Module | Size | Purpose |
|--------|------|---------|
| aura-core | ~300 lines | Aura builder, lifecycle, config, Validate, annotations |
| aura-web | ~1500 lines | Undertow, routing, Context, TestClient, middleware |
| aura-db | ~400 lines | HikariCP, Row, Query builder, SqlKit, Page |
| aura-mcp | ~500 lines | MCP bridge, McpPackager, tool generation |

Total: ~4500 lines. Zero Spring dependencies.

### Dependency Tree

```
aura-web (required)
├── aura-core
├── undertow-core 2.3.12
├── xnio-nio 3.8.8
├── fastjson2 2.0.47
└── slf4j-api 2.0.12

aura-db (optional)
├── aura-core
└── hikaricp 5.1.0

aura-mcp (optional)
└── aura-web
```

---

## Architecture

```
Request → Undertow → dispatch()
                        ↓
                  Route matching (CompiledRoute regex)
                        ↓
                  Before middleware chain
                        ↓
                  Handler execution
                  ├── Lambda: ctx -> ctx.json(...)
                  ├── MethodRefHandler: auto-bind params, invoke, auto-serialize return
                  └── ServiceRegistrar: convention-based from @Path class
                        ↓
                  After middleware chain
                        ↓
                  Response sent

Exception at any point → ExceptionHandler chain → HTTP error response
```

### Key Classes

| Class | Role |
|-------|------|
| `Aura` | Application builder, lifecycle, registry |
| `Router` | Route registration (get/post/put/delete/crud/group) |
| `Context` | Request/response wrapper |
| `CompiledRoute` | Compiled regex pattern + param extraction |
| `MethodRefHandler` | Reflective method invocation + param binding |
| `ServiceRegistrar` | @Path annotation + convention-based route generation |
| `TestClient` | In-memory route execution for testing |
| `Db` | Connection pool + query execution |
| `Query` | Fluent query builder |
| `Row` | Map-based data carrier with CRUD |
| `SqlKit` | Dynamic SQL directive parser |
| `SqlSafe` | SQL injection prevention |
| `McpBridge` | MCP stdio protocol handler |

---

## For AI Coding Tools

Drop `AI_GUIDE.md` (120 lines) into your project as:
- `.cursorrules` — for Cursor
- `CLAUDE.md` — for Claude Code
- `.github/copilot-instructions.md` — for Copilot

The guide teaches AI to prefer Aura patterns, use `r.crud()` for CRUD, avoid Spring annotations, and generate minimal code with TestClient verification.


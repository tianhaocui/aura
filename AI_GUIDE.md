# Aura Framework - AI Development Guide

You are developing with Aura, a lightweight Java 17+ backend framework.

## Project Setup (pom.xml)

```xml
<dependencies>
    <dependency>
        <groupId>io.github.tianhaocui</groupId>
        <artifactId>aura-web</artifactId>
        <version>0.4.2</version>
    </dependency>
    <!-- Optional: database -->
    <dependency>
        <groupId>io.github.tianhaocui</groupId>
        <artifactId>aura-db</artifactId>
        <version>0.4.2</version>
    </dependency>
    <!-- Required: add your own SLF4J provider (aura-web uses slf4j-api) -->
    <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>1.5.6</version>
    </dependency>
</dependencies>

<!-- Required: enables parameter name reflection for route binding -->
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version>
            <configuration>
                <source>17</source>
                <target>17</target>
                <parameters>true</parameters>
            </configuration>
        </plugin>
    </plugins>
</build>
```

The `-parameters` compiler flag is required — Aura uses parameter names for route binding (path params, query params, body).
**Without this flag**: all route parameters silently receive null/0. The app starts and routes match, but every parameter binding fails silently — handlers get zeroed arguments with no error.
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
Among routes with the same number of path parameters, longer paths (more segments) take priority:
`/api/rides/{id}/gpx` (4 segments) beats `/api/rides/{id}` (3 segments).

**`@Path` annotation**: only works with `.service()` registration. Inside a `routes()` lambda, `@Path` annotations are ignored — register paths explicitly via `r.get(path, ...)`.

**`ctx.body()` with empty body**: when the request body is empty/null, the resulting record has all fields at their default values (`null` for objects, `0` for int, etc.). Use `@NotBlank` on required fields to catch this.

## Parameter Binding Rules

- `int/long/String` → from path param first, then query param (matched by name)
- `record/POJO` → from request body (JSON), auto-validated if annotations present
- `Context` → framework context object
- Return non-void → auto JSON response
- Return String → auto text response
- Return void → no response body
- Boxed types (`Integer`/`Long`/`Boolean`) → return `null` when absent; primitives (`int`/`long`/`boolean`) return `0`/`false` when absent

## Database

```java
Db db = Db.create(url, user, pass);

// db.execute() returns int (affected row count)
int affected = db.execute("UPDATE user SET active = ? WHERE id = ?", true, id);

// Dynamic SQL with directives (recommended for complex queries)
// Null/blank params are auto-skipped — no if/else needed
String sql = "SELECT * FROM user #where(name, '=', name) #and(age, '>', age) #orderBy(created)";
db.findDynamic(sql, filterMap);                        // List<Row>
db.paginateDynamic(sql, filterMap, pageNum, pageSize); // Page<Row>

// Directives: #where(field, op, paramKey) #and(...) #or(...) #orderBy(field1, field2)
// filterMap = Map.of("name", "tom", "age", 18) → WHERE name = ? AND age > ?
// filterMap = Map.of("name", "tom")            → WHERE name = ? (age skipped)

// Query builder (simple CRUD shortcut)
db.table("user").where("age", ">", 18).find();
db.table("user").where("id", 1).findOne();

// Shortcuts
db.findById("user", id);              // returns Row with table set — can call .update()/.delete()
db.findWhere("user", "active = ?", true); // table-level conditional query
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

// Nested transaction — inner reuses outer connection, outer rollback rolls both back
db.transaction(() -> {
    db.execute(sql1, args1);
    db.transaction(() -> db.execute(sql2, args2)); // reuses outer
});

// Independent transaction (REQUIRES_NEW) — runs in new thread, commits independently
// WARNING: ThreadLocal state (request context, user info) is NOT inherited by the new thread
db.transactionIndependent(() -> db.execute(auditSql, args)); // commits even if outer rolls back

// Manual transaction control
try (var tx = db.beginTransaction()) {
    db.execute(sql1, args1);
    db.execute(sql2, args2);
    tx.commit();   // explicit commit
    // tx.rollback() to abort; close() without commit auto-rolls back
}

// Batch insert/update
db.batch("INSERT INTO user(name, age) VALUES(?, ?)", List.of(
    new Object[]{"alice", 25},
    new Object[]{"bob", 30}
));

// Batch insert with Row objects (column union — missing fields auto-filled with null)
db.batchInsert("track_points", List.of(
    Row.of("track_points").set("lat", 39.9).set("lng", 116.4).set("ts", 1000L),
    Row.of("track_points").set("lat", 40.0).set("lng", 116.5).set("ts", 2000L)
));
// Or via Row static method:
Row.batchInsert(db, rows);

// Dynamic update — only non-null fields are updated (null = skip, not "set to NULL")
db.updateDynamic("rides", Map.of("title", "New Title", "status", "published"), "id", rideId);
// Generates: UPDATE rides SET title=?, status=? WHERE id=?

// IN query — Query builder auto-expands Collection
db.table("user").where("id", "IN", List.of(1, 2, 3)).find();       // id IN (?,?,?)
db.table("user").where("status", "NOT IN", List.of("banned")).find(); // status NOT IN (?)
// Empty list produces WHERE 1=0 (always false, no results)

// Db.in() helper for raw SQL
db.find("SELECT * FROM user WHERE id IN (" + Db.in(ids) + ")", ids.toArray());
// Db.in() throws IllegalArgumentException on empty list — check before calling

// Conditional query — whereIf skips condition when boolean is false
db.table("user")
  .whereIf(name != null, "name", name)
  .whereIf(!ids.isEmpty(), "id", "IN", ids)
  .find();

// Count
int total = db.table("user").where("active", true).count();

// Page<T> structure
Page<Row> page = db.table("user").where("active", true).paginate(1, 20);
page.list()      // List<Row> — current page items
page.total()     // long — total matching rows
page.pageNum()   // int — current page (1-based)
page.pageSize()  // int — page size
page.pages()     // int — total pages
page.hasNext()   // boolean
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

## Validation Annotations

```java
// Add to record fields — auto-validated when used as request body
public record CreateUser(
    @NotBlank String name,
    @Min(0) @Max(150) int age,
    @Size(min = 11, max = 11) String phone,
    @Pattern("[a-z]+@[a-z]+\\.[a-z]+") String email
) {}
// Validation failure → 400 with field-level error messages
```

Annotations: `@NotNull`, `@NotBlank`, `@Min`, `@Max`, `@Size`, `@Pattern`

**Important**: Validation annotations only trigger for record/POJO body parameters. Path/query params (`int`, `String`, etc.) are NOT validated by annotations — use manual checks if needed.

For cross-field validation, implement `Validatable` — called automatically after annotation checks pass:

```java
record DateRange(
    @NotNull LocalDate start,
    @NotNull LocalDate end
) implements Validatable {
    public void validate() {
        Validate.isTrue(!end.isBefore(start), "end must be after start");
    }
}

record PasswordConfirm(
    @NotBlank String password,
    @NotBlank String confirm
) implements Validatable {
    public void validate() {
        Validate.isTrue(password.equals(confirm), "passwords do not match");
    }
}
```

## Plugins

```java
Aura.create()
    .plugin(app -> app.register(new RedisClient("localhost")))
    .plugin(new JwtAuthPlugin("secret"))
    .start();

// Plugin interface: single method
public interface AuraPlugin { void install(Aura app); }
```

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
    .accessLog(true)               // or env: AURA_ACCESS_LOG=true — logs: GET /path → 200 (12ms)
    .jsonConfig(c -> {             // global JSON serialization config
        c.dateFormat("yyyy-MM-dd HH:mm:ss");
        c.writeNulls(true);        // output null fields in JSON
    })
    .shutdownTimeout(30)           // graceful shutdown wait (seconds, default: 30)
    .mcp(true)                     // MCP Server for AI agents (all routes)
    .mcp(mcpRouter)                // MCP with selective tool exposure
    .set("db.url", "jdbc:mysql://...")
    .onStart(a -> a.register(Db.create(...)))
    .onStop(a -> a.getBean(Db.class).close())
    .start(args);                  // supports --config=file --port=N --env=X
```

**Graceful shutdown** is built-in: on SIGTERM/SIGINT, Aura waits up to `shutdownTimeout` seconds for in-flight requests to complete before stopping. No additional code required.

Properties read: startup args > env var > `aura.properties` > code default.

Startup args override any key: `--aura.port=9090 --db.url=jdbc:mysql://...`

Framework keys in `aura.properties`:
```properties
aura.port=8080
aura.env=dev
aura.workers=200
aura.cors=true
aura.max-body-size=10485760
aura.shutdown-timeout=30
```

Custom keys follow the same rule — `db.url=...` in `aura.properties` is overridden by env var `DB_URL`.

## Context API (when needed)

```java
ctx.path("id")   ctx.query("page")   ctx.header("Authorization")
ctx.body(T.class)  ctx.cookie("name")  ctx.method()  ctx.url()
ctx.pageNum()    ctx.pageSize()       ctx.file("field")   // UploadedFile
ctx.status(201)  ctx.json(obj)        ctx.text("ok")  ctx.redirect("/")
ctx.set(user)    ctx.get(User.class)  ctx.app().getBean(Db.class)
ctx.sse()        // SseEmitter — opens text/event-stream response

// Typed query params (return default on null/blank/parse failure)
ctx.queryInt("page", 1)          // int
ctx.queryLong("since", 0L)       // long
ctx.queryBool("active", false)   // boolean — only "true" (case-insensitive) returns true
```

**Important**: `ctx.json(row)` works directly — Row extends LinkedHashMap, so it serializes as a JSON object with all fields. No need to manually build a Map. To output only specific columns, use `.select("id, name, status")` in the query instead.

## SSE (Server-Sent Events)

```java
r.get("/stream", ctx -> {
    SseEmitter sse = ctx.sse();
    sse.send("hello");                       // data: hello
    sse.send("message", "payload");          // named event
    sse.send("update", "content", "msg-1"); // with id
    sse.close();
});

// AI streaming example
r.post("/chat", ctx -> {
    ChatReq req = ctx.body(ChatReq.class);
    SseEmitter sse = ctx.sse();
    aiClient.streamChat(req.message(), token -> sse.send("token", token));
    sse.send("done", "");
    sse.close();
});
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

## MCP (AI Agent Tools)

```java
// All routes exposed as MCP tools (simple mode)
Aura.create().mcp(true).start(args);

// Selective exposure via McpRouter (recommended)
McpRouter mcp = new McpRouter();
mcp.tool("get_user", userService, "get", "Get user by ID");
mcp.tool("list_users", userService, "list", "List all users");

// Custom handler with explicit params
mcp.tool("create_order", "Create order")
   .param("product", String.class, "Product name")
   .param("quantity", int.class, "Quantity")
   .handler(ctx -> orderService.create(ctx.getString("product"), ctx.getInt("quantity")));

// Enum auto-mapping: AI sees "NEW(新建)", code gets OrderStatus.NEW
mcp.tool("query_orders", "Query by status")
   .param("status", OrderStatus.class, "Order status")
   .handler(ctx -> orderService.findByStatus(ctx.getEnum("status", OrderStatus.class).code));

// Map mapping: AI sees "北京", code gets "010"
mcp.tool("query_city", "Query city")
   .param("city", String.class, "City", Map.of("北京", "010", "上海", "021"))
   .handler(ctx -> cityService.findByCode(ctx.getString("city")));

// Multi-API aggregation: one tool, multiple services
mcp.tool("order_detail", "Full order info")
   .param("id", int.class, "Order ID")
   .handler(ctx -> {
       var order = orderService.get(ctx.getInt("id"));
       return Map.of("order", order, "user", userService.get(order.userId()));
   });

Aura.create().mcp(mcp).start(args);
// Run: java -jar app.jar --mcp-stdio
```

McpContext methods: `getString()`, `getInt()`, `getLong()`, `getEnum(name, Type.class)`, `get()`.
URL config (npm bridge): env `AURA_API_URL` > `aura.properties` (`api.url=...`) > baked default.

## Common Patterns (avoid reinventing these)

```java
// ❌ WRONG: manually assembling a Map from Row
Map<String, Object> data = new LinkedHashMap<>();
data.put("id", row.getStr("id"));
data.put("name", row.getStr("name"));
ctx.json(data);

// ✅ RIGHT: Row IS a Map — serialize directly
ctx.json(row);
// Or select specific columns at query time:
ctx.json(db.table("user").select("id, name, status").where("id", id).findOne());

// ❌ WRONG: manual pagination
int offset = (page - 1) * pageSize;
int total = db.table("rides").where("user_id", userId).count();
List<Row> rows = db.find(sql + " LIMIT ? OFFSET ?", userId, pageSize, offset);

// ✅ RIGHT: use Query.paginate()
Page<Row> page = db.table("rides")
    .where("user_id", userId)
    .orderBy("created_at DESC")
    .paginate(ctx.pageNum(), ctx.pageSize());
ctx.json(page); // {list: [...], pageNum: 1, pageSize: 20, total: 42, totalPages: 3, ...}

// ❌ WRONG: manual delete with raw SQL
db.execute("DELETE FROM tournaments WHERE id = ?", id);

// ✅ RIGHT: use Query.delete()
db.table("tournaments").where("id", id).delete();

// ❌ WRONG: repeating auth in every method
public List<Ride> list(Context ctx) { jwt.requireAuth(ctx); ... }
public Ride get(Context ctx) { jwt.requireAuth(ctx); ... }

// ✅ RIGHT: use group + before middleware
r.group("/api", api -> {
    api.before(ctx -> jwt.requireAuth(ctx));
    api.get("/rides", rideService, "list");
    api.get("/rides/{id}", rideService, "get");
});
r.post("/auth/login", authService, "login"); // outside group = no auth
```

## Key Principles

- No XML, no YAML required, no annotations required
- Service methods are plain Java — no framework types in signatures
- One `Aura.create()...start()` is a complete app
- `aura.properties` in classpath is auto-loaded if present
- Row type roundtrip: insert with `LocalDateTime` → `findById` returns `LocalDateTime` → `update` writes back without conversion

## Important Notes

- **Validation annotations only apply to record body parameters** — path/query params (int, String, etc.) are NOT validated by `@NotBlank`, `@Min`, etc. Only record/POJO bodies trigger annotation-based validation.
- **`db.execute()` returns `int`** — the number of affected rows. Use it to check if an UPDATE/DELETE actually changed anything.
- **`@Path` annotation only works with `.service()` registration** — routes registered via `routes()` lambda do NOT process `@Path`. Use explicit path strings in the lambda.
- **Graceful shutdown is built-in** — configure via `shutdownTimeout(seconds)` or env `AURA_SHUTDOWN_TIMEOUT`. Default: 30s. Inflight requests complete before server stops.
- **`ctx.body()` on empty body gives default values** — when the request body is empty or `{}`, record fields get Java defaults (`null` for objects, `0` for int, `false` for boolean). Use `@NotBlank` on required String fields to reject empty requests.

## Packaging (Fat Jar)

```xml
<!-- In your pom.xml, set main class -->
<properties>
    <main.class>com.example.App</main.class>
</properties>
```

```bash
mvn package -Pfat-jar
java -jar target/your-app-1.0.jar
java -jar target/your-app-1.0.jar --mcp-stdio
```

To use `fat-jar`, add this profile to your project's pom.xml:

```xml
<profiles>
    <profile>
        <id>fat-jar</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-shade-plugin</artifactId>
                    <version>3.5.2</version>
                    <executions>
                        <execution>
                            <phase>package</phase>
                            <goals><goal>shade</goal></goals>
                            <configuration>
                                <createDependencyReducedPom>false</createDependencyReducedPom>
                                <transformers>
                                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                        <mainClass>${main.class}</mainClass>
                                    </transformer>
                                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                                </transformers>
                            </configuration>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

## Multi-DataSource

```java
Db main = Db.create("main", "jdbc:mysql://host/main", user, pass);
Db log  = Db.create("log", "jdbc:mysql://host/log", user, pass);

app.register("main", main).register("log", log);

// Retrieve by name
Db logDb = app.getBean("log", Db.class);

// Or just pass directly to services — no registry needed
new OrderService(main);
new AuditService(log);
```

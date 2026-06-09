# Aura Database Guide

## Query Style Decision Rule

```
db.table().where().find()          — simple CRUD (default choice)
db.findDynamic(template, map)      — multi-condition search with optional filters
db.find(sql, params)               — only for joins/subqueries
```

## Query Builder (Simple CRUD)

```java
Db db = Db.create(url, user, pass);

db.table("user").where("age", ">", 18).find();
db.table("user").where("id", 1).findOne();
db.table("user").whereNull("deleted_at").find();           // IS NULL
db.table("user").whereNotNull("email").find();             // IS NOT NULL

// IMPORTANT: where() throws if value is null or blank — use whereIf for optional conditions
// db.table("user").where("status", null)  → throws IllegalArgumentException
db.table("user").whereIf(status != null, "status", status).find();  // correct pattern

// IN queries — auto-expands Collection
db.table("user").where("id", "IN", List.of(1, 2, 3)).find();
db.table("user").where("status", "NOT IN", List.of("banned")).find();

// Conditional — whereIf skips condition when false
db.table("user")
  .whereIf(name != null, "name", name)
  .whereIf(!ids.isEmpty(), "id", "IN", ids)
  .find();

// Count
int total = db.table("user").where("active", true).count();

// Delete and update via query
db.table("user").where("status", "inactive").delete();
db.table("user").where("id", 1).update(Row.of("user").set("name", "new"));

// Shortcuts
db.findById("user", id);
db.findWhere("user", "active = ?", true);
db.deleteById("user", id);
```

## Dynamic SQL (Multi-condition Search)

```java
// Null/blank params auto-skipped — no if/else needed
String sql = "SELECT * FROM user #where(name, '=', name) #and(age, '>', age) #orderBy(created)";
db.findDynamic(sql, filterMap);                        // List<Row>
db.paginateDynamic(sql, filterMap, pageNum, pageSize); // Page<Row>

// Directives: #where(field, op, paramKey) #and(...) #or(...) #orderBy(field1, field2)
// filterMap = Map.of("name", "tom", "age", 18) → WHERE name = ? AND age > ?
// filterMap = Map.of("name", "tom")            → WHERE name = ? (age skipped)
```

## Row CRUD

```java
// Insert — returns self with generated primary key
Row row = Row.of("user").set("name", "tom").set("age", 25).insert(db);
Object id = row.id();

// insertFull() — insert + re-fetch (includes server-generated columns)
Row full = Row.of("user").set("name", "tom").insertFull(db);
full.get("created_at"); // LocalDateTime from DB

// findById → modify → update: only .set() fields are sent to UPDATE
Row found = db.findById("user", id);
found.set("name", "updated").update(db);
// Generates: UPDATE user SET name=? WHERE id=?  (not all columns)
// If no .set() was called, update sends all fields (backwards-compatible)

// db.execute() returns int (affected row count)
int affected = db.execute("UPDATE user SET active = ? WHERE id = ?", true, id);
```

## Batch Operations

```java
// Batch insert (column union — missing fields auto-filled with null)
db.batchInsert("track_points", List.of(
    Row.of("track_points").set("lat", 39.9).set("lng", 116.4),
    Row.of("track_points").set("lat", 40.0).set("lng", 116.5)
));
Row.batchInsert(db, rows);

// Batch execute
db.batch("INSERT INTO user(name, age) VALUES(?, ?)", List.of(
    new Object[]{"alice", 25},
    new Object[]{"bob", 30}
));

// Dynamic update — only non-null fields updated
db.updateDynamic("rides", Map.of("title", "New Title", "status", "published"), "id", rideId);
```

## Pagination

```java
// Built-in helpers
int page     = ctx.pageNum();   // ?page= (default 1, min 1)
int pageSize = ctx.pageSize();  // ?pageSize= (default 20, max 500)

Page<Row> page = db.table("user").where("active", true).paginate(ctx.pageNum(), ctx.pageSize());
// Use ctx.json(page) — serializes as {list, pageNum, pageSize, total, totalPages, hasNext}
```

## Transactions

```java
// Simple
db.transaction(() -> { db.execute(sql, args); });

// Nested — inner reuses outer connection, outer rollback rolls both
db.transaction(() -> {
    db.execute(sql1, args1);
    db.transaction(() -> db.execute(sql2, args2));
});

// Independent (REQUIRES_NEW) — new thread, commits independently
// WARNING: ThreadLocal state NOT inherited
db.transactionIndependent(() -> db.execute(auditSql, args));

// Manual control
try (var tx = db.beginTransaction()) {
    db.execute(sql1, args1);
    tx.commit();
}
```

## IN Query Helper

```java
// Db.in() for raw SQL
db.find("SELECT * FROM user WHERE id IN (" + Db.in(ids) + ")", ids.toArray());
// Throws IllegalArgumentException on empty list — check before calling
// Empty list in Query builder produces WHERE 1=0 (always false)
```

## Type Mapping

`rsToRow` preserves JDBC types: `Timestamp` → `LocalDateTime`, `Date` → `LocalDate`, `Time` → `LocalTime`.
`row.getStr("created_at")` calls `.toString()`. `ctx.json(row)` serializes to ISO 8601.

## Multi-DataSource

```java
Db main = Db.create("main", "jdbc:mysql://host/main", user, pass);
Db log  = Db.create("log", "jdbc:mysql://host/log", user, pass);
app.register("main", main).register("log", log);
Db logDb = app.getBean("log", Db.class);

// Custom DataSource (full HikariCP control — pool size, timeout, driver-specific props)
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:clickhouse://localhost:8123/analytics");
config.setUsername("default");
config.addDataSourceProperty("async_insert", "1");
config.setMaximumPoolSize(5);
Db analytics = Db.create("analytics", new HikariDataSource(config));
```

## Common Patterns

```java
// Row IS a Map — serialize directly, no manual assembly needed
ctx.json(row);
ctx.json(db.table("user").select("id, name, status").where("id", id).findOne());

// Use Query.paginate() instead of manual LIMIT/OFFSET
Page<Row> page = db.table("rides")
    .where("user_id", userId)
    .orderBy("created_at DESC")
    .paginate(ctx.pageNum(), ctx.pageSize());

// Use Query.delete() instead of raw SQL DELETE
db.table("tournaments").where("id", id).delete();

// Use group + before for scoped auth
r.group("/api", api -> {
    api.before(ctx -> jwt.requireAuth(ctx));
    api.get("/rides", rideService, "list");
});
r.post("/auth/login", authService, "login"); // outside = no auth
```

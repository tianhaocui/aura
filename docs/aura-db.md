# aura-db

Database access with connection pooling, query builder, Row objects, dynamic SQL, and pagination.

## Db

Main database class. Uses HikariCP connection pool.

### Setup

```java
Db db = Db.create("jdbc:mysql://localhost/mydb", "user", "pass");
// Or H2 in-memory:
Db db = Db.create("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
```

### Named DataSource

```java
Db main = Db.create("main", "jdbc:mysql://host/main", user, pass);
Db log  = Db.create("log", "jdbc:mysql://host/log", user, pass);

// Or wrap an existing DataSource
Db analytics = Db.create("analytics", existingDataSource);
```

### Cleanup

```java
// Shutdown the independent transaction thread pool (call on app stop)
Db.shutdownPool();
```

### Query Builder

```java
// Start a query
db.table("user")

// Chain conditions (null values auto-skipped)
  .where("age", ">", 18)
  .where("status", "active")     // shorthand for .where("status", "=", "active")
  .orderBy("name", "id ASC")
  .limit(10)
  .offset(20)

// Execute
  .find();                        // List<Row>
  .findOne();                     // Row or null
  .paginate(1, 20);              // Page<Row>
  .count();                       // int
  .delete();                      // int (affected rows)
  .update(Row.of("").set("status", "banned")); // int
```

### Conditional Queries

```java
// whereIf — skip condition when boolean is false
db.table("user")
  .whereIf(name != null, "name", name)
  .whereIf(!ids.isEmpty(), "id", "IN", ids)
  .find();
```

### IN / NOT IN

```java
// Query builder auto-expands Collections
db.table("user").where("id", "IN", List.of(1, 2, 3)).find();       // id IN (?,?,?)
db.table("user").where("status", "NOT IN", List.of("banned")).find(); // status NOT IN (?)
// Empty list produces WHERE 1=0 (always false, no results)

// Db.in() helper for raw SQL
db.find("SELECT * FROM user WHERE id IN (" + Db.in(ids) + ")", ids.toArray());
// Db.in() throws IllegalArgumentException on empty list
```

### Row Queries

```java
db.find("SELECT * FROM user WHERE age > ?", 18);           // List<Row>
db.findOne("SELECT * FROM user WHERE id = ?", 1);          // Row
db.findById("user", 123);                                   // Row
db.findById("user", "user_id", 123);                       // custom PK
db.findBy("user", "age > ? AND status = ?", 18, "active"); // List<Row>
// Rows returned by findById/findBy carry the table name — .update()/.delete() work directly
```

### Dynamic SQL

```java
// Directives: #where, #and, #or, #orderBy
// Null/blank params auto-skipped
String sql = "SELECT * FROM user #where(name, '=', name) #and(age, '>', age)";

Map<String, Object> filter = Map.of("name", "tom", "age", 18);
db.find(sql, filter);                        // List<Row>
db.paginateDynamic(sql, filter, PaginationOrder.by("created DESC", "id ASC"),
        1, 20, TotalStrategy.EXACT);        // Page<Row>

// If filter = Map.of("name", "tom") → WHERE name = ? (age skipped)
// If filter = Map.of() → no WHERE clause at all
```

### Execute

```java
db.execute("INSERT INTO user (name, age) VALUES (?, ?)", "tom", 25);
db.execute("UPDATE user SET status = ? WHERE id = ?", "active", 1);
db.deleteById("user", 123);
```

### Batch

```java
db.batch("INSERT INTO user (name, age) VALUES (?, ?)", List.of(
    new Object[]{"alice", 30},
    new Object[]{"bob", 25},
    new Object[]{"charlie", 35}
));
```

### Batch Insert (Row-based)

```java
// Insert multiple Rows in one batch — columns are the union of all Rows, missing fields filled with null
List<Row> points = List.of(
    Row.of("track_points").set("lat", 39.9).set("lng", 116.4).set("ts", 1000L),
    Row.of("track_points").set("lat", 40.0).set("lng", 116.5).set("ts", 2000L)
);
int count = db.batchInsert("track_points", points); // returns total affected rows

// Or via Row static method (table name taken from first Row):
Row.batchInsert(db, points);
```

### Dynamic Update

```java
// Only updates non-null fields — null means "skip this field", not "set to NULL"
Map<String, Object> data = Map.of("title", "New Title", "status", "published");
db.updateDynamic("rides", data, "id", rideId);
// Generates: UPDATE rides SET title=?, status=? WHERE id=?

// All nulls → returns 0, no SQL executed
// Use Row.update() if you need to explicitly set a column to NULL
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

### Independent Transaction

```java
// Always starts a new transaction, even if already inside one.
// Runs in a separate thread — ThreadLocal state is NOT inherited.
db.transactionIndependent(() -> db.execute(auditSql, args));
// Commits independently — survives outer transaction rollback
```

### Manual Transaction Control

```java
try (var tx = db.beginTransaction()) {
    db.execute(sql1, args1);
    db.execute(sql2, args2);
    tx.commit();   // explicit commit
    // tx.rollback() to abort; close() without commit auto-rolls back
}
// Throws IllegalStateException if already in a transaction
```

### Insert and Return ID

```java
Object generatedId = db.insertAndReturnId(
    "INSERT INTO user(name, age) VALUES(?, ?)", "alice", 25);
```

### Pagination

```java
Page<Row> page = db.table("user")
        .where("status", "active")
        .orderBy("created_at DESC", "id ASC")
        .paginate(1, 20, TotalStrategy.EXACT);
// Or with raw SQL:
Page<Row> page = db.paginate("SELECT * FROM user", new Object[]{},
        PaginationOrder.by("created_at DESC", "id ASC"),
        1, 20, TotalStrategy.NONE);
```

Pagination always requires an explicit order with a unique tie-breaker. `EXACT`
runs a count query. `NONE` skips the count, leaves `total()` and `totalPages()`
empty, and fetches one extra row to compute `hasNext()`.

Raw and dynamic queries are wrapped before the final order is applied. Their
`PaginationOrder` fields must therefore be unqualified projected column labels.
For joins, project an explicit alias and order by it, for example
`SELECT u.id AS user_id, ...` with `PaginationOrder.by("user_id ASC")`.

### Typed Queries

```java
List<User> users = db.query(
    "SELECT * FROM user WHERE age > ?",
    new Object[]{18},
    rs -> new User(rs.getInt("id"), rs.getString("name"))
);
```

### Cancellation

Pass a shared `CancellationToken` to `findCancellable`, `query`,
`executeCancellable`, or query builder operations such as
`db.table("user").find(token)`. The JDBC statement
is cancelled when the token fires and result iteration checks the token between
rows, so request and workflow shutdown can stop database work cooperatively.

### Close

```java
db.close(); // closes connection pool
```

---

## Row

Map-based data carrier (`extends LinkedHashMap<String, Object>`) with typed getters and CRUD shortcuts.

### Create

```java
Row row = Row.of("user");                    // with table name
Row row = Row.of("user", "user_id");         // custom primary key
```

### Set Values

```java
row.set("name", "tom").set("age", 25);
row.id(123);                                  // set primary key value
row.exclude("created_at", "updated_at"); // skip these columns in update()
```

### Get Values

```java
String name = row.getStr("name");
Integer age = row.getInt("age");
Long id = row.getLong("id");
Boolean active = row.getBool("active");
Object pk = row.id();                         // get primary key value
```

### CRUD

```java
row.insert(db);    // INSERT, returns self with generated primary key populated
row.insertFull(db); // INSERT + re-fetch full row (server-generated columns populated)
row.update(db);    // UPDATE by primary key, returns boolean
row.delete(db);    // DELETE by primary key, returns boolean
```

### Full Example

```java
// Create
Row row = Row.of("user").set("name", "tom").set("age", 25).insert(db);
Object id = row.id(); // generated ID

// insertFull — insert + re-fetch including server-generated columns
Row full = Row.of("user").set("name", "tom").insertFull(db);
full.get("created_at"); // LocalDateTime — populated from DB

// Read
Row user = db.findById("user", 1);
String name = user.getStr("name");

// findById → modify → update roundtrip (no type conversion needed)
Row found = db.findById("user", id);
found.set("name", "updated");
found.update(db); // LocalDateTime columns written back natively via JDBC

// Exclude server-managed columns from update
found.exclude("created_at", "updated_at").set("name", "updated").update(db);

// Targeted update (only set changed fields)
Row.of("user").id(1).set("name", "updated").update(db);

// Delete
Row.of("user").id(1).delete(db);
```

### Type Mapping

`rsToRow` preserves JDBC types — values are stored as Java types, not Strings:

| JDBC type | Java type stored in Row |
|-----------|------------------------|
| `TIMESTAMP` | `java.time.LocalDateTime` |
| `DATE` | `java.time.LocalDate` |
| `TIME` | `java.time.LocalTime` |

`row.getStr("created_at")` calls `.toString()` and works fine.
`ctx.json(row)` serializes date/time to ISO 8601 automatically.

---

## Query

Fluent query builder returned by `db.table(name)`.

| Method | Description |
|--------|-------------|
| `select(String columns)` | Columns to select (default: `*`) |
| `where(String field, String op, Object value)` | Add condition (skipped if value is null/blank) |
| `where(String field, Object value)` | Shorthand for `where(field, "=", value)` |
| `orderBy(String... fields)` | ORDER BY (validates field names) |
| `limit(int)` | LIMIT |
| `offset(int)` | OFFSET |
| `find()` | Execute, return `List<Row>` |
| `findOne()` | Execute with LIMIT 1, return `Row` or null |
| `paginate(int pageNum, int pageSize)` | Execute with pagination, return `Page<Row>` |
| `count()` | COUNT(*), return int |
| `update(Row row)` | UPDATE matching rows with Row values |
| `delete()` | DELETE matching rows |

### Valid Operators

`=`, `!=`, `<>`, `<`, `>`, `<=`, `>=`, `LIKE`, `like`, `IN`, `in`, `NOT LIKE`, `NOT IN`, `IS`, `IS NOT`

---

## Page

```java
public record Page<T>(List<T> list, int pageNum, int pageSize,
                      OptionalLong total, boolean hasNext)
```

| Method | Returns | Description |
|--------|---------|-------------|
| `list()` | `List<T>` | Current page data |
| `pageNum()` | int | Current page number |
| `pageSize()` | int | Page size |
| `total()` | `OptionalLong` | Exact total, empty with `TotalStrategy.NONE` |
| `totalPages()` | `OptionalInt` | Exact pages, empty with `TotalStrategy.NONE` |
| `hasNext()` | boolean | Has next page |
| `hasPrev()` | boolean | Has previous page |

---

## SqlKit

Dynamic SQL template parser.

```java
SqlKit.Parsed result = SqlKit.parse(template, dataMap);
String sql = result.sql();
Object[] params = result.params();
```

### Directives

| Directive | Syntax | Behavior |
|-----------|--------|----------|
| `#where` | `#where(field, 'op', paramKey)` | First condition, adds WHERE |
| `#and` | `#and(field, 'op', paramKey)` | Additional condition with AND |
| `#or` | `#or(field, 'op', paramKey)` | Additional condition with OR |
| `#orderBy` | `#orderBy(field1, field2)` | ORDER BY (whitelist validated) |

All directives auto-skip when the parameter value is null or blank.

---

## Security

- All table/column names validated: `[a-zA-Z_][a-zA-Z0-9_]*`
- All operators whitelisted
- Parameters always use `?` placeholders (never concatenated)
- Transaction connections not leaked by try-with-resources

---

## Schema migration SPI

`SchemaMigrator` is the single execution boundary. `DatabaseModule` invokes it
once during module startup and aborts startup with outer code
`AURA_MODULE_START_FAILED` plus `causeCode=DB_MIGRATION_FAILED` when it fails or
returns no result:

```java
AuraApp.create()
        .use(DatabaseModule.create(db)
                .migrateWith(SqlSchemaMigrator.classpath("db/migration")))
        .start();
```

For Flyway, add `aura-db-migration-flyway` and use the same lifecycle boundary:

```java
DatabaseModule.create(db)
        .migrateWith(FlywaySchemaMigrator.classpath("db/migration"));
```

For Liquibase, add `aura-db-migration-liquibase`:

```java
DatabaseModule.create(db)
        .migrateWith(LiquibaseSchemaMigrator.classpath("db/changelog.xml"));
```

Both adapters borrow connections from `MigrationContext.dataSource()` and
return them after each run without closing the Aura-owned `DataSource` or `Db`.
They fail startup when the configured classpath resource is missing and report
the number of migrations or changesets applied by that run. Migration runs
outside an Aura transaction so the selected engine owns locking, validation,
transactions, baseline, and dialect semantics.

`aura-db-migration-flyway` includes Flyway's PostgreSQL and MySQL/MariaDB
database-type plugins. The application must still install its chosen JDBC
driver. For Oracle, SQL Server, or another database, add the matching Flyway
database plugin at the same version as `flyway-core`.

Custom adapters implement `SchemaMigrator` and follow the same ownership rule;
`DatabaseModule` closes the database during application shutdown.

`SqlSchemaMigrator` is a lightweight implementation for tests and simple
single-process deployments. It maintains `_migrations`, validates checksums and
uses Aura transactions, but its semicolon splitter is not a Flyway/Liquibase
replacement: it does not implement vendor delimiters, stored-procedure parsing,
distributed migration locks, repair, or baseline workflows.

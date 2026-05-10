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

### Query Builder

```java
// Start a query
db.table("user")

// Chain conditions (null values auto-skipped)
  .where("age", ">", 18)
  .where("status", "active")     // shorthand for .where("status", "=", "active")
  .orderBy("name", "created DESC")
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

### Row Queries

```java
db.find("SELECT * FROM user WHERE age > ?", 18);           // List<Row>
db.findOne("SELECT * FROM user WHERE id = ?", 1);          // Row
db.findById("user", 123);                                   // Row
db.findById("user", "user_id", 123);                       // custom PK
db.findBy("user", "age > ? AND status = ?", 18, "active"); // List<Row>
```

### Dynamic SQL

```java
// Directives: #where, #and, #or, #orderBy
// Null/blank params auto-skipped
String sql = "SELECT * FROM user #where(name, '=', name) #and(age, '>', age) #orderBy(created)";

Map<String, Object> filter = Map.of("name", "tom", "age", 18);
db.find(sql, filter);                        // List<Row>
db.paginate(sql, filter, 1, 20);            // Page<Row>

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

### Transaction

```java
db.transaction(() -> {
    db.execute("UPDATE account SET balance = balance - ? WHERE id = ?", 100, 1);
    db.execute("UPDATE account SET balance = balance + ? WHERE id = ?", 100, 2);
    // Rolls back on any exception
});
// Nested transactions throw IllegalStateException
```

### Pagination

```java
Page<Row> page = db.table("user").where("status", "active").paginate(1, 20);
// Or with raw SQL:
Page<Row> page = db.paginate("SELECT * FROM user", new Object[]{}, 1, 20);
```

### Typed Queries

```java
List<User> users = db.query(
    "SELECT * FROM user WHERE age > ?",
    new Object[]{18},
    rs -> new User(rs.getInt("id"), rs.getString("name"))
);
```

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
row.update(db);    // UPDATE by primary key, returns boolean
row.delete(db);    // DELETE by primary key, returns boolean
```

### Full Example

```java
// Create
Row row = Row.of("user").set("name", "tom").set("age", 25).insert(db);
Object id = row.id(); // generated ID

// Read
Row user = db.findById("user", 1);
String name = user.getStr("name");

// Update
Row.of("user").id(1).set("name", "updated").update(db);

// Delete
Row.of("user").id(1).delete(db);
```

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
public record Page<T>(List<T> list, int pageNum, int pageSize, long total)
```

| Method | Returns | Description |
|--------|---------|-------------|
| `list()` | `List<T>` | Current page data |
| `pageNum()` | int | Current page number |
| `pageSize()` | int | Page size |
| `total()` | long | Total record count |
| `totalPages()` | int | Total pages |
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

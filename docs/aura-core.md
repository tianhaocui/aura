# aura-core

Application lifecycle, configuration, validation, and annotations.

## Aura

Main entry point. Fluent builder for configuring and starting the application.

### Quick Start

```java
// One-line startup (auto-scans caller's package)
Aura.run(args);

// With configuration
Aura.create()
    .port(8080)
    .cors(true)
    .mcp(true)
    .scan("com.example")
    .start(args);
```

### Builder Methods

| Method | Description | Default |
|--------|-------------|---------|
| `port(int)` | HTTP port | 8080 |
| `env(String)` | Environment label | "dev" |
| `workers(int)` | Undertow worker threads | 200 |
| `cors(boolean)` | Enable CORS (allow all origins) | false |
| `cors(String)` | Enable CORS with specific origin | - |
| `maxBodySize(long)` | Request body limit in bytes | 10MB |
| `shutdownTimeout(int)` | Graceful shutdown wait in seconds | 30 |
| `staticFiles(String)` | Serve static files from classpath path | - |
| `mcp(boolean)` | Enable MCP Server | false |
| `mcp(int)` | Enable MCP on specific port | - |
| `plugin(AuraPlugin)` | Register a plugin (called before start) | - |
| `corsHeaders(String)` | CORS allowed headers | "Content-Type, Authorization" |
| `prop(String, String)` | Set custom property | - |
| `onStart(Consumer<Aura>)` | Lifecycle hook (runs after server starts) | - |
| `onStop(Consumer<Aura>)` | Lifecycle hook (runs before shutdown, reverse order) | - |

### Route Registration

```java
// Direct on Aura (simple endpoints)
app.get("/path", () -> result);
app.post("/path", (ReqBody req) -> result);

// Via Router (full control)
app.routes((Router r) -> {
    r.get("/path", handler);
    r.crud("/path", service);
});

// Via service scan
app.service(new UserService());
app.scan("com.example");
```

### Properties

```java
// Set
app.prop("db.url", "jdbc:mysql://...");

// Read (priority: env var > code > aura.properties)
String url = app.prop("db.url");      // also checks env var DB_URL
int timeout = app.prop("timeout", 3000); // with int default
```

### Environment Variables

Property keys are auto-mapped to environment variables: `key.name` → `KEY_NAME`

| Property | Env Var | Description |
|----------|---------|-------------|
| `port` | `AURA_PORT` | HTTP port |
| `env` | `AURA_ENV` | Environment label |
| `db.url` | `DB_URL` | Database URL |
| `db.user` | `DB_USER` | Database user |
| `db.password` | `DB_PASSWORD` | Database password |
| Any custom | `KEY_NAME` | dot → underscore, uppercase |

Environment variables always take priority over code and config file.

### Configuration File (optional)

Place `aura.properties` in classpath root (e.g. `src/main/resources/aura.properties`):

```properties
port=8080
env=prod
db.url=jdbc:mysql://prod-server/mydb
db.user=app
db.password=secret
```

Priority: **env var > code `.prop()` > `aura.properties`**

The config file is optional. If not present, no error. All configuration can be done purely in code + env vars.

### CLI Arguments

```bash
java -jar app.jar --port=9090 --env=prod --mcp-stdio --scan=com.example
```

| Argument | Description |
|----------|-------------|
| `--port=N` | Override HTTP port |
| `--env=X` | Override environment |
| `--mcp-stdio` | Enable MCP stdio mode |
| `--scan=pkg` | Package to scan for @Path classes |
| `--key=value` | Override any property key (e.g. `--db.url=jdbc:...`) |

### Registry

```java
app.register(db);                  // store by type
Db db = app.get(Db.class);        // retrieve by type
```

### Startup

```java
app.start();          // start without CLI args
app.start(args);      // supports --port=N --env=X --mcp-stdio --scan=pkg
Aura.run(args);       // auto-detect package, scan, start
```

---

## Validate

Input validation utility. All methods throw `ValidationException` on failure (auto-returns HTTP 400).

```java
Validate.notNull(value, "field is required");
Validate.notBlank(str, "name is required");
Validate.range(age, 0, 150, "invalid age");
Validate.range(longVal, 0L, 1000L, "out of range");
Validate.minLength(str, 3, "too short");
Validate.maxLength(str, 200, "too long");
Validate.matches(email, "^.+@.+\\..+$", "invalid email");
Validate.isTrue(condition, "condition failed");
```

### ValidationException

```java
public class ValidationException extends RuntimeException
```

Framework catches this and returns HTTP 400 with the message.

---

## Validation Annotations

Auto-validate record fields when used as request body parameters. Framework calls `BeanValidator.validate()` automatically after JSON deserialization.

```java
public record CreateUser(
    @NotBlank String name,
    @Min(0) @Max(150) int age,
    @Size(min = 11, max = 11) String phone,
    @Pattern("[a-z]+@[a-z]+\\.[a-z]+") String email
) {}
```

| Annotation | Target | Validation |
|------------|--------|------------|
| `@NotNull` | Any field | value != null |
| `@NotBlank` | String | not null and not blank |
| `@Min(value)` | Number | >= value |
| `@Max(value)` | Number | <= value |
| `@Size(min, max)` | String | length between min and max |
| `@Pattern(regex)` | String | matches regex |

All annotations support a custom `message` attribute. Multiple violations are reported together.

Validation failure throws `ValidationException` → HTTP 400.

### Manual Validation

```java
BeanValidator.validate(myRecord); // throws ValidationException if invalid
```

### Cross-Field Validation (Validatable)

```java
record DateRange(
    @NotNull LocalDate start,
    @NotNull LocalDate end
) implements Validatable {
    public void validate() {
        Validate.isTrue(!end.isBefore(start), "end must be after start");
    }
}
// validate() is called automatically after annotation checks pass
```

---

## Plugin Mechanism

Extend Aura with reusable plugins. A plugin is a single-method interface.

```java
public interface AuraPlugin {
    void install(Aura app);
}
```

### Usage

```java
Aura.create()
    .plugin(new RedisPlugin("localhost:6379"))
    .plugin(new JwtAuthPlugin("my-secret"))
    .start();
```

### Writing a Plugin

```java
public class RedisPlugin implements AuraPlugin {
    private final String host;
    public RedisPlugin(String host) { this.host = host; }

    @Override
    public void install(Aura app) {
        RedisClient client = new RedisClient(host);
        app.register(client);
        app.onStop(a -> client.close());
    }
}
```

Plugins are installed in order before the server starts. They can register services, add hooks, set properties, or configure routes.

---

## Annotations

| Annotation | Target | Purpose |
|------------|--------|---------|
| `@Path("/prefix")` | Class | Base path for service routes |
| `@Get("/path")` | Method | GET endpoint |
| `@Post("/path")` | Method | POST endpoint |
| `@Put("/path")` | Method | PUT endpoint |
| `@Delete("/path")` | Method | DELETE endpoint |
| `@Desc("description")` | Class, Method, Parameter | Documentation for schema/MCP |
| `@NotNull` | Record field | Validates not null |
| `@NotBlank` | Record field | Validates not null/blank |
| `@Min(value)` | Record field | Validates >= value |
| `@Max(value)` | Record field | Validates <= value |
| `@Size(min, max)` | Record field | Validates string length |
| `@Pattern(regex)` | Record field | Validates regex match |

### Convention-based routing (no annotations needed)

Methods named `get`, `list`, `create`, `update`, `delete` are auto-mapped:

| Method name | HTTP | Path |
|-------------|------|------|
| `get(int id)` | GET | `/{id}` |
| `list()` | GET | `/` |
| `create(Req req)` | POST | `/` |
| `update(int id, Req req)` | PUT | `/{id}` |
| `delete(int id)` | DELETE | `/{id}` |

---

## RouteEntry

```java
public record RouteEntry(String method, String path, Object handler, String description)
```

Internal record for direct routes registered on Aura.

---

## SPI Interfaces

### AuraStarter

```java
public interface AuraStarter {
    void start(Aura app);
    void stop();
}
```

Implemented by `aura-web` (UndertowStarter). Discovered via ServiceLoader.

### McpStarter

```java
public interface McpStarter {
    void start(Aura app);
    void startStdio(Aura app);
    void stop();
}
```

Implemented by `aura-mcp` (AuraMcpStarter). Discovered via ServiceLoader.

---

## Fat Jar Packaging

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

The `fat-jar` profile is inherited from `aura-parent`. Produces a single executable jar with all dependencies.

---

## Multi-DataSource

```java
Db main = Db.create("main", "jdbc:mysql://host/main", user, pass);
Db log  = Db.create("log", "jdbc:mysql://host/log", user, pass);

app.register("main", main).register("log", log);

// Retrieve by name
Db logDb = app.getBean("log", Db.class);

// Or just pass directly to services
new OrderService(main);
new AuditService(log);
```

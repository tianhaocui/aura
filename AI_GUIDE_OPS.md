# Aura Operations Guide

## SSE (Server-Sent Events)

```java
context.route(new RouteContribution("GET", "/stream", (BaseHandler) ctx -> {
    SseEmitter sse = ctx.sse();
    sse.send("hello");                       // data: hello
    sse.send("message", "payload");          // named event
    sse.send("update", "content", "msg-1"); // with id
    sse.close();
}, Map.of("streaming", true)));

// AI streaming example
context.route(new RouteContribution("POST", "/chat", (BaseHandler) ctx -> {
    ChatReq req = ctx.body(ChatReq.class);
    SseEmitter sse = ctx.sse();
    aiClient.streamChat(req.message(), token -> sse.send("token", token));
    sse.send("done", "");
    sse.close();
}, Map.of("streaming", true)));
```

## File Upload

```java
// multipart/form-data
UploadedFile f = ctx.file("avatar");
f.name()        // original filename
f.data()        // byte[]
f.contentType() // MIME type
f.size()        // bytes

// Increase limit for large files (default 10MB):
AuraApp.create(new AuraConfig().maxBodySize(500 * 1024 * 1024L))
```

## File Download

```java
ctx.sendFile("report.pdf", fileBytes);                    // application/octet-stream
ctx.sendFile("data.csv", csvBytes, "text/csv");           // custom content type
```

## Common Integrations

```java
public final class InfrastructureModule implements AuraModule {
    private final Cache<String, User> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final SnapshotHttpClient upstream = new SnapshotHttpClient(
            "upstream", "https://api.example.com");

    public ModuleMetadata descriptor() {
        return ModuleMetadata.of("infrastructure", "1.0.0")
                .requires("db")
                .provides("cache", "upstream.http");
    }

    public void install(ModuleContext context) {
        context.register(cache);
        context.register(upstream);
    }

    public void start(ModuleContext context) {
        Db db = context.require(Db.class);
        scheduler.scheduleAtFixedRate(() ->
                db.table("sessions").where("expired_at", "<", LocalDateTime.now()).delete(),
                0, 1, TimeUnit.HOURS);
    }

    public void stop(ModuleContext context) {
        scheduler.shutdownNow();
        cache.invalidateAll();
    }
}

// In a request handler: bounded response + timeout + request cancellation.
User user = upstream.get("/users/" + id, User.class, ctx.cancellationToken());
```

## Configuration Details

Properties resolution priority (highest to lowest):
1. Environment variable (`AURA_ORDERS_TIMEOUT` for `aura.orders.timeout`)
2. JVM system property (`-Daura.orders.timeout=5`)
3. The `Properties`/file passed to `AuraApp.create(...)`
4. Explicit `AuraConfig` defaults

Aura does not search classpath profiles implicitly. Supply the deployment file
explicitly with `AuraApp.create(Path.of("/etc/aura.properties"))`. Startup args
support `--dev`, `--mcp-stdio`, and module properties such as
`--orders.timeout=5`; runtime settings are frozen at application creation.

Framework keys in `aura.properties`:
```properties
aura.port=8080
aura.env=dev
aura.workers=200
aura.cors=true
aura.max-body-size=10485760
aura.shutdown-timeout=30
```

Module keys such as `aura.orders.timeout` are overridden by environment variable
`AURA_ORDERS_TIMEOUT`.

**Graceful shutdown** is built-in: on SIGTERM/SIGINT, waits up to `shutdownTimeout` seconds for in-flight requests. Default: 30s.

**Request timeout**: `requestTimeout(seconds)` cancels the request token,
interrupts the handler thread, and returns 503 when no response was committed.

**Gzip**: `gzip(true)` compresses responses larger than `gzipMinSize` (default 1KB). Env: `AURA_GZIP=true`.

## Packaging (Fat Jar)

```xml
<properties>
    <main.class>com.example.App</main.class>
</properties>
```

```bash
mvn package -Pfat-jar
java -jar target/your-app-1.0.jar
java -jar target/your-app-1.0.jar --mcp-stdio
```

Fat jar properties loading: `aura.properties` and `aura-{env}.properties` are loaded from classpath root (`src/main/resources/`). In fat jar, maven-shade merges all classpath resources — if a dependency also ships `aura.properties`, use `ServicesResourceTransformer` and ensure your file is in `src/main/resources/` (not a nested path). Env vars and `--args` always take precedence regardless of packaging.

Add this profile to pom.xml:

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

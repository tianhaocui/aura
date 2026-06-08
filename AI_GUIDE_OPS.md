# Aura Operations Guide

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

## File Upload

```java
// multipart/form-data
UploadedFile f = ctx.file("avatar");
f.name()        // original filename
f.data()        // byte[]
f.contentType() // MIME type
f.size()        // bytes

// Increase limit for large files (default 10MB):
Aura.create().maxBodySize(500 * 1024 * 1024L)
```

## File Download

```java
ctx.sendFile("report.pdf", fileBytes);                    // application/octet-stream
ctx.sendFile("data.csv", csvBytes, "text/csv");           // custom content type
```

## Common Integrations

```java
// --- Local Cache (Caffeine) ---
var cache = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();
app.register(cache);
app.onStop(a -> cache.invalidateAll());
User user = cache.get(userId, id -> db.findById("user", id));

// --- Redis (Redisson) ---
var config = new Config();
config.useSingleServer().setAddress("redis://localhost:6379");
var redisson = Redisson.create(config);
app.register(redisson);
app.onStop(a -> redisson.shutdown());
RMapCache<String, Object> map = redisson.getMapCache("sessions");
map.put(token, session, 30, TimeUnit.MINUTES);

// --- Scheduled Tasks ---
var scheduler = Executors.newScheduledThreadPool(1);
app.onStart(a -> scheduler.scheduleAtFixedRate(() -> {
    db.table("sessions").where("expired_at", "<", LocalDateTime.now()).delete();
}, 0, 1, TimeUnit.HOURS));
app.onStop(a -> scheduler.shutdownNow());

// --- HTTP Client (JDK) ---
var http = HttpClient.newHttpClient();
app.register(http);
var req = HttpRequest.newBuilder(URI.create("https://api.example.com/data")).build();
var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
```

## Configuration Details

Properties read order: startup args > env var > `aura.properties` > code default.

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

Custom keys: `db.url=...` in `aura.properties` is overridden by env var `DB_URL`.

**Graceful shutdown** is built-in: on SIGTERM/SIGINT, waits up to `shutdownTimeout` seconds for in-flight requests. Default: 30s.

**Request timeout**: `requestTimeout(seconds)` — returns 503 JSON on timeout. Handler thread is NOT interrupted.

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

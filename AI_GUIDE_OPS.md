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

`AuraApp.create()` reads environment variables and JVM system properties before
freezing configuration. To add file values, use
`AuraApp.create(Path.of("/etc/aura.properties"))`; environment and JVM values
still take precedence. Aura does not search classpath profiles implicitly.
Startup args support `--dev`, `--mcp-stdio`, and module properties such as
`--orders.timeout=5`; runtime settings are frozen at application creation.

Framework keys in `aura.properties` (the same keys can be provided by the
matching `AURA_*` environment variables):
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

**Graceful shutdown** is built-in: on SIGTERM/SIGINT, waits up to
`shutdownTimeout` seconds for in-flight requests. Default: 30s. Register an
`AuraApp.observe(...)` listener for `runtime.shutdown.failure` and
`runtime.shutdown.completed`; these events are stable and omit raw exception
messages. Fatal JVM conditions are rethrown rather than hidden as ordinary
cleanup failures.

**Request timeout**: `requestTimeout(seconds)` cancels the request token,
interrupts the handler thread, and returns 503 when no response was committed.

**Gzip**: `gzip(true)` compresses responses larger than `gzipMinSize` (default 1KB). Env: `AURA_GZIP=true`.

## Admission Controls

For a simple application, configure global IP rate and concurrency limits in
`AuraConfig`. When rules differ by route or API key, one capability module owns
a `StandardAdmissionPolicy` and contributes it with
`context.admissionPolicy(policy)`. Do not implement counters in request handlers.

```java
StandardAdmissionPolicy policy = StandardAdmissionPolicy.builder()
        .perIp(100, 65_536)
        .perApiKey(500, 10_000)
        .routeRate("/orders/{id}", 20)
        .routeConcurrency("/reports", 4)
        .build();
```

## Release Gates

For ordinary local changes, run the single deterministic repository-only gate:

```bash
bash scripts/verify-local-readiness.sh
```

Use the specialized commands below only when diagnosing one gate or collecting
its individual artifact. The local entry point never triggers remote CI,
production signing, or Central Portal publication.

Generate the aggregate CycloneDX SBOM before publishing:

```bash
bash scripts/verify-sbom.sh
```

The gate writes `target/aura-sbom.json` with the reactor's runtime and compile
components, versions, licenses, and dependency relationships. CI also runs
GitHub Dependency Review on pull requests; a release must pass both the SBOM
shape check and the dependency vulnerability policy before Central publishing.
The verifier requires `jq` and rejects missing or duplicate public Aura modules,
version drift, dangling dependency references, and malformed JSON.
The CI workflow additionally runs a full OSV-Scanner scan on pull requests,
every push, and the weekly schedule, so merge-time and newly disclosed
vulnerabilities are not dependent on one narrow event type. CI archives the
verified SBOM for 90 days as
`aura-1.0-sbom`; release evidence must use that artifact rather than an
unverified file copied from a developer workstation.

Exercise the real GPG plugin without publishing anything:

```bash
bash scripts/verify-release-signatures.sh
```

The script creates an isolated one-day test key, runs the Maven `release`
profile through `verify`, and verifies every public POM, JAR, source JAR, and
Javadoc JAR signature. It never runs `deploy` and never uses a developer's
default GPG keyring. It also writes and verifies
`target/aura-release-checksums.sha256` for every artifact and `.asc` signature;
CI archives that manifest for 90 days.

Public API changes use a committed `javap` baseline:

```bash
bash scripts/verify-public-api.sh
```

An intentional public API change must update `api/aura-1.0.public-api.txt` in
the same review with `AURA_API_UPDATE=true`; do not silently regenerate it in
CI.

AI accounting is also an optional SPI. Use `AiUsageSink` for durable usage,
budget, pricing, or audit integration; keep the sink bounded and failure-isolated
so an accounting outage cannot turn a completed provider response into a request
failure.

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

The standard archetype configures Shade with both the executable manifest and
`ServicesResourceTransformer`; removing the latter breaks runtime adapter
discovery. Deployment values come from environment/JVM overrides or an explicit
file passed to `AuraApp.create(Path)`, not implicit classpath profile discovery.

The repository's canonical deployment verification is:

```bash
bash scripts/verify-deployment.sh
```

It requires Docker, kind, kubectl, Java, Maven, Python 3, curl, ripgrep, and
unzip. The script generates a fresh archetype project, builds and runs its
non-root read-only image, performs a Kubernetes server-side dry-run, deploys the
same image to kind, waits for the readiness probe, and checks `/hello`. CI runs
this command; do not replace it with text-only Dockerfile or YAML assertions.

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

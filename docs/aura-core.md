# aura-core 1.0

`aura-core` owns validated configuration, application lifecycle, the module
graph, capability lookup, health contracts, permissions, and the base request
types used by optional adapters.

## Application Entry Point

```java
AuraApp app = AuraApp.create(new AuraConfig()
        .port(8080)
        .maxBodySize(10 * 1024 * 1024L)
        .requestTimeout(30))
    .use(HealthModule.create())
    .use(new OrdersModule());

app.start();
```

Configuration is validated and frozen when `AuraApp` is created. Use
`AuraApp.create(Path)` or `AuraApp.create(Properties)` for deployment values.
The no-argument `AuraApp.create()` reads environment and JVM system properties.
Those sources also override values from a supplied properties object or file;
passing an explicit `AuraConfig` remains the programmatic, self-contained path.

The public lifecycle is:

```text
NEW -> STARTING -> RUNNING -> STOPPING -> STOPPED
```

Repeated `start()` while running and repeated `stop()` are idempotent. A stopped
runtime cannot be restarted.

Shutdown always attempts every registered cleanup even when an earlier module,
adapter, or resource fails. Observe `runtime.shutdown.failure` for each
failure and `runtime.shutdown.completed` for the final `failureCount`. Failure
events expose only a stable code, phase, component, and exception type; raw
exception messages are not emitted. During startup rollback, cleanup causes are
also attached to the original startup exception as suppressed exceptions.
Fatal JVM conditions (`VirtualMachineError` and `ThreadDeath`) are never
converted into lifecycle events; they are rethrown immediately.

## Module Contract

```java
public final class OrdersModule implements AuraModule {
    @Override public ModuleMetadata descriptor() {
        return ModuleMetadata.of("orders", "1.0.0")
                .requires("db", "[1.0,2.0)")
                .provides("orders.api");
    }

    @Override public void install(ModuleContext context) {
        Db db = context.require(Db.class);
        context.register(new OrderService(db));
        context.route(new RouteContribution(
                "GET", "/orders/{id}",
                (BaseHandler) request -> request.json(
                        db.findById("orders", request.pathLong("id")))));
        context.permissions().register("orders.read", "Read orders");
    }
}
```

`install(...)` contributes deterministic in-memory state. `start(...)` opens
external resources and `stop(...)` releases them. Dependencies are validated,
version-checked, cycle-checked, and topologically ordered before installation.

## Module Configuration

Each module reads only `aura.<module-id>.*` values and binds them to a record:

```java
record OrdersConfig(int pageSize, boolean audit) { }

OrdersConfig config = context.config(OrdersConfig.class);
```

```properties
aura.orders.pageSize=100
aura.orders.audit=true
```

Missing primitive values use Java defaults; missing wrapper/reference values
are `null`. Invalid values fail module installation with a stable error code.

## Runtime Configuration

Selected `AuraConfig` controls include:

| Setting | Default | Constraint |
|---|---:|---|
| `port` | `8080` | `0..65535` |
| `workers` | `200` | positive |
| `maxBodySize` | `10 MiB` | positive and bounded |
| `maxUploadSize` | `1 MiB` | positive, not above body size |
| `maxWebSocketMessageSize` | `1 MiB` | `1 byte..64 MiB` |
| `maxWebSocketConnections` | `10000` | `1..1000000` |
| `webSocketIdleTimeout` | `60` | `1..86400` seconds |
| `requestTimeout` | `0` | non-negative seconds; `0` disables |
| `healthCheckTimeout` | `1` | `1..300` seconds |
| `pageSizeMax` | `500` | `1..100000` |
| `concurrencyLimit` | `0` | non-negative; `0` disables |
| `rateLimitPerSecond` | `0` | non-negative; `0` disables |
| `rateLimitMaxKeys` | `65536` | `1..1000000` |
| `devMode` | `false` | explicit; never inferred from environment name |

TLS credentials are consumed internally when the runtime is created. The
public `AuraConfigSnapshot` does not expose the keystore password.

```properties
aura.tls.keystore-path=/run/secrets/server.p12
aura.tls.keystore-password=${injected-by-the-deployment}
aura.trusted-proxies=10.0.0.0/8,fd00::/8
```

The equivalent environment variables include `AURA_TLS_KEYSTORE_PATH`,
`AURA_TLS_KEYSTORE_PASSWORD`, `AURA_TRUSTED_PROXIES`,
`AURA_MAX_WEBSOCKET_CONNECTIONS`, and `AURA_WEBSOCKET_IDLE_TIMEOUT`. Trusted
proxy entries must be numeric IP addresses or CIDRs; hostnames and malformed
prefixes fail configuration validation.

## Cancellation

`CancellationToken` is the shared cooperative cancellation contract. A request
token is cancelled on timeout, transport-observed client disconnect, and
shutdown. Silent network loss cannot be observed until the transport performs
I/O, so streaming handlers should emit bounded heartbeats. Database, workflow,
AI, and external HTTP APIs accept or link this token rather than starting
untracked work.

```java
request.cancellationToken().throwIfCancelled();
```

Cancellation listeners run once. Linked tokens propagate parent cancellation.

## Health

Install `HealthModule.create()` to contribute:

- `GET /health/live`
- `GET /health/ready`
- `GET /health`

Register dependency checks with `app.healthCheck(...)` or
`ModuleContext.healthCheck(...)`. Checks execute on a bounded executor, have a
configured timeout, fail closed, and never return raw exception messages.

## Validation

Request records can use `@NotNull`, `@NotBlank`, `@Min`, `@Max`, `@Size`, and
Aura's bounded safe-regex `@Pattern`. Implement `Validatable` for cross-field
rules. Validation failures become structured 400 responses.

## Extension Rule

Publish reusable features as `AuraModule` Maven artifacts. Do not use global
registries, implicit classpath activation, or application-instance plugins.
Applications install a feature with one explicit `use(...)` call.

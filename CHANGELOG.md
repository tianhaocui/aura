# Changelog

## 0.6.2

### Added
- `after().exclude(paths...)` — path exclusion for after middleware (symmetric with before)
- `db.findOneOrThrow()` / `db.findByIdOrThrow()` — throws NotFoundException (404)
- `@RateLimit(value, window)` — method-level rate limiting annotation
- `@Scheduled(cron/fixedRate/fixedDelay)` — declarative scheduled tasks
- `app.openapi(true)` — auto-generate OpenAPI 3.0 JSON from routes + method signatures
- `ctx.queryMap()` — get all query params as Map
- `ctx.jsonRaw(String)` — send raw JSON without re-serialization
- `app.resultWrapper(Result::ok)` — auto-wrap handler return values
- `app.paramResolver(Class, Function)` — custom parameter injection (e.g. CurrentUser from token)
- `app.trustProxy(true)` — opt-in X-Forwarded-For trust for rate limiting
- Startup banner (customizable via `app.banner(text)` or disable with `app.banner(false)`)
- `NotFoundException` — framework exception mapped to 404

### Fixed
- RateLimiter race condition — synchronized sliding-window (was non-atomic check-then-act)
- RateLimiter per-method window isolation (was shared global clear cycle)
- RateLimiter memory growth — periodic key eviction (60s cycle)
- Production error responses no longer leak exception messages (returns "Internal Server Error")
- `ctx.header()` CRLF injection validation
- `/__schema__` and `/openapi.json` only served in dev mode (was bypassing auth)
- `Db.TX_CONN` changed to instance-level ThreadLocal (was static — multi-DB caused cross-contamination)
- Hot-reload clears plugins list on reload (was accumulating)
- Scheduler cron uses millisecond precision (was truncating to seconds)
- Response double-write protection via AtomicBoolean CAS
- CORS rejects `credentials=true` with wildcard origin at config time
- `Aura.run()` uses `create()` factory (supports hot-reload)
- `resultWrapper` skips null return values (prevents NPE)
- OpenAPI skips paramResolver-injected types (was emitting as requestBody)

## 0.6.1

### Added
- `services()` — @Path classes auto-register routes (DI + routing in one step)
- `app.resultWrapper(fn)` — global response wrapping
- `ctx.jsonRaw(String)` — raw JSON output without serialization
- Aura auto-registers itself as bean (services can inject `Aura`)
- Startup route table log with handler source (e.g. `GET /api/users — UserController.list`)
- `scan()` marked @Deprecated — use `services(Class...)` instead

## 0.6.0

### Added
- `services(Class...)` — auto-wiring via constructor injection + topological sort
- `Reloadable` interface — config hot-reload notification
- `app.reloadConfig()` — trigger all Reloadable components
- Error diagnostics: prop() null shows lookup path, 404 suggests closest route (Levenshtein)
- `props(prefix)` now walks full resolve chain (env > sysprop > file)
- `BeforeBuilder.and()` — chain back to Aura after exclude()
- `ServiceResolver` — dependency resolution with cycle detection + clear error messages
- `scan()` @Path classes get constructor injection

## 0.5.5

### Added
- `ctx.raw(String)` — write body without setting Content-Type
- `app.before(handler).exclude(paths...)` — path-based middleware exclusion

### Fixed
- `prop()` env key conversion: hyphens now converted to underscores (`nacos.server-addr` → `NACOS_SERVER_ADDR`)
- `onStart` hooks now fire after server port is listening (was before)
- Config resolve adds System Property layer (env > sysprop > file)

## 0.5.4

### Added
- `app.patch()`, `app.head()`, `app.options()` — full HTTP method routing (PATCH/HEAD/OPTIONS)
- HEAD auto-fallback: HEAD requests use GET handler with body suppressed when no explicit HEAD route
- `app.signJwt(String subject)` — JWT now accepts any string subject (email, UUID, etc.)
- `app.props("prefix.", RecordClass.class)` — auto-bind config properties to Record
- `app.health()` — /health endpoint, returns 503 during shutdown for K8s/LB drain
- `app.dev(true)` / `--dev` flag — hot-reload mode (requires aura-dev + JDK)
- `ctx.ip()` — client IP (X-Forwarded-For aware)
- `ctx.before()` / `ctx.after()` — app-level middleware
- `Query.exists()` — check if rows match (count > 0)
- `Row.getDouble(key)` / `Row.getDouble(key, default)` — double accessor
- `db.transactionThrows(ThrowingSupplier)` — transactions that propagate checked exceptions
- `TestClient.of(app)` — simplified TestClient creation from Aura instance
- `TestSession` — auto token management for authenticated test scenarios
- `Response.expectJson("$.path", value)` — JSONPath assertions in tests
- `Request.query("key", "value")` — query parameter builder for test requests
- `MockContext` made public — direct handler unit testing without routing
- Startup self-check — warns if `-parameters` compiler flag is missing
- Structured validation errors — `ValidationException` now carries `List<FieldError>`
- aura-dev module — ClassLoader hot-swap, code changes take effect in <1.5s

### Changed
- `ctx.userId()` returns `String` (was `long`) — supports UUID/email subjects
- `app.auth()` function type: `Function<BaseContext, String>` (was `Long`)
- `JwtSupport.verify()` returns `String` (was `Long`)
- Row preserves original column case from DB (was forced to lowercase)
- `bodyOrThrow()` validation failures return structured JSON: `{"error":"...","errors":[{"field":"name","message":"..."}]}`
- CORS Allow-Methods includes PATCH/HEAD/OPTIONS

### Migration Notes
- `ctx.userId()` now returns String — update code that casts to long: use `Long.parseLong(ctx.userId())`
- Custom `auth()` function must return `String` (not `Long`)
- `ValidationException.getMessage()` now includes field names; use `.errors()` for structured access

## 0.5.3

### Added
- `ctx.ip()` — client IP address (X-Forwarded-For first-hop)
- `app.props(prefix)` — get all properties with a given prefix as Map
- Profile auto-load: `aura-{env}.properties` loaded after `aura.properties`
- `app.before()` / `app.after()` — app-level middleware convenience
- `ctx.bodyOrThrow(Class)` — body parsing + null check + validation in one call
- `ctx.queryRequired(name)` — throws if query param missing
- Friendly port-conflict error message (no System.exit)

## 0.4.3

### Added
- Request timeout with `requestTimeout(seconds)` — returns 503 JSON on timeout (env: `AURA_REQUEST_TIMEOUT`)
- Gzip response compression with `gzip(true)` and configurable `gzipMinSize` (env: `AURA_GZIP`)
- `ctx.sendFile(filename, data)` and `ctx.sendFile(filename, data, contentType)` for file downloads
- Route conflict detection — throws `IllegalStateException` on duplicate method + path at startup
- `Query.whereNull(field)` and `Query.whereNotNull(field)` for IS NULL / IS NOT NULL conditions

### Changed
- `abort()` without explicit status now defaults to 403 Forbidden (was 200)
- `orderBy()` throws `IllegalArgumentException` on invalid field names instead of silently skipping

### Fixed
- Timeout/handler race condition — AtomicBoolean CAS ensures only one response is sent
- `generateShortId()` potential `StringIndexOutOfBoundsException` on edge-case random values
- Error log now records correct status code (logged after exception handler runs)
- Resource leaks and deprecated API usage in aura-mcp module

## 0.4.2

### Added
- `db.batchInsert(table, rows)` — batch insert with automatic column union
- `Row.batchInsert(db, rows)` — static batch insert helper
- `db.updateDynamic(table, fields, idCol, idVal)` — partial update (null values skipped)
- Typed query params: `ctx.queryInt()`, `ctx.queryLong()`, `ctx.queryBool()`

### Fixed
- Module version alignment for Maven Central deployment

## 0.4.1

### Added
- `JsonConfig` — global JSON serialization configuration (`dateFormat`, `writeNulls`)
- Route sorting — exact paths always beat parameterized paths
- `group()` routing with scoped middleware
- `Result<T>` wrapper type for standardized API responses

### Fixed
- `${project.version}` replaced with `${aura.version}` in parent pom

## 0.4.0

### Added
- WebSocket support
- MCP Server with tool auto-generation and npm packager
- `McpRouter` for selective MCP tool exposure
- Enum auto-mapping in MCP (label → code)

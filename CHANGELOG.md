# Changelog

## 0.4.3 (unreleased)

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

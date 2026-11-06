# Aura MCP Guide

## MCP (AI Agent Tools)

```java
// Install MCP explicitly in Aura 1.0
AuraApp.create().use(McpModule.create(router)).start("--mcp-stdio");

// Selective exposure via McpRouter: a tool is a Route whose handler speaks MCP.
McpRouter mcp = new McpRouter();
mcp.tool(Route.of("GET", "/user", (McpHandler) ctx -> userService.get(ctx.getLong("id")))
    .describedAs("Get user by ID")
    .withParams(List.of(new ApiParameter("id", "long", "query", true, "User ID"))));

// A tool that is already an HTTP route reuses that contract verbatim —
// description, parameter types, permission and auth all transfer. Only the handler
// shape differs, and withHandler says so.
mcp.tool(getOrderRoute.withHandler((McpHandler) ctx -> orderService.get(ctx.getLong("id"))));

// Custom handler with explicit params
mcp.tool(Route.of("POST", "/orders", (McpHandler) ctx ->
        orderService.create(ctx.getString("product"), ctx.getInt("quantity")))
    .describedAs("Create order")
    .withParams(List.of(new ApiParameter("product", "String", "query", true, "Product name"),
                        new ApiParameter("quantity", "int", "query", true, "Quantity"))));

// An enum publishes the names its own contract declares — the same list OpenAPI
// shows, and the same names ctx.getEnum() binds.
mcp.tool(Route.of("GET", "/orders", (McpHandler) ctx ->
        orderService.findByStatus(ctx.getEnum("status", OrderStatus.class).code))
    .describedAs("Query by status")
    .withParams(List.of(new ApiParameter("status", "OrderStatus", "query", true, "Order status",
        Map.of("type", "string", "enum", List.of("NEW", "PAID", "CANCEL")))));

// A label that is not a domain value ("北京" for "010") is translated by the handler
// that uses it, next to the table it comes from.
mcp.tool(Route.of("GET", "/city", (McpHandler) ctx ->
        cityService.findByCode(cityCodes.getOrDefault(ctx.getString("city"), ctx.getString("city"))))
    .describedAs("Query city")
    .withParams(List.of(new ApiParameter("city", "String", "query", true, "City",
        Map.of("type", "string", "enum", List.of("北京", "上海"))))));

// Multi-API aggregation: one tool, multiple services
mcp.tool(Route.of("GET", "/order/detail", (McpHandler) ctx -> {
        var order = orderService.get(ctx.getInt("id"));
        return Map.of("order", order, "user", userService.get(order.userId()));
    }).describedAs("Full order info")
    .withParams(List.of(new ApiParameter("id", "int", "query", true, "Order ID"))));

AuraApp.create().use(McpModule.create(mcp)).start("--mcp-stdio");
// Run: java -jar app.jar --mcp-stdio
```

The tool name comes from the route: `GET /user` is `get_user`, `DELETE /order/{id}` is
`delete_order_by_id`. A name derived from the contract is a name the model and the registry
cannot disagree about; there is no second place to state it.

For a custom catalog or dispatcher, implement dependency-neutral
`io.aura.contract.McpRouterSpec` and compose it explicitly:

```java
McpRouterSpec custom = new CompanyMcpRouter();
AuraApp.create()
    .mcpAuthenticate(metadata -> authenticate(metadata))
    .mcpAuthorize((user, permission) -> authorize(user, permission))
    .use(McpModule.create(custom).http())
    .start("--mcp-stdio");
```

The same implementation serves stdio and HTTP/SSE. Its `invoke(...)` method
must apply the supplied permission checker before executing protected tools;
do not create transport-specific adapters or bypass the checker.

For an authenticated transport, keep identity resolution and permission
decisions explicit:

```java
AuraApp.create()
    .mcpAuthenticate(metadata -> tokenService.userId(metadata.get("authorization")))
    .mcpAuthorize((user, permission) -> acl.allows(user, permission))
    .use(McpModule.create(mcp))
    .start("--mcp-stdio");
```

Transport adapters pass untrusted metadata to the authenticator. A tool handler
can read the resolved identity with `ctx.userId()`. Permissioned tools fail
closed when no authorizer or authenticated identity is available.

Handlers may return explicit MCP content when a response is not plain text:

```java
ctx -> new McpStructured(Map.of("order", order, "total", total))
ctx -> new McpImage(base64Png, "image/png")
ctx -> new McpResourceLink("orders://" + id, "application/json", json)
```

Use `McpText` for explicit text. Existing String and POJO return values remain
supported and are encoded as text/structured JSON automatically.

Enable the HTTP transport explicitly; installing `McpModule` alone does not
open an HTTP endpoint:

```java
McpModule mcpModule = McpModule.create(mcp)
    .http("/mcp", 1024, Duration.ofMinutes(30), 1024 * 1024);

AuraApp.create()
    .mcpAuthenticate(metadata -> tokenService.userId(metadata.get("authorization")))
    .mcpAuthorize((user, permission) -> acl.allows(user, permission))
    .use(mcpModule)
    .start();
```

Clients initialize with protocol version `2025-03-26`, retain the returned
`Mcp-Session-Id` response header, and send that header on later POST or DELETE
requests. Sessions are bound to the authenticated identity, have a bounded
capacity and idle TTL, and re-authenticate every request. The JSON-RPC response
body has an explicit byte limit; oversized tool/resource results fail with
`MCP_RESPONSE_TOO_LARGE`. The endpoint rejects
anonymous callers even when every registered tool is public.

## McpContext Methods

`getString()`, `getInt()`, `getLong()`, `getBoolean()`, `getDouble()`,
`getEnum(name, Type.class)`, `get()`, `userId()`

Missing values bind exactly as an HTTP query parameter does: `0`, `0L`, `false`,
`0.0`, or `null`. Both transports go through `ScalarType.fromString(value, type)`,
so a malformed argument raises `NumberFormatException` on either entry point.

## URL Configuration (npm bridge)

Env `AURA_API_URL` > `aura.properties` (`api.url=...`) > baked default.

## Deployment

- **stdio** — `java -jar app.jar --mcp-stdio`, for Claude Desktop / Cursor
- **HTTP JSON-RPC** — explicit `McpModule.create(router).http()`, default `/mcp`
- **npm publish** — `McpPackager` generates distributable npm package, `--publish` to registry

```json
{
  "mcpServers": {
    "my-app": {
      "command": "npx",
      "args": ["my-app-mcp@1.0.0"]
    }
  }
}
```

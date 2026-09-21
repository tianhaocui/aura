# Aura MCP Guide

## MCP (AI Agent Tools)

```java
// Install MCP explicitly in Aura 1.0
AuraApp.create().use(McpModule.create(router)).start("--mcp-stdio");

// Selective exposure via McpRouter (recommended)
McpRouter mcp = new McpRouter();
mcp.tool("get_user", userService, "get", "Get user by ID");
mcp.tool("list_users", userService, "list", "List all users");

// Custom handler with explicit params
mcp.tool("create_order", "Create order")
   .param("product", String.class, "Product name")
   .param("quantity", int.class, "Quantity")
   .handler(ctx -> orderService.create(ctx.getString("product"), ctx.getInt("quantity")));

// Enum auto-mapping: AI sees "NEW(新建)", code gets OrderStatus.NEW
mcp.tool("query_orders", "Query by status")
   .param("status", OrderStatus.class, "Order status")
   .handler(ctx -> orderService.findByStatus(ctx.getEnum("status", OrderStatus.class).code));

// Map mapping: AI sees "北京", code gets "010"
mcp.tool("query_city", "Query city")
   .param("city", String.class, "City", Map.of("北京", "010", "上海", "021"))
   .handler(ctx -> cityService.findByCode(ctx.getString("city")));

// Multi-API aggregation: one tool, multiple services
mcp.tool("order_detail", "Full order info")
   .param("id", int.class, "Order ID")
   .handler(ctx -> {
       var order = orderService.get(ctx.getInt("id"));
       return Map.of("order", order, "user", userService.get(order.userId()));
   });

AuraApp.create().use(McpModule.create(mcp)).start("--mcp-stdio");
// Run: java -jar app.jar --mcp-stdio
```

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

`getString()`, `getInt()`, `getLong()`, `getEnum(name, Type.class)`, `get()`, `userId()`

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

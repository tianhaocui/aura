# Aura MCP Guide

## MCP (AI Agent Tools)

```java
// All routes exposed as MCP tools (simple mode)
Aura.create().mcp(true).start(args);

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

Aura.create().mcp(mcp).start(args);
// Run: java -jar app.jar --mcp-stdio
```

## McpContext Methods

`getString()`, `getInt()`, `getLong()`, `getEnum(name, Type.class)`, `get()`

## URL Configuration (npm bridge)

Env `AURA_API_URL` > `aura.properties` (`api.url=...`) > baked default.

## Deployment

- **stdio** — `java -jar app.jar --mcp-stdio`, for Claude Desktop / Cursor
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

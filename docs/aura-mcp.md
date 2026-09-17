# aura-mcp

MCP (Model Context Protocol) integration. Exposes Aura routes as AI agent tools.

## Quick Start

```java
AuraApp.create(new AuraConfig().port(8080))
    .use(McpModule.create(mcpRouter))
    .start("--mcp-stdio");
// HTTP on :8080, MCP available via --mcp-stdio
```

模块化应用可以显式安装 `McpModule`：

```java
AuraApp.create()
    .use(McpModule.create(mcpRouter))
    .start("--mcp-stdio");
```

`McpModule` 只注册 router，不隐式打开网络监听；stdio 由启动参数启用，HTTP
由 `McpModule.create(router).http()` 显式启用。HTTP transport 默认限制响应为
1MB，可通过四参数 `.http(path, maxSessions, idleTtl, maxResponseBytes)` 调整。
HTTP responses、structured tool compatibility text 和 SSE notification 都通过
同一个 streaming byte-budget encoder 输出；超过预算会立即中止，不会先分配完整的
无界 JSON 字符串，再返回 `MCP_RESPONSE_TOO_LARGE`。

Streamable HTTP 客户端应在 POST 请求中发送
`Accept: application/json, text/event-stream`。为兼容旧客户端，当前实现允许
省略该 header，但如果显式提供则必须包含 `application/json`、
`text/event-stream` 或 `*/*`，否则返回稳定的 `406/-32005` 错误。除
`initialize` 外的请求必须携带 `Mcp-Session-Id`；缺失返回 `400/-32002`，过期或
身份不匹配的 session 返回 `404/-32002`。

POST 也接受 JSON-RPC batch，但每批最多 32 条。响应只包含带 `id` 的 request；
notification 不产生响应，整批都是 notification 时返回 `202` 空 body。`initialize`
必须作为单独请求发送，不能混入 batch。

需要 server-to-client 事件流时显式开启 SSE：

```java
McpModule module = McpModule.create(mcpRouter)
        .http("/mcp", 1024, Duration.ofMinutes(30), 1024 * 1024)
        .sse();

AuraApp.create()
        .mcpAuthenticate(metadata -> tokenService.userId(metadata.get("authorization")))
        .use(module)
        .start();
```

SSE GET 必须携带 `Accept: text/event-stream`、认证信息和
`Mcp-Session-Id`。连接会发送 `endpoint` 和 `heartbeat` 事件；业务模块可通过
`module.publish(sessionId, method, params)` 发送 server notification。每个 session
最多一个 SSE 连接，事件大小受 HTTP response budget 限制，断开和 DELETE 会清理连接。
事件带有 session 内单调递增 ID，并保留最多 128 条事件用于
`Last-Event-ID` 重连重放。当前实现不把 POST response 自动转成 SSE；需要完整
streamable HTTP 双向协商时仍应使用 POST JSON-RPC 契约并由客户端轮询响应。

## McpRouter (Selective Tool Exposure)

For a route already described by the shared `ApiRoute` contract, use
`McpRouter.tool(route, handler)`. The generated tool name, parameter names,
types, required flags, and descriptions then stay aligned with HTTP Schema and
OpenAPI output. Use the string-based builder below for MCP-only tools.

Not all APIs should be MCP tools. McpRouter lets you explicitly choose which operations to expose, with AI-friendly parameter mapping.

```java
McpRouter mcp = new McpRouter();

// Expose a service method directly
mcp.tool("get_user", userService, "get", "Get user by ID");
mcp.tool("list_users", userService, "list", "List all users");

// Custom handler with explicit parameters
mcp.tool("create_order", "Create a new order")
   .param("product", String.class, "Product name")
   .param("quantity", int.class, "Quantity")
   .handler(ctx -> orderService.create(ctx.getString("product"), ctx.getInt("quantity")));

AuraApp.create()
    .use(McpModule.create(mcp))
    .start(args);
```

Declare a permission for tools that mutate protected state. Permissioned tools
fail closed when invoked without an authenticated identity:

```java
mcp.tool("delete_order", "Delete an order")
   .param("id", long.class, "Order ID")
   .permission("orders.delete")
   .handler(ctx -> orderService.delete(ctx.getLong("id")));

mcp.invoke("delete_order", args, userId,
        (user, permission) -> acl.allows(user, permission));
```

The generated tool schema includes `x-aura-permission`. The four-argument
`invoke` overload is the authenticated transport boundary; the two-argument
overload does not bypass a declared permission.

When a tool is generated from an `ApiRoute`, its authentication and permission
metadata are copied automatically. A protected HTTP route therefore cannot
silently become an anonymous MCP tool.

When McpRouter is provided, MCP runs in-process (no HTTP roundtrip). Only registered tools are exposed.

Handlers can return explicit content blocks with `McpText`, `McpStructured`,
`McpImage`, or `McpResourceLink` when plain text is not sufficient. Existing
String and POJO return values remain supported.

### Enum Auto-Mapping

Java enums with a `label` field are automatically converted to AI-friendly descriptions:

```java
enum OrderStatus {
    NEW(1, "新建"), PAID(2, "已支付"), CANCEL(9, "已取消");
    final int code;
    final String label;
    OrderStatus(int code, String label) { this.code = code; this.label = label; }
}

mcp.tool("query_orders", "Query orders by status")
   .param("status", OrderStatus.class, "Order status")
   .handler(ctx -> {
       OrderStatus s = ctx.getEnum("status", OrderStatus.class);
       return orderService.findByStatus(s.code);
   });
```

Generated schema shows: `"enum": ["NEW(新建)", "PAID(已支付)", "CANCEL(已取消)"]`
AI sends `"PAID"` → `ctx.getEnum()` resolves to `OrderStatus.PAID`.

### Map Mapping (Label → Code)

For non-enum parameters where AI should see friendly labels but code needs internal values:

```java
mcp.tool("query_city", "Query city info")
   .param("city", String.class, "City name", Map.of("北京", "010", "上海", "021", "广州", "020"))
   .handler(ctx -> cityService.findByCode(ctx.getString("city")));
```

AI sees: `"enum": ["北京", "上海", "广州"]`
AI sends `"北京"` → `ctx.getString("city")` returns `"010"`.
Unknown labels pass through unchanged.

### Multi-API Aggregation

One MCP tool can orchestrate multiple services:

```java
mcp.tool("full_order_detail", "Get order with user and payment info")
   .param("orderId", int.class, "Order ID")
   .handler(ctx -> {
       int id = ctx.getInt("orderId");
       var order = orderService.get(id);
       var user = userService.get(order.userId());
       var payment = paymentService.getByOrder(id);
       return Map.of("order", order, "user", user, "payment", payment);
   });
```

### McpContext API

| Method | Description |
|--------|-------------|
| `getString(name)` | Get param as String (with mapping applied) |
| `getInt(name)` | Get param as int (0 if absent) |
| `getLong(name)` | Get param as long (0 if absent) |
| `getEnum(name, type)` | Get param as enum constant |
| `get(name)` | Get raw value (with mapping applied) |

---

## Deployment Modes

### Mode 1: stdio (for Claude Desktop / Cursor / IDEs)

```bash
java -jar app.jar --mcp-stdio
```

Configure in Claude Desktop:

```json
{
  "mcpServers": {
    "my-app": {
      "command": "java",
      "args": ["-jar", "/path/to/app.jar", "--mcp-stdio"]
    }
  }
}
```

App runs HTTP + stdio MCP in one process. stdout = JSON-RPC only, app logs go to stderr.

### Mode 2: npm Package (for distribution)

Generate a pure Node.js npm package:

```java
McpPackager.generate("http://your-app:8080", "@yourname/my-app-mcp", "./output");
```

```bash
cd output && npm publish --access public
```

End users configure:

```json
{
  "mcpServers": {
    "your-app": {
      "command": "npx",
      "args": ["@yourname/my-app-mcp"]
    }
  }
}
```

No Java required on end user's machine.

---

## McpBridge

Standalone MCP stdio bridge that connects to a remote Aura app via HTTP.

```java
// Programmatic
McpBridge bridge = new McpBridge("http://localhost:8080");
bridge.run(); // reads stdin, writes stdout

// CLI
java -jar aura-mcp-bridge.jar http://localhost:8080
```

### How it works

1. Fetches `/__schema__` from the app
2. Converts routes to MCP tools
3. Reads JSON-RPC from stdin
4. Translates `tools/call` to HTTP requests
5. Returns results via stdout

### Supported JSON-RPC methods

| Method | Description |
|--------|-------------|
| `initialize` | MCP handshake |
| `tools/list` | List available tools |
| `tools/call` | Invoke a tool |

The stdio protocol uses stable JSON-RPC errors for transport-level failures:

| Code | Meaning |
|------|---------|
| `-32700` | Invalid JSON |
| `-32600` | Invalid JSON-RPC request/version |
| `-32601` | Method is not supported |
| `-32602` | `params` is not an object or otherwise invalid |

Notifications (requests without an `id`) never receive a response, including
notifications that contain invalid parameters.

---

## McpPackager

Generates publishable npm packages for MCP distribution.

```java
McpPackager.generate(
    "http://your-app:8080",      // App URL (baked into package)
    "@yourname/my-app-mcp",       // npm package name
    "./mcp-output"                // Output directory
);
```

Private and local targets are rejected by default. For an explicitly trusted local
development service, use the overload with `allowPrivateUrl` set to `true`:

```java
McpPackager.generate("http://localhost:8080", "@yourname/my-app-mcp", "./mcp-output", true);
```

The CLI equivalent is `-Daura.mcp.allow-private-url=true` or
`AURA_MCP_ALLOW_PRIVATE_URL=true`.

### Generated files

```
mcp-output/
├── package.json    — npm metadata
└── index.js        — Node.js MCP bridge (pure JS, no Java dependency)
```

### CLI usage

```bash
java -cp aura-mcp.jar io.aura.mcp.McpPackager http://app:8080 @name/pkg ./out
```

### URL Configuration (npm bridge)

The generated npm bridge resolves the API URL at runtime:

1. **Environment variable**: `AURA_API_URL=http://prod-server:8080`
2. **Config file**: `aura.properties` in CWD or `$HOME` with `api.url=http://...`
3. **Baked default**: URL passed to `McpPackager.generate()`

When `/__schema__` is protected in production, set `AURA_SCHEMA_TOKEN` (or
`API_SCHEMA_TOKEN` for the npm bridge) so the bridge sends
`X-Aura-Schema-Token` while loading the schema.

Priority: env var > config file > baked default.

```properties
# aura.properties
api.url=http://localhost:8080
```

The bridge validates the URL starts with `http://` or `https://` and exits with an error otherwise.

---

## McpUtil

Utility class for MCP tool generation.

```java
// Tool naming
McpUtil.buildToolName("GET", "/user/{id}");    // → "get_user_by_id"
McpUtil.buildToolName("GET", "/user");          // → "get_user"
McpUtil.buildToolName("POST", "/user");         // → "post_user"
McpUtil.buildToolName("DELETE", "/user/{id}");  // → "delete_user_by_id"

// Type mapping
McpUtil.jsonType("int");      // → "integer"
McpUtil.jsonType("String");   // → "string"
McpUtil.jsonType("boolean");  // → "boolean"
McpUtil.jsonType("double");   // → "number"

// Schema generation
List<McpUtil.ParamInfo> params = List.of(
    new McpUtil.ParamInfo("id", "int", "User ID")
);
Map<String, Object> schema = McpUtil.buildInputSchema(params);
```

---

## Tool Naming Convention

| Route | Tool Name |
|-------|-----------|
| `GET /user/{id}` | `get_user_by_id` |
| `GET /user` | `get_user` |
| `POST /user` | `post_user` |
| `PUT /user/{id}` | `put_user_by_id` |
| `DELETE /user/{id}` | `delete_user_by_id` |
| `GET /user/search` | `get_user_search` |

---

## AuraMcpStarter

ServiceLoader implementation that starts MCP server.

```java
// Called automatically by the optional MCP starter when a transport is enabled
// HTTP mode: McpModule.create(router).http()
starter.start(app);

// stdio mode (--mcp-stdio flag):
starter.startStdio(app);
```

---

## Protocol

Aura MCP implements [Model Context Protocol](https://modelcontextprotocol.io/) version `2025-03-26`.

### Initialize response

```json
{
  "protocolVersion": "2025-03-26",
  "capabilities": {"tools": {}},
  "serverInfo": {"name": "aura-mcp", "version": "1.0.0"}
}
```

### tools/list response

```json
{
  "tools": [
    {
      "name": "get_user_by_id",
      "description": "Get user by ID",
      "inputSchema": {
        "type": "object",
        "properties": {
          "id": {"type": "integer", "description": "from path"}
        },
        "required": ["id"]
      }
    }
  ]
}
```

### tools/call

```json
// Request
{"method": "tools/call", "params": {"name": "get_user_by_id", "arguments": {"id": 1}}}

// Response
{"result": {"content": [{"type": "text", "text": "{\"id\":1,\"name\":\"Alice\"}"}]}}
```

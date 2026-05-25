# aura-mcp

MCP (Model Context Protocol) integration. Exposes Aura routes as AI agent tools.

## Quick Start

```java
Aura.create()
    .port(8080)
    .mcp(true)
    .service(new UserService())
    .start(args);
// HTTP on :8080, MCP available via --mcp-stdio
```

## McpRouter (Selective Tool Exposure)

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

Aura.create()
    .mcp(mcp)  // pass McpRouter instead of boolean
    .start(args);
```

When McpRouter is provided, MCP runs in-process (no HTTP roundtrip). Only registered tools are exposed.

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
// Called automatically by Aura when .mcp(true) is set
// SSE mode (network):
starter.start(app);

// stdio mode (--mcp-stdio flag):
starter.startStdio(app);
```

---

## Protocol

Aura MCP implements [Model Context Protocol](https://modelcontextprotocol.io/) version `2024-11-05`.

### Initialize response

```json
{
  "protocolVersion": "2024-11-05",
  "capabilities": {"tools": {}},
  "serverInfo": {"name": "aura-mcp", "version": "0.3.0"}
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

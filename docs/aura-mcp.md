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

### Custom Router SPI

实现 `io.aura.contract.McpRouterSpec` 可以替换内置 `McpRouter`，无需为 stdio、
HTTP 或 SSE 分别编写适配器：

```java
McpRouterSpec custom = new CompanyMcpRouter();
AuraApp.create()
    .mcpAuthenticate(metadata -> authenticate(metadata))
    .mcpAuthorize((user, permission) -> authorize(user, permission))
    .use(McpModule.create(custom).http().sse())
    .start("--mcp-stdio");
```

SPI 仅依赖 JDK 类型，提供 tools/resources/prompts catalog 和工具调用边界。
自定义 `invoke(...)` 必须在执行受保护工具前调用 transport 传入的 permission
checker；内置 transport 负责认证和传递 identity，但不会猜测自定义工具的权限。

Streamable HTTP 客户端应在 POST 请求中发送
`Accept: application/json, text/event-stream`。为兼容旧客户端，当前实现允许
省略该 header，但如果显式提供则必须包含 `application/json`、
`text/event-stream` 或 `*/*`，否则返回稳定的 `406/-32005` 错误。除
`initialize` 外的请求必须携带 `Mcp-Session-Id`；缺失返回 `400/-32002`，过期或
身份不匹配的 session 返回 `404/-32002`。

JSON 与 SSE 的 Accept quality 相同时，Aura 使用 JSON 快路径。已经完成握手的单个 request
可以用 `Accept: text/event-stream, application/json;q=0.5` 提高 SSE preference，
或只接受 `text/event-stream`，以选择 request-scoped SSE；服务端发送一个
`event: message`，其中 JSON-RPC `id` 与请求一致，随后关闭响应。该有限流不进入
session replay history，也不能通过 `Last-Event-ID` 恢复。notification 仍返回
`202` 空 body；`initialize` 和 batch 必须接受 `application/json`，SSE-only 返回
`406/-32005`。
认证、Content-Type 和 JSON parse 等尚未得到可靠 request `id` 的 transport error
仍使用 JSON；一旦单个对象中存在显式 `id`，Invalid Request 也遵循上述 SSE preference。

`initialize` 只创建 `NEGOTIATING` session。客户端必须随后发送一条独立、无
`id` 的 `notifications/initialized` notification，成功后 session 才进入 `READY`；
协商期间只允许 `ping`，业务调用返回 `409/-32007`。带 `id`、重复或 batch 内的
initialized notification 不会改变生命周期。后续 POST、GET 和 DELETE 可以省略
`MCP-Protocol-Version` 以兼容旧客户端；一旦显式提供就必须等于协商版本，错误版本
在刷新 TTL 或执行业务前返回 `400/-32008`。

POST 也接受 JSON-RPC batch，但每批最多 32 条。响应只包含带 `id` 的 request；
notification 不产生响应，整批都是 notification 时返回 `202` 空 body。`initialize`
必须作为单独请求发送，不能混入 batch。

需要 server-to-client 事件流时显式开启 SSE：

```java
McpModule module = McpModule.create(mcpRouter)
        .http("/mcp", 1024, Duration.ofMinutes(30), 1024 * 1024)
        .sse(32, 64 * 1024, 128, 256 * 1024);

AuraApp.create()
        .mcpAuthenticate(metadata -> tokenService.userId(metadata.get("authorization")))
        .use(module)
        .start();
```

SSE GET 必须携带 `Accept: text/event-stream`、认证信息和
`Mcp-Session-Id`。连接会发送 `endpoint` 和 `heartbeat` 事件；业务模块可通过
`module.publish(sessionId, method, params)` 发送 server notification。每个 session
最多一个 SSE 连接，事件大小受 HTTP response budget 限制，断开和 DELETE 会清理连接。
服务器 heartbeat 不刷新 session idle TTL；只有经过认证的客户端请求会续租。
每个连接的待发送队列同时受事件数和 UTF-8 总字节限制，publish 不执行 socket
写入。队列溢出时事件会先进入有界 replay history，然后当前慢连接被关闭；客户端
使用 `Last-Event-ID` 重连。history 同样具有事件数和总字节双上限，session 删除时
一起清理。请求的 ID 已早于保留窗口或超前时，服务端发送 `replay-gap` 事件并关闭，
客户端必须重新同步状态，不能把缺口当作完整重放。`module.sseMetrics()` 提供 active/closing 连接数、
history 数、排队事件/字节、overflow、gap 和写失败计数。独立 GET stream 用于
server notification；POST SSE 只承载当前 request 的 correlated response，两者不共享重放归属。

默认 queue 为 32 条/64 KiB，history 为 128 条/256 KiB。单事件解析上限仍由
HTTP response budget 控制；超过 retention byte limit 的事件会触发 overflow/gap，
不会扩大常驻内存。只有确有大事件重放需求时才显式提高 `.sse(...)` 容量。

## McpRouter (Selective Tool Exposure)

A tool is a `Route` whose handler speaks MCP. `McpRouter.tool(route)` is the only
registration door, so the parameter contract a model sees is the parameter contract HTTP
sees: name, type, required flag, description, source, and the JSON Schema of a structured
body all transfer unchanged, and the tool name is derived from the method and path instead
of invented alongside them.

Not all APIs should be MCP tools. McpRouter lets you choose which operations to expose.

<!-- aura:compile name=mcp-tool-from-route -->
```java
package docs.example;

import io.aura.contract.ApiParameter;
import io.aura.contract.Route;
import io.aura.mcp.McpHandler;
import io.aura.mcp.McpRouter;

import java.util.List;
import java.util.Map;

public final class OrderTools {
    private OrderTools() { }

    public static McpRouter router() {
        McpRouter mcp = new McpRouter();

        // A tool with no HTTP endpoint is still a Route -- one nobody serves -- declared with
        // the same ApiParameter the route catalog produces.
        mcp.tool(Route.of("POST", "/orders", (McpHandler) ctx -> new Order(
                        ctx.getString("product"),
                        ctx.getInt("quantity"),
                        ctx.getEnum("status", OrderStatus.class)))
                .describedAs("Create a new order")
                .withParams(List.of(
                        new ApiParameter("product", "String", "query", true, "Product name"),
                        new ApiParameter("quantity", "int", "query", true, "Quantity"),
                        // The accepted values are the parameter's declared schema, which is the
                        // same map OpenAPI renders -- MCP invents no vocabulary of its own.
                        new ApiParameter("status", "OrderStatus", "query", true, "Order status",
                                Map.of("type", "string", "enum", List.of("NEW", "PAID"))))));

        // A route already served over HTTP keeps every declared fact; only the handler rebinds.
        Route getUser = Route.of("GET", "/user/{id}",
                        (io.aura.port.BaseHandler) ctx -> ctx.text("served over HTTP"))
                .describedAs("Get a user")
                .withParams(List.of(new ApiParameter("id", "long", "path", true, "User identifier")));
        mcp.tool(getUser.withHandler((McpHandler) ctx -> ctx.getLong("id")));

        return mcp;
    }

    /** The name a model sends is the name the handler binds. */
    public enum OrderStatus { NEW, PAID }

    public record Order(String product, int quantity, OrderStatus status) { }
}
```

A parameter whose `required` flag is true is refused before the handler runs when the
argument is absent: the answer is `MCP_MISSING_ARGUMENT` on stdio, the HTTP transport and
the bridge alike, so a model that skipped an argument it was told to fill in learns that
instead of watching the handler run on `0`.

Passing a `Route` whose handler belongs to another transport fails at registration with
`AURA_MCP_HANDLER_MISMATCH` and names the route. Accepting it and ignoring its handler was
how a tool published a contract whose behaviour lived somewhere else.

A parameter whose type the Java-to-JSON table cannot render — a record, a class, an enum —
has to declare its schema, or registration fails with `AURA_MCP_PARAM_SCHEMA_MISSING`.
`{"type": "object"}` for a body and `{"type": "string", "enum": [...]}` for an enum is what
the route catalog and the annotation processor already attach, which is why a served route
never trips this; a hand-declared parameter has to say the same.

Binding a tool to a service method by reflection is not an entry point either: parameter
names are erased without `-parameters`, the declared types arrive as `string`, and the tool
silently disagrees with the same route's HTTP schema.

Declare a permission for tools that mutate protected state. Permissioned tools fail closed
when invoked without an authenticated identity:

```java
mcp.tool(Route.of("DELETE", "/order/{id}", (McpHandler) ctx -> orderService.delete(ctx.getLong("id")))
    .describedAs("Delete an order")
    .withParams(List.of(new ApiParameter("id", "long", "path", true, "Order ID")))
    .requireAuth("orders.delete"));

mcp.invoke("delete_order_by_id", args, userId,
        (user, permission) -> acl.allows(user, permission));
```

The generated tool schema includes `x-aura-permission`. The four-argument `invoke` overload
is the authenticated transport boundary; the two-argument overload does not bypass a
declared permission. When a tool is registered from a `Route`, its authentication and
permission metadata are copied automatically, so a protected HTTP route cannot silently
become an anonymous MCP tool.

When McpRouter is provided, MCP runs in-process (no HTTP roundtrip). Only registered tools
are exposed.

Handlers can return explicit content blocks with `McpText`, `McpStructured`, `McpImage`, or
`McpResourceLink` when plain text is not sufficient. Existing String and POJO return values
remain supported.

### Enum Parameters

An enum publishes the constant names its contract declares — the same list OpenAPI shows,
and the same names `ctx.getEnum(...)` binds, so a value a model sends means the same thing
on both transports:

```java
mcp.tool(Route.of("GET", "/orders", (McpHandler) ctx -> {
            OrderStatus status = ctx.getEnum("status", OrderStatus.class);
            return orderService.findByStatus(status.code);
        })
    .describedAs("Query orders by status")
    .withParams(List.of(new ApiParameter("status", "OrderStatus", "query", true, "Order status",
        Map.of("type", "string", "enum", List.of("NEW", "PAID", "CANCEL")))));
```

### Friendly Labels That Are Not Domain Values

A label a model sends that is not a domain value (`"北京"` for `"010"`) is mapped in the
handler that uses it, next to the table it comes from, rather than in a framework-side
lookup the transport applies on the way in:

```java
Map<String, String> cityCodes = Map.of("北京", "010", "上海", "021", "广州", "020");
mcp.tool(Route.of("GET", "/city",
        (McpHandler) ctx -> cityService.findByCode(
                cityCodes.getOrDefault(ctx.getString("city"), ctx.getString("city"))))
    .describedAs("Query city info")
    .withParams(List.of(new ApiParameter("city", "String", "query", true, "City name",
        Map.of("type", "string", "enum", List.of("北京", "上海", "广州")))));
```

The accepted values still reach the model through the declared schema; only the translation
moved to where the value is interpreted.

### Multi-API Aggregation

One MCP tool can orchestrate multiple services:

```java
mcp.tool(Route.of("GET", "/order/detail", (McpHandler) ctx -> {
            int id = ctx.getInt("orderId");
            var order = orderService.get(id);
            var user = userService.get(order.userId());
            var payment = paymentService.getByOrder(id);
            return Map.of("order", order, "user", user, "payment", payment);
        })
    .describedAs("Get order with user and payment info")
    .withParams(List.of(new ApiParameter("orderId", "int", "query", true, "Order ID"))));
```

### McpContext API

| Method | Description |
|--------|-------------|
| `getString(name)` | Get param as String |
| `getInt(name)` | Get param as int (0 if absent) |
| `getLong(name)` | Get param as long (0 if absent) |
| `getDouble(name)` | Get param as double (0 if absent) |
| `getBoolean(name)` | Get param as boolean (false if absent) |
| `getEnum(name, type)` | Bind the sent name with `Enum.valueOf` |
| `get(name)` | Get the raw value |
| `userId()` | Identity resolved by the transport, null when anonymous |


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

The bridge bounds its own resources. Each bound has the default shown and can be
set with `-D<property>=<value>`; a non-positive or unparseable value falls back to
the default, so leaving them unset behaves exactly as before.

| Property | Default | Bound |
|----------|---------|-------|
| `aura.mcp.bridge.max-response-bytes` | `1048576` | largest upstream response body read |
| `aura.mcp.bridge.connect-timeout-seconds` | `10` | TCP connect timeout |
| `aura.mcp.bridge.request-timeout-seconds` | `30` | per-`tools/call` request timeout |
| `aura.mcp.bridge.schema-timeout-seconds` | `10` | `/__schema__` fetch timeout |

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

// Schema generation — the one builder every MCP entry point uses
List<McpParam> params = List.of(
    new McpParam(new ApiParameter("id", "int", "query", true, "User ID"))
);
Map<String, Object> schema = McpUtil.inputSchema(params);
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
HTTP JSON-RPC POST requests require `Content-Type: application/json` (an optional
charset parameter is allowed) and an `Accept` value containing
`application/json` or `text/event-stream`. Other request media types fail with
HTTP 415 before authentication or JSON parsing.

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

[English](README.md)

# Aura

**A**I · **U**sable · **R**apid · **A**utonomous

AI 原生 Java 后端框架。**AI 开发 → AI 测试 → AI 使用。**

4 个模块，约 5000 行代码。一个依赖启动。无选择、无魔法、无样板代码。

## 为什么选 Aura

| | 做什么 | 怎么做 |
|--|--------|--------|
| **AI 开发** | AI 用最少上下文生成正确的后端代码 | 120 行开发指南，Just Service 模式，零注解 |
| **AI 测试** | AI 立即验证自己写的代码，不需要启动 HTTP 服务器 | 内置 TestClient，内存级路由测试 |
| **AI 使用** | AI agent 自动发现并调用你的 API | MCP 原生支持 + `/__schema__` 自描述 |

## 立即体验

添加到你的 Claude Desktop / Cursor MCP 配置：

```json
{
  "mcpServers": {
    "aura-demo": {
      "command": "npx",
      "args": ["aura-demo-mcp@0.3.0"]
    }
  }
}
```

AI agent 获得 7 个工具（创建/列表/获取/更新/删除/搜索/统计 todos）。[源码](https://github.com/tianhaocui/aura-demo)

## 快速开始

```xml
<dependency>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-web</artifactId>
    <version>0.4.2</version>
</dependency>
<!-- 需要添加 SLF4J 实现，如 logback-classic -->
```

```java
Aura.create().port(8080)
    .get("/hello", () -> "hello world")
    .start();
```

## Just Service

Service 方法就是普通 Java 方法。没有 Controller，没有 DAO，不需要注解。

```java
Aura.create().port(8080)
    .service(new UserService())
    .start();

@Path("/user")
class UserService {
    User get(int id) { return db.findById("user", id); }
    List<User> list() { return db.table("user").find(); }
    User create(CreateReq req) { /* 插入 */ return user; }
    void delete(int id) { db.deleteById("user", id); }
}

record User(int id, String name, int age) {}
record CreateReq(String name, int age) {}
```

一行代码注册 5 个 CRUD 路由。参数自动绑定：
- `int/long/String` → 先匹配路径参数，再匹配查询参数（按名称）
- `record/POJO` → 请求体（JSON）
- 非 void 返回值 → 自动 JSON 响应

## AI 测试

TestClient 在内存中运行路由。不启动 HTTP 服务器，不占端口，不等待。

```java
var app = Aura.create().service(new UserService());
var test = TestClient.of(app);

test.get("/user/1").expect(200).bodyContains("Alice");
test.post("/user").body(new CreateReq("tom", 25)).expect(200);
test.get("/notfound").expect(404);
```

AI 写代码 → AI 跑 TestClient → AI 确认通过。闭环。

## AI 使用（MCP + Schema）

每个 Aura 应用都能被 AI agent 自动发现。

```java
Aura.create().port(8080)
    .service(new UserService())
    .start(args); // 传入 --mcp-stdio 启用 MCP
// HTTP :8080, schema 在 /__schema__
```

`GET /__schema__` 返回完整 API 结构——端点、参数、类型、curl 示例。AI agent 调一次就知道所有接口。

MCP 部署方式：
- **stdio** — `java -jar app.jar --mcp-stdio`，用于 Claude Desktop / Cursor
- **npm 发布** — `McpPackager` 生成可分发的 npm 包，`--publish` 直接发布到 registry

### 选择性暴露（McpRouter）

不是所有 API 都该做成 MCP 工具。McpRouter 让你精确控制：

```java
McpRouter mcp = new McpRouter();
mcp.tool("get_user", userService, "get", "获取用户");
mcp.tool("create_order", "创建订单")
   .param("product", String.class, "商品名")
   .param("status", OrderStatus.class, "订单状态")  // 枚举自动映射
   .handler(ctx -> orderService.create(ctx.getString("product"), ctx.getEnum("status", OrderStatus.class)));

Aura.create().mcp(mcp).start(args);
```

特性：枚举自动映射（label→code）、Map 映射、多 API 聚合。详见 [docs/aura-mcp.md](docs/aura-mcp.md)。

## 数据库

```java
Db db = Db.create(url, user, pass);

// 动态 SQL — null/空白参数自动跳过（推荐用于复杂查询）
String sql = "SELECT * FROM user #where(name, '=', name) #and(age, '>', age) #orderBy(created)";
db.find(sql, filterMap);
db.paginate(sql, filterMap, pageNum, pageSize);
// ctx.pageNum() 和 ctx.pageSize() 解析 ?page= 和 ?pageSize=，带安全默认值

// Query builder — 简单 CRUD 快捷方式
db.table("user").where("age", ">", 18).orderBy("name").find();
db.table("user").where("id", 1).findOne();

// 快捷方法
db.findById("user", id);
db.deleteById("user", id);

// Row CRUD — insert() 返回自身，并填充生成的主键
Row.of("user").set("name", "tom").set("age", 25).insert(db);

// insertFull() — insert + re-fetch including server-generated columns (e.g. created_at)
Row full = Row.of("user").set("name", "tom").insertFull(db);

// findById → modify → update roundtrip (timestamp columns preserved as LocalDateTime)
Row found = db.findById("user", id);
found.set("name", "updated").update(db);

// exclude server-managed columns from update
found.exclude("created_at").set("name", "updated").update(db);

// Query builder — 删除和批量更新
db.table("user").where("status", "inactive").delete();
db.table("user").where("id", 1).update(Row.of("user").set("name", "new"));

// 批量插入 — 自动合并列，缺失列填 null
db.batchInsert("points", List.of(
    Row.of("points").set("lat", 39.9).set("lng", 116.4),
    Row.of("points").set("lat", 40.0).set("lng", 116.5)
));
// 或通过 Row 静态方法
Row.batchInsert(db, rows);

// 部分更新 — null 值跳过（只更新非 null 字段）
db.updateDynamic("user", Map.of("name", "tom", "age", 30), "id", 1);

// 事务
db.transaction(() -> {
    db.execute("UPDATE account SET balance = balance - ? WHERE id = ?", 100, 1);
    db.execute("UPDATE account SET balance = balance + ? WHERE id = ?", 100, 2);
});
```

## 文件上传

```java
// multipart/form-data
UploadedFile f = ctx.file("avatar");
f.name()        // 原始文件名
f.data()        // byte[]
f.contentType() // MIME 类型
f.size()        // 字节数
```

## SSE（服务器推送事件）

```java
r.get("/stream", ctx -> {
    SseEmitter sse = ctx.sse();
    sse.send("hello");                       // data: hello
    sse.send("message", "payload");          // 命名事件
    sse.send("update", "content", "msg-1"); // 带 id
    sse.close();
});

// AI 流式输出示例
r.post("/chat", ctx -> {
    ChatReq req = ctx.body(ChatReq.class);
    SseEmitter sse = ctx.sse();
    aiClient.streamChat(req.message(), token -> sse.send("token", token));
    sse.send("done", "");
    sse.close();
});
```

## 中间件

```java
app.routes(r -> {
    r.before(ctx -> { /* 认证、日志 */ });
    r.after(ctx -> { /* 计时 */ });
    r.group("/api", api -> {
        api.before(authMiddleware);  // 仅 /api/** 路由需要认证
        api.get("/items", itemService, "list");
        api.post("/items", itemService, "create");
    });
    r.exception(BizException.class, (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
});
```

`group()` + `before()` 是作用域认证的标准模式——公开路由放外面，受保护路由放 group 里面。

## 配置

```java
Aura.create()
    .port(8080)              // HTTP 端口
    .cors(true)              // CORS 允许所有来源
    .maxBodySize(10 * 1024 * 1024L) // 请求体大小限制（默认 10MB）
    .spa(true)               // SPA 模式：未知路径回退到 /index.html
    .mcp(true)               // 启用 --mcp-stdio 模式
    .staticFiles("/public")  // 静态文件
    .prop("db.url", "...")   // 自定义属性（环境变量 DB_URL 优先）
    .onStart(a -> { ... })   // 生命周期钩子
    .onStop(a -> { ... })
    .start(args);            // 支持 --port=N --env=X --mcp-stdio
```

## 模块

| 模块 | 用途 | 必需 |
|------|------|------|
| aura-core | 应用生命周期、配置、注册表 | 是（传递依赖） |
| aura-web | HTTP 路由、上下文、中间件、TestClient | 是 |
| aura-db | 数据库、Row、Query builder、动态 SQL | 可选 |
| aura-mcp | MCP 服务器、工具自动生成、npm 打包 | 可选 |

最小依赖：`aura-web`（自动引入 `aura-core`）。

## 生态

| 项目 | 说明 |
|------|------|
| [aura-demo](https://github.com/tianhaocui/aura-demo) | 完整 CRUD 示例，带 MCP，已发布为 npm 包 |
| [aura-skill](https://github.com/tianhaocui/aura-skill) | AI 技能生成 — 将 Aura API 转为 Claude Code skills |

## 完整示例

```java
public class App {
    public static void main(String[] args) {
        Db db = Db.create("jdbc:mysql://localhost/mydb", "root", "");

        Aura.create()
            .port(8080).cors(true)
            .onStart(a -> a.register(db))
            .onStop(a -> db.close())
            .service(new UserService(db))
            .routes(r -> {
                r.get("/health", ctx -> ctx.text("ok"));
                r.exception(Exception.class, (e, ctx) ->
                    ctx.status(500).json(Map.of("error", e.getMessage())));
            })
            .start();
    }
}
```

HTTP :8080。使用 `--mcp-stdio` 启动即可让 AI agent 访问。

## AI 编码工具集成

让你的 AI IDE 默认使用 Aura。将对应文件复制到项目根目录：

- **Cursor** → 复制 [AI_GUIDE.md](AI_GUIDE.md) 为 `.cursorrules`
- **Claude Code** → 复制 [AI_GUIDE.md](AI_GUIDE.md) 为 `CLAUDE.md`
- **Copilot** → 复制到 `.github/copilot-instructions.md`

120 行开发指南，AI 读一次就能正确生成 Aura 代码。

---
name: aura
description: "Aura Java 后端框架开发指南。用于 AI 编写 Aura 应用代码时自动加载框架上下文。当项目使用 Aura 框架（pom.xml 包含 aura-web/aura-db/aura-mcp 依赖）、或用户明确提到 Aura 框架时触发。"
---

# Aura Framework — AI Coding Reference

Aura 是轻量级 Java 17+ 后端框架。读完本文即可正确生成 Aura 代码，无需查阅其他资料。

## 核心原则

- 没有 XML、没有 YAML、没有必需注解
- 一个 `Aura.create()...start()` 就是完整应用
- Service 是普通 Java 类，无需继承/实现
- 配置只支持 .properties 格式
- DI 用 `services(Class...)`，框架自动构造器注入。`register()` 仅用于基础设施
- 路径参数语法是 `{id}`，不是 `:id`

## 项目搭建

```xml
<dependency>
    <groupId>io.github.tianhaocui</groupId>
    <artifactId>aura-web</artifactId>
    <version>0.6.0</version>
</dependency>
```

必须添加编译器参数，否则路由参数全部为 null：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <parameters>true</parameters>
    </configuration>
</plugin>
```

## 最小应用

```java
public class App {
    public static void main(String[] args) {
        Aura.create()
            .port(8080)
            .get("/hello", () -> "hello world")
            .start(args);
    }
}
```

## 路由注册

```java
// 函数式（简单场景）
app.get("/path", () -> result);
app.get("/user/{id}", (int id) -> findUser(id));
app.post("/user", (CreateReq req) -> save(req));

// Router DSL（推荐，支持中间件和分组）
app.routes(r -> {
    r.get("/users", ctx -> ctx.json(userService.list()));
    r.post("/users", ctx -> {
        var req = ctx.bodyOrThrow(CreateReq.class);
        ctx.status(201).json(userService.create(req));
    });
    r.group("/admin", admin -> {
        admin.before(Aura.requireAuth());
        admin.get("/stats", ctx -> ctx.json(stats()));
    });
});

// Service 类注册（自动路由）
app.routes(r -> r.crud("/user", userService));
```

## 请求参数获取

| 方法 | 说明 | 缺失/非法时行为 |
|------|------|----------------|
| `ctx.path("id")` | 路径参数，返回 String | 返回 null |
| `ctx.pathInt("id")` | 路径参数转 int | 抛 IllegalArgumentException |
| `ctx.pathLong("id")` | 路径参数转 long | 抛 IllegalArgumentException |
| `ctx.query("page")` | 查询参数，返回 String | 返回 null |
| `ctx.query("page", "1")` | 查询参数带默认值 | 返回默认值 |
| `ctx.queryRequired("name")` | 必填查询参数 | 抛 IllegalArgumentException |
| `ctx.queryInt("page", 1)` | 查询参数转 int，带默认值 | 返回默认值（不抛异常） |
| `ctx.queryLong("since", 0L)` | 查询参数转 long | 返回默认值 |
| `ctx.queryBool("active", false)` | 查询参数转 boolean | 返回默认值 |
| `ctx.body(Class)` | JSON 请求体反序列化 | 返回 null |
| `ctx.bodyOrThrow(Class)` | JSON 请求体 + 校验 | 抛 IllegalArgumentException |
| `ctx.header("Authorization")` | 请求头 | 返回 null |
| `ctx.cookie("name")` | Cookie | 返回 null |
| `ctx.file("field")` | 上传文件 | 返回 null |
| `ctx.formField("name")` | multipart 表单字段 | 返回 null |
| `ctx.ip()` | 客户端 IP（优先 X-Forwarded-For） | 返回 null |

## 响应输出

```java
ctx.json(obj);           // application/json (serializes object)
ctx.jsonRaw(str);        // application/json (raw string, no re-serialization)
ctx.text("ok");          // text/plain
ctx.html("<h1>Hi</h1>"); // text/html
ctx.raw(body);           // no Content-Type set (for CSV, XML, proxy responses)
ctx.status(201).json(obj);
ctx.redirect("/login");
ctx.sendFile("report.pdf", bytes);
```

函数式路由的返回值规则：
- 返回对象 → 自动 JSON
- 返回 String → text/plain
- 返回 void → 无响应体

## 异常处理

```java
// 全局注册（推荐写法）
app.exception(IllegalArgumentException.class, (e, ctx) ->
    ctx.status(400).json(Result.fail(400, e.getMessage())));
app.exception(Exception.class, (e, ctx) ->
    ctx.status(500).json(Result.fail(500, "Internal error")));
```

框架默认行为（不注册也有）：
- `IllegalArgumentException` / `ValidationException` → 400
- 其他未捕获异常 → 500
- dev 环境额外返回 stack trace

**AI 写代码时只管 throw，不需要在 handler 里 try-catch。**

## 参数校验

```java
public record CreateUser(
    @NotBlank String name,
    @Min(0) @Max(150) int age,
    @Size(min = 11, max = 11) String phone,
    @Pattern("[a-z]+@[a-z]+\\.[a-z]+") String email
) {}

// 使用
var req = ctx.bodyOrThrow(CreateUser.class); // 自动校验，失败抛异常 → 400
```

可用注解：`@NotNull`、`@NotBlank`、`@Min`、`@Max`、`@Size`、`@Pattern`

跨字段校验实现 `Validatable`：
```java
record DateRange(@NotNull LocalDate start, @NotNull LocalDate end) implements Validatable {
    public void validate() {
        Validate.isTrue(!end.isBefore(start), "end must be after start");
    }
}
```

## 中间件

```java
// app 级别（推荐，最简洁）
app.before(ctx -> log.info("{} {}", ctx.method(), ctx.url()))
   .before(Aura.requireAuth())
   .after(ctx -> log.info("done"));

// router 级别（在 routes block 内）
app.routes(r -> {
    r.before(ctx -> { /* router 前置 */ });
    r.after(ctx -> { /* router 后置 */ });

    r.group("/api", api -> {
        api.before(Aura.requireAuth()); // 分组前置：仅 /api/** 需要认证
        api.get("/items", itemService, "list");
    });
});
```

执行顺序：app.before → router.before → group.before → handler → app.after → router.after → group.after

## 认证

```java
app.jwt("secret");  // 或环境变量 AURA_JWT_SECRET

// 保护路由
r.group("/api", api -> {
    api.before(Aura.requireAuth());
    api.get("/me", ctx -> ctx.json(findUser(ctx.userId())));
});

// 签发 token
app.signJwt(userId);

// 自定义认证（OAuth、IAM、Redis session 等）
app.auth(ctx -> myClient.verify(ctx.header("X-Token")));
```

## 配置

```java
Aura.create()
    .port(8080)             // 或 AURA_PORT
    .env("dev")             // 或 AURA_ENV（影响错误详情输出）
    .cors(true)             // 或细粒度：.cors(c -> c.origins("https://app.com"))
    .accessLog("json")      // JSON 格式 access log
    .staticFiles("/public") // classpath 相对路径
    .spa(true)              // SPA 模式：未匹配路径 → index.html
    .set("app.key", "val")  // 自定义配置
    .start(args);           // 支持 --port=9090 --config=/path/to/file.properties
```

读取配置：
```java
app.prop("app.key")              // 单个值
app.prop("app.key", 8080)       // 带 int 默认值
app.props("bnp.")               // 按前缀获取所有配置，返回 Map<String, String>
```

### Profile 机制

框架自动加载配置文件：
1. `aura.properties`（基础配置）
2. `aura-{env}.properties`（环境覆盖，如 `aura-dev.properties`、`aura-prod.properties`）

env 值来自 `AURA_ENV` 环境变量或 `aura.properties` 中的 `aura.env`，默认为 `dev`。

优先级：启动参数 > 环境变量 > aura-{env}.properties > aura.properties > 代码默认值

## DI — services() 自动装配（v0.6.0+，推荐模式）

```java
// 基础设施：手动创建 + register
app.register(Db.create(app.prop("db.url"), app.prop("db.user"), app.prop("db.pass")));
app.register(app.props("engine.", EngineConfig.class));

// 业务组件：services() 自动构造器注入
app.services(
    ScoreService.class,
    MatchService.class,
    Engine.class           // 框架自动 resolve 构造器参数
);
```

规则：
- `register(instance)` = 基础设施（Db、Config Record）
- `services(Class...)` = 业务组件（框架自动创建）
- **@Path 类在 services() 里自动注册路由** — 无需额外 routes() 调用
- Aura 自身自动注册 — 任何 service 可注入 `Aura` 访问 config/beans
- 调用顺序无关，start() 时统一 resolve
- 缺失依赖 → 启动报错含 Hint
- 实现 `Reloadable` → 配置变更时自动调 reload()
- 实现 `Closeable` → stop 时自动 close
- `scan()` 已废弃 → 用 `services(Class...)` 替代

AI 加组件只需：写类 + services() 加一行。

### resultWrapper — 全局返回值包装

```java
app.resultWrapper(Result::ok);  // handler 返回值自动包 Result
```

- Handler return value → `Result.ok(value)` → JSON
- Handler throw → exceptionHandler → wrapper 不触发
- Handler 手动 ctx.json() → wrapper 不触发（response already sent）
- @Path service 方法直接 return 业务对象，不用写 ctx.json(Result.ok(...))

## 标准响应格式

```java
Result.ok(data);              // {code: 0, message: "ok", data: ...}
Result.ok("created", data);   // {code: 0, message: "created", data: ...}
Result.fail(400, "bad req");  // {code: 400, message: "bad req", data: null}
```

## 测试

```java
var app = Aura.create();
app.routes(r -> r.get("/hello", ctx -> ctx.text("world")));
var client = TestClient.of(app);

client.get("/hello").execute().expect(200).bodyContains("world");
client.post("/users").body(Map.of("name", "bob")).execute().expect(201);
client.get("/users/1").header("Authorization", "Bearer xxx").execute();
```

内存执行，无需启动 HTTP 服务器。

## 关键约定（不要猜，按这个来）

1. **路径参数用 `{id}` 不是 `:id`**
2. **Record 类自动 JSON 序列化/反序列化**（字段名 = JSON key）
3. **query param 必须手动获取**（`ctx.query("name")`），没有自动绑定
4. **DI 用 `services(Class...)`**，框架自动构造器注入。`register()` 仅用于基础设施
5. **配置文件只有 .properties**，不支持 YAML
6. **`-parameters` 编译器参数必须加**，否则所有参数为 null
7. **校验注解只对 body 参数生效**，path/query 参数不会触发
8. **staticFiles 路径是 classpath 相对路径**（jar 内的 resources 目录）
9. **异常处理用 `app.exception()` 注册**，handler 里不需要 try-catch
10. **`bodyOrThrow()` = body() + 非空检查 + BeanValidator 校验**
11. **全局中间件用 `app.before().exclude(...)`**，支持路径排除
12. **Profile 自动加载** — `aura-{env}.properties` 覆盖 `aura.properties`
13. **`app.props(prefix)` 按前缀获取配置**，走完整 resolve（env 可覆盖 file）
14. **`ctx.raw(body)` 只写 body 不设 Content-Type**，适合透传/CSV/XML
15. **端口冲突有友好提示**，不会看到原始 BindException 堆栈

## 数据库（aura-db）

详见项目内 AI_GUIDE_DB.md。核心用法：

```java
Db db = Db.create("jdbc:mysql://...", "user", "pass");
app.register(db);

// 查询
db.table("user").where("id", 1).findOne();
db.table("user").where("age", ">", 18).orderBy("name").find();

// 写入
db.table("user").insert(Map.of("name", "Alice", "age", 25));
db.table("user").where("id", 1).update(Map.of("name", "Bob"));
db.table("user").where("id", 1).delete();
```

## MCP Server（aura-mcp）

详见项目内 AI_GUIDE_MCP.md。

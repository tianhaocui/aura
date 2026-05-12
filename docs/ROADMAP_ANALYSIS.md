# Aura 框架演进路线图分析

> 基于 aura-site 官网项目实际使用反馈，2026-05-11

## 背景

aura-site 项目验证了 Aura 的核心价值：2000 行 Java 实现了 Spring Boot 需要 5000+ 行的功能。但在实际开发中暴露了一些体验短板。本文档基于真实痛点，评估各演进方向的优先级、API 设计和实现复杂度。

**核心原则：Aura 的竞争力是轻和快，任何拓展不能违背这一点。**

---

## 一、短期补充（提升日常开发体验）

### 1.1 参数校验注解

**痛点：** aura-site 中大量重复的 `Validate.notBlank()`、`Validate.min()` 手写校验，每个 Service 方法开头都是 3-5 行校验代码。

**优先级：** P0 — 影响每个接口的开发效率

**API 设计：**

```java
// 方式一：注解在 record 字段上（推荐）
public record CreatePatient(
    @NotBlank String name,
    @Min(0) @Max(150) int age,
    @Pattern("[1-9]\\d{10}") String phone
) {}

// 框架自动校验，失败返回 400：
// {"error": "name: must not be blank", "field": "name"}

// 方式二：保留手动校验（向后兼容）
Validate.notBlank(name, "name");
```

**实现复杂度：** 中等（约 300 行）
- 新增 `io.aura.annotation` 包：`@NotBlank`、`@NotNull`、`@Min`、`@Max`、`@Size`、`@Pattern`
- `MethodRefHandler` 在参数绑定时自动触发校验
- 校验失败抛 `ValidationException`，已有的异常处理机制自动返回 400

**边界判断：** 不做 Bean Validation（JSR 380）全量实现，只做最常用的 6-8 个注解。不引入外部依赖。

---

### 1.2 分页返回标准化

**痛点：** `crud()` 的 list 方法返回全量数据，需要手动读 page/pageSize 参数并调用 `paginate()`。

**优先级：** P1 — crud() 是高频使用的 API

**API 设计：**

```java
// crud() 自动分页（默认 page=1, pageSize=20）
r.crud("/api/docs", docsService);
// GET /api/docs?page=2&pageSize=10 → 自动返回 Page<Row>

// 响应格式：
{
  "data": [...],
  "page": 2,
  "pageSize": 10,
  "total": 156,
  "totalPages": 16
}

// 手动控制：
r.crud("/api/docs", docsService, CrudOptions.of().pageSize(50));
```

**实现复杂度：** 低（约 100 行）
- `Router.crud()` 的 list handler 自动读取 `page`/`pageSize` 查询参数
- 调用 Service 的 `list()` 方法时传入分页参数
- 返回标准化的 Page JSON 结构

**边界判断：** 只在 crud() 中自动分页，不强制所有 GET 接口分页。

---

### 1.3 定时任务

**痛点：** aura-site 的回访提醒、库存预警需要定时检查，目前只能用 `ScheduledExecutorService` 手写。

**优先级：** P1 — 业务刚需

**API 设计：**

```java
// 方式一：Builder API（推荐）
Aura.create()
    .cron("0 9 * * *", () -> reminderService.checkOverdue())
    .cron("*/5 * * * *", () -> stockService.checkLowStock())
    .start();

// 方式二：注解（可选，后续加）
@Schedule("0 9 * * *")
public void checkOverdue() { ... }
```

**实现复杂度：** 中等（约 200 行）
- 内置轻量 cron 解析器（只支持标准 5 字段）
- 基于 `ScheduledExecutorService`，不引入 Quartz
- `app.cron()` 注册任务，`stop()` 时自动关闭线程池
- 支持 `@Schedule` 注解（PackageScanner 扫描时注册）

**边界判断：** 不做分布式调度、不做任务持久化、不做失败重试。单机 cron 够用。

---

### 1.4 WebSocket / SSE

**痛点：** AI 对话等待时间长，需要流式输出。

**优先级：** P2 — 特定场景需要，非通用痛点

**API 设计：**

```java
// SSE（推荐，更简单）
r.sse("/api/chat/stream", ctx -> {
    ctx.sse().send("thinking", "正在分析...");
    ctx.sse().send("token", "你好");
    ctx.sse().send("done", "");
    ctx.sse().close();
});

// WebSocket
r.ws("/ws/chat", ws -> {
    ws.onMessage(msg -> ws.send("echo: " + msg));
    ws.onClose(() -> log.info("closed"));
});
```

**实现复杂度：** 中高（约 400 行）
- SSE：基于 Undertow 的 chunked response，实现简单
- WebSocket：Undertow 原生支持，需要封装 API
- 建议先做 SSE（覆盖 AI 流式场景），WebSocket 后续加

**边界判断：** SSE 优先，WebSocket 按需。不做 Socket.IO 兼容。

---

## 二、中期拓展（扩大适用场景）

### 2.1 多数据源

**痛点：** 微服务场景需要连多个库（主库 + 日志库 + 分析库）。

**优先级：** P2

**API 设计：**

```java
Db main = Db.create("main", mainUrl, user, pass);
Db log = Db.create("log", logUrl, user, pass);

app.register(main).register(log);

// 使用时通过名称获取
Db logDb = app.get("log", Db.class);
```

**实现复杂度：** 低（约 50 行）
- `Db.create()` 增加 name 参数重载
- `Aura.register()` 支持命名注册
- `app.get(name, type)` 按名称获取

**边界判断：** 不做跨库事务、不做读写分离路由。

---

### 2.2 数据库迁移

**痛点：** `initSchema()` 手动执行 DDL，多人协作时容易遗漏或冲突。

**优先级：** P2

**API 设计：**

```java
// 启动时自动执行未执行的迁移
db.migrate("classpath:migrations/");

// 迁移文件命名：
// migrations/001_create_users.sql
// migrations/002_add_email_column.sql
// migrations/003_create_orders.sql
```

**实现复杂度：** 中等（约 200 行）
- 创建 `_migrations` 表记录已执行的版本
- 按文件名数字前缀排序执行
- 支持 classpath 和文件系统路径
- 失败时回滚当前文件并报错

**边界判断：** 不做 rollback 脚本、不做 Java 迁移、不做多环境差异化。比 Flyway 轻 10 倍。

---

### 2.3 缓存层

**痛点：** 医生列表、收费项目等低频变更数据每次都查库。

**优先级：** P3

**API 设计：**

```java
// 内置内存缓存
Cache cache = app.cache();
List<Row> doctors = cache.get("doctors", () -> db.find("SELECT * FROM doctors"), Duration.ofMinutes(10));

// 手动失效
cache.evict("doctors");

// 全局配置
Aura.create().cache(CacheConfig.of().maxSize(1000).defaultTtl(Duration.ofMinutes(5)));
```

**实现复杂度：** 中等（约 150 行）
- 基于 `ConcurrentHashMap` + 过期时间戳
- LRU 淘汰（可选，基于 `LinkedHashMap`）
- 不引入 Caffeine/Guava

**边界判断：** 只做本地内存缓存。分布式缓存（Redis）通过插件机制支持。

---

### 2.4 插件机制

**痛点：** 社区无法贡献扩展，所有功能都要进主仓库。

**优先级：** P3

**API 设计：**

```java
// 插件接口
public interface AuraPlugin {
    void install(Aura app);
}

// 使用
Aura.create()
    .plugin(new RedisPlugin("localhost:6379"))
    .plugin(new JwtAuthPlugin("secret-key"))
    .start();

// 插件实现示例
public class RedisPlugin implements AuraPlugin {
    public void install(Aura app) {
        app.register(new RedisClient(host, port));
        app.onStop(a -> a.get(RedisClient.class).close());
    }
}
```

**实现复杂度：** 极低（约 30 行）
- 定义 `AuraPlugin` 接口（一个方法）
- `Aura.plugin()` 在 `start()` 前调用 `install()`
- 插件通过 `app.register()` 注入服务

**边界判断：** 不做插件市场、不做版本管理、不做热加载。就是一个接口。

---

## 三、长期方向（差异化定位）

### 3.1 AI-native 深化

**定位：** Aura 最大的差异化优势。MCP 已经有了基础，继续深化。

**API 设计：**

```java
// 已有：MCP 自动暴露 API
Aura.create().mcp(true).start();

// 增强一：更好的 AI 描述
@Desc("创建患者档案，需要姓名和手机号")
public Row create(CreatePatient req) { ... }

// 增强二：AI 中间件（实验性）
Aura.create()
    .aiAgent(true)  // 自动暴露所有 API 为 MCP tools
    .start();
```

**优先级：** P1（差异化核心）

**实现路径：**
1. 短期：完善 `@Desc` 注解在 schema 中的展示（已有基础）
2. 中期：MCP 自动生成更好的 tool description（基于参数类型推断）
3. 长期：探索 AI 中间件（意图识别、参数补全）— 需要谨慎评估复杂度

---

### 3.2 一键部署

**痛点：** 部署需要手动打包、配置 classpath。

**优先级：** P2

**API 设计：**

```bash
# Maven 插件打包
mvn aura:package
# 生成：target/app.jar（包含所有依赖 + 静态文件）

# 运行
java -jar app.jar
java -jar app.jar --port=80 --env=prod
```

**实现复杂度：** 中等（约 200 行 Maven 插件）
- 基于 maven-shade-plugin 封装
- 自动包含 `src/main/resources/public` 静态文件
- 生成可执行 jar（Main-Class manifest）

---

## 四、不做清单

| 不做 | 原因 |
|------|------|
| IoC 容器 | "new 出来就能用"是核心体验，DI 增加理解成本 |
| ORM / Entity 映射 | Row + Query builder 够用，Entity 是 Spring 的路 |
| AOP / 动态代理 | 中间件机制够用，AOP 增加调试难度 |
| 模板引擎 | 前后端分离是主流，静态文件 + API 够用 |
| 分布式事务 | 超出轻量框架定位，用消息队列解决 |
| 配置中心 | 环境变量 + properties 文件够用 |

---

## 五、优先级总览

| 优先级 | 特性 | 复杂度 | 预计工期 |
|--------|------|--------|----------|
| P0 | 参数校验注解 | 中 | 2-3 天 |
| P1 | 分页标准化 | 低 | 1 天 |
| P1 | 定时任务 | 中 | 2 天 |
| P1 | AI-native 深化 | 低-中 | 持续迭代 |
| P2 | SSE 流式输出 | 中 | 2 天 |
| P2 | 多数据源 | 低 | 半天 |
| P2 | 数据库迁移 | 中 | 2 天 |
| P2 | 一键部署 | 中 | 2 天 |
| P3 | 缓存层 | 中 | 1-2 天 |
| P3 | 插件机制 | 极低 | 半天 |
| P3 | WebSocket | 中高 | 3 天 |

**建议执行顺序：** 插件机制（30 行，先搭骨架）→ 参数校验 → 分页标准化 → 定时任务 → SSE → 其余按需

---

## 六、一句话总结

Aura 的演进策略是**做加法但不做乘法**：每个新特性独立可选，不增加已有代码的复杂度。用户不用的特性，零成本；用到的特性，一行代码开启。

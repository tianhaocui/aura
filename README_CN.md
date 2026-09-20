[English](README.md)

# Aura

**A**I · **U**sable · **R**apid · **A**utonomous

Aura 是面向 AI 开发的 Java 17+ 后端运行时。稳定性是第一条红线；第二条红线是让 AI
开发更准确、更快、更少出错、可验证、省 Token、易学习。

Aura 1.0 只有一种应用模型：能力模块与业务模块通过 `use(...)` 显式组装。同一份路由
契约同时驱动内存测试、Undertow、`/__schema__`、OpenAPI、生成代码和 MCP，避免多套
定义漂移。

## 安装

```xml
<dependency>
  <groupId>io.github.tianhaocui</groupId>
  <artifactId>aura-web</artifactId>
  <version>1.0.0</version>
</dependency>
```

只有在需要时才增加 `aura-db`、`aura-workflow`、`aura-ai`、`aura-mcp` 或
`aura-processor`。

## 创建应用

```java
AuraApp app = AuraApp.create(new AuraConfig().port(8080))
    .use(HealthModule.create())
    .use(new AuraModule() {
        @Override public ModuleMetadata descriptor() {
            return ModuleMetadata.of("hello", "1.0.0");
        }

        @Override public void install(ModuleContext context) {
            context.route(new RouteContribution(
                "GET", "/hello",
                (BaseHandler) request -> request.text("hello world")));
        }
    });

app.start();
```

每个模块都有 ID、版本、依赖要求和显式贡献。缺失依赖、版本不兼容、循环依赖和重复
路由都会在启动时用稳定错误码失败。

## 组装能力

```java
AuraApp.create()
    .use(HealthModule.create())
    .use(DatabaseModule.create(db))
    .use(WorkflowModule.create())
    .use(AiModule.create(provider))
    .use(McpModule.create(router).http().sse())
    .use(OrderModule.create())
    .start();
```

模块可以作为普通 Maven artifact 发布。用户只需增加依赖并调用一次 `use(...)`，不需要
额外转换、适配或硬编码启动逻辑。

## 针对指定用户扩展流程

基础 workflow 定义通用下单流程，variant 可以插入指定客户的步骤，不需要复制或分叉
整条流程：

```java
WorkflowDefinition<OrderContext> order = Workflow
    .<OrderContext>builder("order")
    .step("place", steps::place)
    .step("ship", steps::ship)
    .step("decrement-stock", steps::decrementStock)
    .variant("quality-customer", variant -> variant
        .insertAfter("place", "inventory-check", steps::inventoryCheck)
        .insertAfter("inventory-check", "quality-check", steps::qualityCheck))
    .build();
```

通过 `WorkflowSelector` 根据租户或用户上下文选择 variant。框架在执行前校验最终 DAG，
并统一处理重试、取消、补偿和幂等语义。

## 不启动服务器也能验证

```java
AuraApp app = AuraApp.create()
    .use(OrderModule.create())
    .prepare();

TestClient test = TestClient.of(app);
test.post("/orders").body(new CreateOrder("sku-1", 2)).expect(200);
test.get("/orders/1").expect(200).bodyContains("sku-1");

Map<String, Object> schema = test.schema();
Map<String, Object> openApi = test.openApi();
```

这是标准 AI 闭环：生成代码、编译、运行 `TestClient`、读取结构化错误，确认后再启动真实
服务。

## 生产边界

- 不可变且经过校验的 `AuraConfigSnapshot`。
- 确定的 `NEW -> STARTING -> RUNNING -> STOPPING -> STOPPED` 生命周期。
- 请求体、上传、外部响应、线程池、快照、MCP batch/replay 和限流 key 全部有界。
- 超时、断连、停机、workflow、数据库和 AI 调用都传播取消信号。
- CORS、WebSocket Origin、可信代理、Host、TLS 和 schema 暴露均显式配置。
- 启动、绑定、路由和运行时错误都有稳定、可搜索的结构化错误码。

## 模块

| 模块 | 作用 |
|---|---|
| `aura-contract` | 轻依赖的模块、路由、错误、健康和取消契约 |
| `aura-core` | 配置、生命周期、模块图、能力注册表 |
| `aura-web` | Undertow、路由、schema/OpenAPI、快照、`TestClient` |
| `aura-db` | 轻量 SQL、事务、分页、数据库 schema 迁移执行 SPI |
| `aura-workflow` | 类型化 DAG、variant、重试、补偿、取消 |
| `aura-ai` | Provider SPI、同步/SSE、工具调用、结构化输出、预算 |
| `aura-mcp` | tools/resources/prompts、鉴权 HTTP/SSE/stdio transport |
| `aura-processor` | 可选的路由元数据和参数绑定代码生成 |

## 文档

- [AI 开发指南](AI_GUIDE.md)
- [1.0 总文档](docs/DOCUMENTATION.md)
- [模块开发](docs/aura-module.md)
- [从旧版本迁移](docs/migration-to-1.0.md)
- [可运行的模块组装示例](examples/aura-composed)
- [可运行的代码生成示例](examples/aura-generated)

1.0 之前的示例只保留在带版本号的 legacy 文档中，用于迁移参考。新应用统一使用
`AuraApp` 和显式模块。

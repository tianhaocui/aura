# Aura AI Native — Application Manifest 需求文档（Phase M）

状态：已实现（机制提交 f3ca04f：FR-1～FR-5；gate 与文档见本分支提交：FR-6～FR-8）
日期：2026-11-18
前置提交：`6158f09`（三个 bug 修复）、`7bd8d86`（Capability 层 + MCP 投影，Phase 1）

---

## 1. 背景与目标

核心架构原则：**One Explicit Definition, Multiple Verifiable Projections** —— Coding Agent
与 Runtime Agent 不共享接口，而是共享同一个权威定义模型；Manifest 与 Runtime Registry 是该模型的
两种投影，通过一致性检查约束漂移。

Phase 1 已交付权威定义（`CapabilityDefinition`）、显式注册（`Capability.of` →
`CapabilityRegistry`）、单一所有权（`CapabilityModule`）与可查询的 MCP 投影
（`capabilityBindings()` + `x-aura-capability`）。本阶段补齐 Phase 1 Definition of Done 中
刻意推迟的三项：

| DoD | 内容 |
|---|---|
| 4 | 生成 Manifest JSON，包含模块、依赖、能力、参数和权限 |
| 7 | Manifest 与 Runtime Registry 可以**独立**对账 |
| 8 | 不启动 HTTP Listener 也能完成 prepare 和 Manifest 生成 |

目标不变：每一项 AI 能力都能**从源码找到定义、从 Manifest 看见契约、从 Runtime 验证执行**；
不引入任何需要 Agent 猜测的隐式行为。

## 2. 已确认决策

| 事项 | 决策 |
|---|---|
| JSON 库 | **Gson**（`com.google.code.gson:gson`），引入 aura-core；最新稳定版，版本在根 pom `dependencyManagement` 统一管理 |
| Manifest 内容 | 仅静态声明：模块、依赖、能力、参数、权限；**不含运行状态** |
| 聚合来源 | `moduleDescriptors()` + `CapabilityRegistry.schema()` + `PermissionRegistry.snapshot()` |
| 运行时检查 | opt-in：配置属性指向 classpath 资源；未配置则行为完全不变 |
| 检查时机 | `app.prepare()` / `start()` 安装成功后（无 listener 也可完成） |
| MCP 投影对账 | 落在 aura-mcp（core 不反向依赖 McpUtil）；以**定义本身**为独立声明侧 |
| CI gate | 照 evals 范式：committed JSON + drift 测试 + 脚本证明真实执行 |
| 注解扫描 | 不做 |
| ModuleContext | 不修改公共接口 |
| API 变更 | 仅 additive；public-api 快照刻意更新（`AURA_API_UPDATE=true`） |

Phase 1 冻结的五细节继续有效：① 业务名与 MCP 工具名显式映射；② 三态 AccessPolicy；
③ ApiParameter.schema 是唯一 Schema 权威；④ Registry 单一所有权；⑤ 声明与运行状态分离。

## 3. 范围

**做**：Manifest 聚合/渲染/对账（core）、prepare() opt-in 检查（core）、MCP 投影对账（mcp）、
参考应用 harness 与 committed manifest、CI drift gate 脚本、文档、API 快照。

**不做**：运行状态入 Manifest（session、用户、审批、Invocation Record、动态授权结果）；
MCP 段进 core 聚合；反射推导 Schema；新 Maven 模块；post-install 通用 seam；
Semantic Layer（Phase 2）；诊断/CodedFailure 升级（Phase 2）。

## 4. 功能需求

### FR-1 Manifest 聚合（aura-core，`io.aura.manifest.ApplicationManifest`）

`generate(AuraRuntime)` → `Map<String, Object>`，固定字段序：

- `schemaVersion: 1`
- `modules`：按 id 排序；每项含 `id`、`version`、`requires`（id + versionRange，来自
  `ModuleRequirement`）、`provides`。来源是 ModuleGraph 描述符——独立于任何 registry。
- `capabilities`：`find(CapabilityRegistry.class)` 存在时取 `registry.schema()`
  （Phase 1 已实现的逐字声明投影，按名排序）；未安装 capability 层 → 空列表。
- `permissions`：`runtime.permissions().snapshot()`，按 id 排序（声明-only 注册表）。
- `app.name` 等冻结配置可含；**端口、状态、会话等运行态一律排除**。

### FR-2 确定性 JSON 渲染

`toJson(Map)` → String：

- Gson 实例：`disableHtmlEscaping()`；committed 工件用 `setPrettyPrinting()`（固定缩进）。
- 不启用 `serializeNulls`：缺席元数据保持缺席（与 RouteContract 同一 doctrine）。
- 字段序由聚合侧 LinkedHashMap 固定；Gson 按 Map 迭代序输出 → 再生成**字节一致**。
- 无时间戳、无随机量。

### FR-3 结构化对账

`reconcile(declared, actual)` → 差异列表（非布尔）：

- 缺失/多余的模块、能力、权限条目（双向集合差）。
- 共有条目逐字段深比较：version、requires、provides、access（policy + permission）、
  params（name/type/source/required/description/schema 逐字）。
- **数值归一化**：Gson 把 JSON 数字解析为 Double（`1` → `1.0`），运行时 Map 是 Integer/Long；
  比较必须按数值相等（如 BigDecimal compareTo），不得按 Java 类型相等。
- 差异信息足以定位：条目名 + 字段路径 + 两侧值。供 prepare() 抛错与 CI 报告共用。

### FR-4 prepare()/start() opt-in 一致性检查（core）

- 配置属性 `aura.manifest` = classpath 资源路径（命名惯例实现时核对现有属性风格）。
- 未设置 → 零行为变化（既有测试不动即证明）。
- 设置 → 安装成功后：解析资源 → `generate()` 现值 → `reconcile()`；
  有漂移 → 抛 `AURA_MANIFEST_DRIFT`（携带差异），走既有 `failStart` 回滚机制。
- 资源不存在/不可解析 → 同样 fail-closed（配置了就必须可读）。
- 幂等：重复 prepare/start 不产生重复副作用；检查只读，不修改任何注册表。

### FR-5 MCP 投影对账（aura-mcp）

`McpRouter.reconcileCapabilities(CapabilityRegistry)`：

- 对每个 `capabilityBindings()` 条目，从 `CapabilityDefinition` 经**同一 funnel** 重算期望事实：
  工具名 = `McpUtil.capabilityToolName(name)`；参数 = `McpParam` 门 + `McpUtil.inputSchema`；
  authenticated/permission = `AccessPolicy` 映射；description 逐字。
- 与实际 `tools()` / `buildSchema()` 逐项比对；不符 → `AURA_MCP_PROJECTION_DRIFT`（指明工具与字段）。
- 检测无绑定却携带 `x-aura-capability` 的工具。
- 能力未投影到 MCP **不是**漂移（投影是逐能力的显式组合决策）。
- 调用方：测试、CI harness；不接入 core 生命周期（避免反向依赖与新 seam）。

### FR-6 参考应用 harness（examples/aura-composed）

- 参考组合增加显式 capability 贡献（`requires("capability")` + `Capability.of`）。
- committed `manifest.json`（FR-2 渲染）。
- `ManifestDriftTest`：compose → `prepare()`（无 listener）→ `generate()` → 与 committed
  文件对账 + `reconcileCapabilities()`；shape 断言（schemaVersion、必备段）。
- `AURA_MANIFEST_UPDATE=true` 时刻意重写 committed 文件（同 `AURA_API_UPDATE` 模式）。

### FR-7 CI drift gate

- `scripts/verify-manifest.sh`：运行 drift 测试，并**要求本次运行新写出的 surefire 报告**
  （`verify-ai-evals.sh` 的证明执行范式——文件存在不等于测试跑过）。
- `ci.yml` 增加步骤；`verify-aura-contracts.sh` 增加脚本存在性断言。

### FR-8 文档

- core 文档新增 Manifest 节：聚合来源、opt-in 属性、对账语义、Gson 渲染约定、
  与 Capability/MCP 的关系；示例用 `<!-- aura:compile -->` 围栏，过 `verify-docs-code.sh`。

## 5. 独立对账矩阵（防自比）

验证不得拿 Registry 生成的 Manifest 再与 Registry 比较。每项检查两侧来源独立：

| # | 检查 | 声明侧 | 运行侧 |
|---|---|---|---|
| 1 | 声明模块全部安装 | committed manifest.modules | `moduleDescriptors()`（ModuleGraph） |
| 2 | 声明能力全部注册 | manifest.capabilities 名集 | `registry.snapshot()` 键集 |
| 3 | 无未声明注册项 | 同 2，反向子集 | 同 2 |
| 4 | 权限/参数/Schema 一致 | manifest 能力条目 | `registry.schema()` 条目（两侧均逐字来自 ApiParameter） |
| 5 | MCP 投影不丢失/不改变定义 | **CapabilityDefinition 本身** | `router.tools()` / `buildSchema()` |

committed manifest 的生成与运行时聚合走同一 `generate()` 代码路径，但**对账基准是 committed
文件**（构建期从源码组合产生、经 CI 守护），不是运行时现生成值——这是非循环性的来源。

## 6. 公共 API 面（全部 additive）

| 模块 | 新增 |
|---|---|
| aura-core | `io.aura.manifest.ApplicationManifest`：`generate(AuraRuntime)`、`toJson(Map)`、`reconcile(Map, Map)` |
| aura-mcp | `McpRouter.reconcileCapabilities(CapabilityRegistry)` |
| aura-contract | 无变更 |
| ModuleContext | 无变更 |
| 配置 | `aura.manifest`（classpath 资源路径，opt-in） |

错误码：`AURA_MANIFEST_DRIFT`、`AURA_MCP_PROJECTION_DRIFT`（结构化，携带差异）。

## 7. 非功能需求

- **确定性**：同一定义集合 → 字节一致的 Manifest；排序与字段序固定。
- **Fail-closed**：配置了 manifest 就必须可读且一致；漂移中止 prepare/start 并回滚。
- **Opt-in 零影响**：未配置时既有应用行为不变。
- **幂等**：检查可重复执行，无副作用。
- **声明纯净**：Manifest 与 `registry.schema()` 均不含运行状态（细节⑤）。
- **依赖约束**：Gson 仅进入 aura-core 运行期依赖；不引入第二个 JSON 库到 core；
  实现时核对 core 的 ArchUnit 依赖规则是否允许。

## 8. 验收标准

补齐 DoD 4/7/8，且不以单元测试通过为唯一标准：

1. Manifest JSON 含模块、依赖、能力、参数、权限；shape 测试守护。
2. 五类漂移各有**负向测试**并被对应检查捕获：
   - 删一个模块声明 → 检查 1
   - 注册一个未声明能力 → 检查 3
   - 改一个参数 schema → 检查 4
   - 改权限策略 → 检查 4
   - 篡改 MCP 工具事实（名/参数/权限标志）→ 检查 5
3. 无 listener 的 `prepare()` 完成生成与对账（DoD 8）。
4. 未配置 `aura.manifest` 的既有应用零行为变化。
5. `AURA_MANIFEST_UPDATE=true` 再生成字节一致；CI gate 绿。
6. public-api 快照更新，0 breaking；全量 `mvn test -DskipITs` + ArchUnit + 全部 gate 脚本通过。

## 9. 实现注意（动代码前现场核对，不假设）

- `PermissionRegistry.snapshot()` 的具体形状与排序。
- `ModuleRequirement` 的可序列化字段（id / versionRange）。
- `AuraRuntime` 是否暴露属性读取（`app.name` 入 Manifest 的取径）。
- 配置属性命名惯例（对照现有 `app.name` 等）。
- core `ArchitectureTest` 的依赖规则是否容许 Gson。
- **pom 纠缠**：根 pom 有预存未提交的 jacoco 改动；新增 `gson.version` +
  dependencyManagement 会落在同一文件。提交时只暂存本阶段 hunks（或先经确认单独提交
  jacoco 改动），不得混入。
- Gson 解析数字为 Double：FR-3 的数值归一化必须有专门测试。
- `prepareModules()` 收尾无现成钩子：检查插入点在安装成功之后、`completeRequestedStop()`
  之前；start() 路径同样覆盖（含已安装仅 start 的分支）。

## 10. 提交切分

1. **机制**：FR-1～FR-5 + 测试 + API 快照（core + mcp + 根/core pom 的 Gson 依赖）。
2. **gate 与文档**：FR-6～FR-8（harness、committed manifest、脚本、ci.yml、docs）。

两个独立提交；不与已提交的 Capability API 混改；预存 pom jacoco 改动保持分离。

## 11. 风险与权衡

- **Gson 成为纯 core 使用者的传递依赖**：已拍板接受；core 此前仅依赖 contract + slf4j-api。
  缓解：Gson 仅在配置了 `aura.manifest` 或显式调用聚合时被触碰。
- **数值归一化遗漏**会造成假漂移（Integer vs Double）：FR-3 专项测试守护。
- **committed manifest 与参考组合漂移**：CI gate 以 surefire 报告证明真实执行，
  防止"文件在、测试没跑"。
- MCP 对账不在运行期生命周期内：投影漂移由构建期 gate 捕获；运行期执行安全仍由既有
  fail-closed 授权链保证。若未来要求运行期 MCP 对账，再议 post-install seam（本期不做）。
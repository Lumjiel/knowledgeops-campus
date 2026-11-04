# 架构分析报告：TenantCostService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/service/TenantCostService.java`（175 行）
- **核查范围**：该类的全部出边（2 个 mapper、1 个 properties、1 个 security 工具逐个下钻；`TenantUsageDailyMapper` 全文含 upsert 语句与 4 条聚合 SQL）；全部入边（grep 证实 8 个生产调用类 11 处调用点 + 2 个测试 mock）；costTier 词汇契约与 `ModelRouter`/yml 的交叉核实（`application.yml:137-147` 三档 cost-tier 全文）；`GlobalExceptionHandler:15-18` 的异常映射；`V8__session_feedback_cost_platform.sql:41-62` 两张表结构比对
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-TenantCostService.java.md`（同文件缺陷审查——ensureBudget 竞态、硬限击穿、CJK 低估等以该报告为准，本报告不重复展开）、`arch-ModelRouter.java.md`（同一批调用方、costTier 供给方，4.1 与其 4.1/5.2 互为印证）、`arch-ReactDecisionParser.java.md`（词汇复制问题的同类形态）

## 1. 模块定位

该类是**成本治理核心**（service 包，`@Service`），五个公开方法覆盖治理生命周期：`estimateTokens`（纯函数估算，:122-129）→ `assertBudget`（LLM 调用前硬限断言，:28-42）→ `recordUsage`（调用后入账，:44-64）→ `summary`（月/日汇总查询，:66-100）→ `updateBudget`（管理员改预算，:102-120）。

它在调用链中的位置是"被动治理组件"：本类不发起任何编排，`estimate→assert→调用模型→record` 四步全部由 11 处调用方手工串联（arch-ModelRouter 4.1/5.2 已从调用方视角论证）。类内两种访问形态并存：**热路径**（assert/record，每个 LLM 请求 2 次）与**管理面**（summary/updateBudget，低频管理员操作），加上一个纯函数 `estimateTokens`——三种内聚度装在同一个 DB 绑定的 Service 里（见 4.3）。

## 2. 依赖全景

### 2.1 出边（注入的 3 个协作者 + 1 个静态工具）

| 依赖 | 所在包 | 该协作者自身的依赖（已下钻核实） | 方向评价 |
| --- | --- | --- | --- |
| `TenantBudgetMapper` | mapper | MyBatis-Plus `BaseMapper` + 1 条 `@Select`，仅依赖 `domain.TenantBudget`（`TenantBudgetMapper.java` 全文 18 行） | ✅ 叶子 |
| `TenantUsageDailyMapper` | mapper | MyBatis-Plus `BaseMapper` + 幂等 upsert `addUsage` + 4 条 `SUM` 聚合，仅依赖 `domain.TenantUsageDaily` | ✅ 叶子 |
| `CostGovernanceProperties` | config.properties | 零 iqk 依赖（纯 `@ConfigurationProperties` + Lombok，三档单价在构造器内置，经 `App.java:10` 注册）；yml 覆盖 `application.yml:185-193` | ✅ 叶子 |
| `TenantContext` | security | 静态工具，仅 MDC + StringUtils，零 iqk 依赖 | ✅ 横切复用（见 2.3） |

进出的数据类型 `TenantBudget`/`TenantCostSummaryVO`/`TenantBudgetUpdateVO` 均为 domain/vo 纯数据叶子。**对 service/rag/agent/controller 特性层零依赖**——出边全部向下且到叶子即止。

### 2.2 入边（8 个生产调用类，11 处调用点，5 个包层）

| 调用方（包层） | 调用点 | 用法 |
| --- | --- | --- |
| controller：`ChatController` | :120-133 | estimate→assert→（流式）record |
| controller：`CustomerServiceController` | :44-54 | 同上（同步） |
| controller：`CostGovernanceController` | :22/:30 | summary/updateBudget（管理面，唯一消费方） |
| rag：`RagAnswerService` | :127-138 | estimate→assert→record |
| rag：`HybridRagAnswerService` | :82-95 | 同上 |
| service：`ReactAgentService` | :413-447 | 同上（含流式 :446） |
| agent.workflow：`WorkflowReactAgentService` | :313-339 | 同上（含流式 :338） |
| agent.research：`ResearchPlannerAgent` | :34-46 | 同上 |
| agent.research：`ReportWriterAgent` | :27-39 | 同上 |

- 方向全部**指向本类**：与 `ModelRouter` 共享几乎同一批调用方（除 `CostGovernanceController` 独有），构成"路由+成本"这对治理组件的固定消费面。
- `agent.workflow`/`agent.research`（特性包）import `service.TenantCostService`——方向合法（特性→共享服务），但注意 service 包当前身兼两职：特性家（`ReactAgentService`）与共享治理设施家（本类）。包粒度观察见 4.4。
- 测试：`ReactAgentServiceTest`（:27/:58 两处 mock）。

### 2.3 横切依赖

- **`TenantContext.normalize` 的正确复用样板**：本类三处入口（:32/:52/:67）统一经 `TenantContext.normalize` 归一租户——与 `ModelRouter` 私养一份 `normalizeTenant` 副本（arch-ModelRouter 5.3）形成对照，这是本库"横切归一应复用 security 工具"的正面样板。
- 除此之外无横切：不读 MDC 上下文做隐式取值（租户一律显式传参）、不注册 Meter、不发事件。

## 3. 循环依赖评估

### 3.1 结论：类级与包级均无环；与 ModelRouter 同为依赖图纯汇点

```
ChatController/CustomerServiceController/CostGovernanceController ─┐
RagAnswerService/HybridRagAnswerService ───────────────────────────┤
ReactAgentService ─────────────────────────────────────────────────┼─→ TenantCostService ─→ TenantBudgetMapper ─→ TenantBudget（叶子）
WorkflowReactAgentService ─────────────────────────────────────────┤                  ├─→ TenantUsageDailyMapper ─→ TenantUsageDaily（叶子）
ResearchPlannerAgent/ReportWriterAgent ────────────────────────────┘                  ├─→ CostGovernanceProperties（叶子）
                                                                                      └─→ TenantContext（security 静态工具）
```

- **类级**：入边（9 个调用类 + 测试）与出边（3 个叶子 + 静态工具）无任何交汇，环结构上不可能。构造器注入 + 零回指，无 Bean 循环。
- **包级**：本类为 service 包贡献的对外依赖全是向下边（mapper/config/domain/security）。`agent.workflow → service` 这条边不参与 harness⇄workflow 包级环（环的构成见 arch-WorkflowReactAgentService 3.1）——TenantCostService 在环之外，消环方案与本类无关。
- **值得注意的结构对称性**：本类与 `ModelRouter` 的入边几乎重合（8 类 11 处 vs 8 类 8 处），两者共同构成每个 LLM 调用点的前置治理对。这既是收敛机会（arch-ModelRouter 6 的第 1 项建议会把两者封进同一组件），也意味着任何对本类调用契约的破坏会同时波及全部 LLM 链路——**变更敏感度最高的一档**。

## 4. 越层调用与边界问题

### 4.1 costTier 词汇跨组件契约，且已经存在一处真实的词汇漂移——中（本报告最重要发现）

`calculateCost`（:153-162）的 `costTier` 入参取值由调用方决定，实际供给方是 `ModelRouter.ModelRouteDecision.costTier()`。两个组件的词汇表**靠各自配置对齐、无共享类型或常量**，交叉核实发现漂移已经存在：

| 词汇来源 | 取值 |
| --- | --- |
| 价格表 `CostGovernanceProperties.usdPer1kTokens`（构造器 + yml:190-193） | `low` / `medium` / `high` |
| `ModelRouter` yml 三档 profile 的 `cost-tier`（yml:139/144/148） | `low` / `medium` / `high` ✅ 对齐 |
| `ModelRouter.safeCostTier` 兜底值与两条 fallback 路径的硬编码（`ModelRouter.java:131`、`:138`、`:179`） | **`balanced`**——价格表中**不存在**的 tier |

即：任何走到 `router_disabled_default`/`default_model_fallback` 兜底、或 profile 未配 `cost-tier` 的决策，costTier 都是 `balanced`，在 `:155-157` 经两级 `getOrDefault` 静默落到 medium 单价。当前数值上恰好无害（medium=0.0030 与硬编码兜底一致），但契约层面这是**两个配置面 + 一处硬编码三方夹缝里的静默降级**，且与 profile 命名空间（economy/balanced/quality）发生名字碰撞——`balanced` 既是 profile 名又是"伪 tier"。治理与路由是同一数据流的上下游，却无编译期纽带。**修复方向**（不改 `assertBudget`/`recordUsage` 签名）：提取共享 tier 常量类（或让 `ModelRouteDecision.costTier` 的取值域与价格表键单源派生）；`ModelRouter` 的 costTier 兜底值改 `medium`；`:157` 硬编码兜底改为引用 properties 默认常量（与 audit L-1 的消双源建议同向）。

### 4.2 治理断言以 `IllegalArgumentException` 表达，治理信号与参数错误共用 400 通道——低（契约面）

`assertBudget` 超限抛 `IllegalArgumentException`（:40），经 `GlobalExceptionHandler:15-18` 统一映射 400。从边界看：预算拒绝是**治理策略信号**而非请求参数错误，调用方（跨 5 个包层 11 处）与客户端都无法编程区分——这正是"治理内嵌在业务链路、无独立边界"的症状之一。修复（audit L-2 的子类化方案）不动本类签名。

### 4.3 热路径治理与管理面查询同居一类，且治理被动的结构性代价——低（内聚度）

三类职责同居：① 每个 LLM 请求都走的热路径（assert/record，2 次 DB 往返，audit L-4）；② 低频管理面（summary 7 次聚合/updateBudget）；③ 纯函数 `estimateTokens`（只读 properties，无 DB）。两个具体后果：
- **被动性使入账完整性依赖调用方纪律**：`recordUsage` 不在调用方的 finally 里就逃逸（audit M-1），本类作为被动组件无任何强制手段——治理边界物理上位于每个调用点的手工编排里，这是 M-1 类问题反复出现的结构根源。若按 arch-ModelRouter 6 第 1 项收敛出 routed-execution 组件，治理对自然获得唯一执行点，本类的被动性代价即被吸收。
- `estimateTokens` 是无状态纯函数，却要求 5 个包层的调用方为它注入一个 DAO 绑定的 Service；它本可下沉为与 mapper 无关的静态工具/独立组件。记录性观察，现规模不值得单独动作。

### 4.4 ensureBudget 与 addUsage 同类两制：幂等策略不统一——低（结构对比，记录性）

同一类内两条写路径的幂等设计截然不同：`TenantUsageDailyMapper.addUsage` 用 `ON DUPLICATE KEY UPDATE` 原子幂等（mapper:17-44），而 `ensureBudget`（:131-146）用先查后插——后者正是 audit H-1 竞态（并发首请求 DuplicateKeyException→500）的载体。对比之下可见本库已有正确的幂等样板（addUsage），ensureBudget 属于未对齐既有样板。修复（catch DuplicateKeyException 重读，或 mapper 侧幂等 insert）不改签名，且宜向 addUsage 的单语句幂等看齐。

### 4.5 `endpointTag` 死参数：对外 API 携带无效数据——低（API 契约，既有报告未覆盖的新发现）

`recordUsage` 的第 5 参 `endpointTag`（:47-48）在方法体内**零使用**：`tenantUsageDailyMapper.addUsage(tenant, date, 1, safeInput, safeOutput, cost)`（:56-63）不含该字段，`tenant_usage_daily` 表也无 endpoint 列（`V8:51-62` 逐列核实；对照 `model_ab_exposure` 表倒是有 `endpoint VARCHAR(32)`，V8:72）。而 11 处调用方全部认真传值（"chat"/"service"/"rag"/"research_planner"/"research_writer"/动态 endpointTag）——**每个调用点都在为一个被丢弃的维度付费**，按 endpoint 的成本归因分析因此不可能做（数据从未落库）。这不是缺陷报告里的注入/竞态类问题，是 API 契约与存储能力的静默脱节。**修复方向**：要么加列落库（迁移 + mapper 扩参，`recordUsage` 签名不变），要么下一轮清理时从签名移除该参数（收缩 API，现调用方需同步删实参——二选一，当前两头都不占是最差状态）。

### 4.6 无越层调用

本类不触及 ChatClient、不依赖特性 service、不读传输层状态；summary/updateBudget 的租户归属防线由 controller 侧 `TenantContext.currentTenantId()` 保证（audit L-5 记录了服务方法自身的信任假设）。除 4.1 的词汇契约外，出边面干净。

## 5. 同层平行实现与隐性耦合

### 5.1 治理四步曲在 11 处调用点手工编排——中（与 arch-ModelRouter 4.1/5.2 同根，从本类视角收口）

`estimate→assert→（调用）→record` 的编排无一处封装：`ChatController.trackedChatStream:120-133`、`CustomerServiceController:44-54`、`RagAnswerService:127-138`、`HybridRagAnswerService:82-95`、`ReactAgentService:413-447`、`WorkflowReactAgentService:313-339`、research 两 agent 各一份。漂移已经可见：输出 token 上限占位（600/2000）各处手抄、流式路径记账时机两种（doFinally vs 回调内）、断言上限与端点标签各自维护。本类作为被动组件无法消除这些副本——收口只能发生在调用侧（统一 routed-execution 组件，见 4.3）。

### 5.2 tier 词汇的三方分散——见 4.1

价格表（properties+建库时构造器）、路由侧 costTier（ModelRouter yml + 代码兜底）、调用方透传（11 处）——同一词汇三处分散，与 `ReactDecisionParser` 的动作白名单四份复制（arch-ReactDecisionParser 4.1）是同一类问题的两种形态：**该库的跨组件契约普遍以字符串复制维持**。

### 5.3 单价双源——低

`CostGovernanceProperties` 构造器内置三档单价（`new BigDecimal("0.0010"/"0.0030"/"0.0100")`），`calculateCost:157` 又硬编码一份 `0.0030` 兜底；yml:190-193 再覆盖一层。三层数值当前一致，但独立维护（audit L-1 已建议消双源）。记录。

## 6. 结论与建议优先级

**总评**：依赖方向健康——四个向下叶子出边、9 个调用类单向入边、零环参与，且是本库正确复用 `TenantContext` 的正面样板；它与 `ModelRouter` 共同构成全库变更敏感度最高的治理对（入边几乎重合）。问题集中在**契约以字符串/手工编排维持**：costTier 词汇已经出现 `balanced` 这个静默落 medium 价的真实漂移（4.1），治理四步曲在 11 处手抄使入账完整性依赖调用方纪律（5.1），外加一个既有报告未覆盖的 `endpointTag` 死参数（4.5）。

| 优先级 | 事项 | 章节 | 改动范围 |
| --- | --- | --- | --- |
| 1 | costTier 词汇单源化：共享常量/类型 + `ModelRouter` 兜底值 `balanced`→`medium` + 消 `:157` 硬编码双源 | 4.1/5.3 | ModelRouter + 本类 + 新常量；对外签名不变 |
| 2 | 治理四步曲随 routed-execution 组件收敛（联动 arch-ModelRouter 6 第 1 项），顺带解决 recordUsage 逃逸 | 4.3/5.1 | 新组件 + 调用方收缩 |
| 3 | `endpointTag` 二选一：加列落库（签名不变）或从签名移除（调用方同步删实参） | 4.5 | 本类 + mapper/迁移 或 11 处调用点 |
| 4 | ensureBudget 向 addUsage 的单语句幂等样板看齐（audit H-1 同方案） | 4.4 | mapper 一条语句或本类一处 catch |
| 5 | 预算拒绝独立异常类型（402/429 通道） | 4.2 | 新子类 + handler，零调用方改动 |

以上各项均不改变 `assertBudget`/`recordUsage`/`summary`/`updateBudget`/`estimateTokens` 的对外 API 签名；第 1 项需同步核对 yml 中 `cost-tier` 取值与共享常量的一致性，第 3 项的"移除参数"方案会收缩公开 API（现 11 处调用方全部需改），建议优先取"加列落库"方案。

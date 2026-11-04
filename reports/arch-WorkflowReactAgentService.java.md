# 架构分析报告：WorkflowReactAgentService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/agent/workflow/WorkflowReactAgentService.java`（500 行）
- **核查范围**：该类的全部入边（调用方）与出边（注入依赖），逐层下钻到各协作者的依赖清单；包结构全景（`com.enterprise.iqk` 下 20 个包）
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、未变更任何对外 API 签名
- **关联报告**：`audit-WorkflowReactAgentService.java.md`（缺陷审查）、`audit-ReactAgentService.java.md`、`audit-AgentWorkflowEngine.java.md`

## 1. 模块定位

该类是 ReAct 工作流编排器（agent.workflow 包），对外暴露两个入口：`chat`（:51，同步多步规划-执行循环）与 `stream`（:120，SSE 流式变体）。唯一调用方是 `WorkflowController`（`/ai/workflow/react/chat[/stream]`，`WorkflowController.java:38-46`）；读侧（任务/事件查询）由同一 controller 直接打 `AgentWorkflowEngine`（:49-75）。此外 `CustomerServiceController.java:40` 的注释把它当作成本记账模式的参照物——它实际承担了"事实上的模式范本"角色（见 5.3）。

它不属于引擎：`AgentWorkflowEngine`（同包）才是持久化/状态机/事件层；本类是**驱动引擎的用例编排层**。

## 2. 依赖全景

### 2.1 出边（注入的 7 个协作者，:43-49）

| 依赖 | 所在包 | 该协作者自身的依赖（已下钻核实） | 方向评价 |
| --- | --- | --- | --- |
| `AgentWorkflowEngine` | agent.workflow（同包） | 3 个 mapper（AgentTask/Step/Event）、ObjectMapper、MeterRegistry | ✅ 干净的下游，叶子化良好 |
| `AgentHarnessService` | agent.harness | `List<AgentRuntime>`、ActionPolicyGuard（→config.properties）、**HarnessEventRecorder（→AgentWorkflowEngine）**、HarnessPayloadSanitizer | ⚠️ 见 3.1 包级环 |
| `ChatClient` | config（Bean） | OpenAiChatModel + ChatMemory（MysqlChatMemory→repository） | ⚠️ 见 5.1 绑定方式 |
| `ModelRouter` | llm | properties + mapper | ✅ 叶子 |
| `TenantCostService` | service | 2 个 mapper + properties + security | ✅ 叶子，但落包存疑（见 4.2） |
| `ObjectMapper` / `MeterRegistry` | 基础设施 | — | ✅ |

经 AgentHarnessService 二跳可达的更深层：`BuiltinToolRuntime` → `rag.RagAnswerService`、`tools.CourseTools`（harness 向下触及特性服务，见 4.3）；`McpToolRuntime`、`WorkspaceRuntime` 无 agent 包外依赖（除 properties）。

### 2.2 入边

- `WorkflowController.java:33` —— 唯一生产调用方（chat/stream 两个端点）。
- 测试与 `CustomerServiceController.java:40` 的注释引用（非调用）。
- 没有任何其他包反向依赖本类；`AgentWorkflowEngine` 与 `AgentHarnessService` 均不引用它。

### 2.3 横切依赖

- `TenantContext`（security）+ 直读 `MDC`（:490）；
- 三个 `domain.vo`（ReactChatRequestVO/ReactChatResponseVO/ReactTraceStepVO）——与 `ReactAgentService` 共享同一组 VO，这是两条 ReAct 链路仅有的共享契约。

## 3. 循环依赖评估

### 3.1 结论：Spring Bean 级无环（应用可正常启动）；包级存在一个环 workflow ⇄ harness

**Bean 级证据链**（逐个下钻注入字段后闭环检查）：

```
WorkflowReactAgentService ─→ AgentHarnessService ─→ HarnessEventRecorder ─→ AgentWorkflowEngine ─→ mapper×3/ObjectMapper/MeterRegistry
WorkflowReactAgentService ─→ AgentWorkflowEngine（直达，同一实例）
```

`AgentWorkflowEngine.java:27-31` 的依赖只有 mapper 与基础设施，不回指任何 agent 类——链到此终止。构造器注入下整图无环，Spring 能完成初始化。**该环不会被 Spring 启动暴露**，这正是它长期潜伏的原因。

**包级环的实体**：

- 出边：`agent/workflow/WorkflowReactAgentService.java:3-4` import `agent.harness.AgentAction/AgentHarnessService`；
- 回边：`agent/harness/HarnessEventRecorder.java:3` import `agent.workflow.AgentWorkflowEngine`，其 `emit()`（:34-36）直接调 `workflowEngine.emitEvent(...)`。

### 3.2 后果

1. **harness 不再是独立内核**：名为 harness（工具执行底座）的包反向依赖了 workflow（编排/状态机/持久化）包，抽象层级倒置——底座知道顶层的存在。
2. **两个包只能整体构建、整体测试**：harness 的单元测试必须连带 workflow 的引擎与 mapper 链；想抽离 harness 复用或独立演进被这个回边锁死。
3. **演化风险**：一旦未来有人让 `AgentWorkflowEngine` 注入 harness 侧组件（比如策略校验），Bean 级环即刻成形，Spring 启动失败，且修复需要动多处。

### 3.3 修复选项（均不改任何对外 API 签名）

按推荐顺序：

1. **端口下沉（推荐）**：在 `agent.harness` 定义单向接口 `HarnessEventSink`（`started/completed` 两个方法，签名照抄 HarnessEventRecorder 的公开方法），`HarnessEventRecorder` 改为依赖该接口；在 `agent.workflow` 放一个实现类委托 `AgentWorkflowEngine.emitEvent`。依赖方向变为 workflow → harness 单向。改动范围：新增 1 接口 + 1 实现，HarnessEventRecorder 换字段类型，不动任何现有公开方法签名。
2. **移动类**：把 `HarnessEventRecorder` 迁入 `agent.workflow`（它本质是 workflow 的事件记录策略，harness 只在 `AgentHarnessService.execute` 前后回调它）。需同步迁移其测试，公开签名不变。
3. **抽包**：`AgentWorkflowEngine` 的事件写入部分（emitEvent 及 AgentEventMapper/Record）下沉到 `agent.event` 包，两侧都依赖它——改动最大，收益主要是长期整洁，当前规模下不划算。

## 4. 越层调用与边界问题

### 4.1 服务层直读 MDC（传输上下文渗入 agent 模块）——低

`WorkflowReactAgentService.java:490` 的 `currentTenantId()` 私有方法体是 `TenantContext.normalize(MDC.get(...))`——与 `TenantContext.currentTenantId()`（`TenantContext.java:18-20`）逐字相同，属重复实现；同时它在 agent 层直接依赖 MDC（由 `RequestContextFilter` 填充的传输层状态）。同库另有 controller 显式传 tenantId 的惯例（`EvaluationController.java:30`、`WorkflowController.java:51` 读侧）——两种约定并存。**修复**：删私有方法改调 `TenantContext.currentTenantId()`；长期看编排服务应接收 tenantId 参数，但那会改内部调用形态，可作为后续项。

### 4.2 TenantCostService 落在 service 大杂烩包——低（结构观感）

`agent.workflow` → `service.TenantCostService`（:10,:47）：`service` 包是本库的兜底层（memory 侧已多次审查其中互不相关的类）。成本记账语义上更贴近 `llm`（ModelRouter 已在 llm 包，两者在 callModel 里成对出现 :313-320）。移动包属纯重构，不改签名；不移动也不构成正确性问题。

### 4.3 harness runtime 下探特性服务——可接受，注明代价

`BuiltinToolRuntime` → `rag.RagAnswerService`、`tools.CourseTools`：方向仍是"agent → 特性能力"的向下依赖，不违层；代价是 harness 无法脱离 rag/tools 独立测试与复用。若未来要拆分 agent 为独立模块，需为这两个工具引入接口。当前规模下不建议动作，仅记录。

### 4.4 WorkflowController 直注引擎读侧——可接受

`WorkflowController.java:34` 同时持有编排服务与引擎，读端点（tasks/events）绕过服务直达引擎。引擎方法是租户参数化的（`AgentWorkflowEngine.java:189-209`，mapper 按 tenant 过滤），安全性成立。仅是"controller 知道两个模块"的轻度耦合，读侧无状态机变更，风险低。

## 5. 同层平行实现与隐性耦合

### 5.1 三个 ChatClient Bean 无 @Primary，绑定靠字段名巧合——中

`CommonConfiguration.java:20-46` 定义 `chatClient`/`serviceChatClient`/`pdfChatClient` 三个 Bean，全库无 `@Primary`/`@Qualifier`（grep 证实）。`WorkflowReactAgentService.java:45` 的字段名 `chatClient` 恰好等于默认 Bean 名，按"类型多候选 → 字段名消歧"的 Spring 兜底规则**巧合地**绑定了带 ChatMemory advisor 的默认客户端（`CommonConfiguration.java:21-26`）。这意味着：①重命名字段这种"安全重构"会直接引发 `NoUniqueBeanDefinitionException`；②将来有人补一个 `@Primary` 会静默改变本类与 `ReactAgentService` 的绑定对象。**修复**：注入点显式加 `@Qualifier("chatClient")`（不改变运行时行为与任何签名，只把隐式变显式）。

### 5.2 指标名冲突：两个 ReAct 流式端点共用同名 Meter——中

`WorkflowReactAgentService.java:464-473` 注册 `react.stream.total.latency` / `react.stream.first_token.latency` / `react.stream.requests`（tag 仅 outcome），与 `ReactAgentService.java:243-259` **完全同名同 tag 集**。MeterRegistry 对同名同 tag 的 Meter 去重合并，`/ai/react/chat/stream` 与 `/ai/workflow/react/chat/stream` 两条不同代码路径的延迟与请求数混入同一序列——监控归因失效，且第一条路径的量会淹没第二条。**修复**：本类指标改前缀 `workflow.react.*` 或追加 `agent=workflow` tag（Micrometer 允许同 tag 名不同值并存）。不改任何 API 签名。

### 5.3 ReAct 内核多重实现：抽取件已存在，本类却是私有副本——中（本报告最重要的结构性发现）

同库现存**三套** ReAct 决策实现：

1. `service/ReactAgentService`（服务 `/ai/react/*`）——已改用抽取件 `ReactDecisionParser` + `ReactResponseFormatter`；
2. `service/ReactDecisionParser` / `service/ReactResponseFormatter`——抽取出来的共享组件；
3. 本类 `WorkflowReactAgentService`（服务 `/ai/workflow/react/*`）——自带全套私有副本且**不使用**抽取件：`parseDecision`（:355-379）、`fallbackDecision`（:381-409）、`extractJson`（:411-416）、`formatSse`（:446-448，与 `ReactResponseFormatter.java:47-49` 逐字相同）、`toJson`（:450-453）、`attachCitationFooter`（:434-444）、`extractTraceStrings`（:418-432）、`callModel/callModelStream` 成本记账（:311-341）。

这已是本项目的既有模式（`audit-ReactDecisionParser.java.md` 记录过三重实现漂移），本类是漂移的存量现场。`CustomerServiceController.java:40` 的注释"same pattern as WorkflowReactAgentService.callModel"进一步证明记账模式正以复制粘贴方式扩散（至少第三处）。两条 ReAct 端点共享同一组 VO（入边 2.3）却各养一套决策/SSE/记账内核，行为随时间分叉已是事实风险。**修复方向**（均不改对外签名）：本类改为复用 `ReactDecisionParser`/`ReactResponseFormatter`（先 diff 私有副本与抽取件的行为差异，漂移处逐项裁决）；成本记账下沉为 ChatClient 装饰器或 `TenantCostService.recordChatCall(...)` 单一入口，三处调用点收敛。

### 5.4 两条 ReAct 端点并存的边界模糊——低（记录性）

`/ai/react/chat/stream`（ReactController→ReactAgentService）与 `/ai/workflow/react/chat/stream`（WorkflowController→本类）是语义重叠的平行入口：前者无 workflow 记录，后者有完整任务/步骤/事件留痕。属产品边界问题而非缺陷，但 5.3 的内核分叉使两者回答行为可能悄悄不一致，建议在文档或路由层明确谁是长期主线。

## 6. 结论与建议优先级

**总评**：Bean 依赖图干净无环，引擎（AgentWorkflowEngine）作为下游叶子设计良好；主要结构性问题集中在"包级回边 + 平行实现不收敛 + 隐式 Bean 绑定"三点，全部可在不动对外 API 签名的前提下修复。

| 优先级 | 事项 | 章节 | 改动范围 |
| --- | --- | --- | --- |
| 1 | 消除包级环：HarnessEventSink 端口下沉 | 3.1/3.3-① | agent.harness + agent.workflow 各一文件 |
| 2 | 指标名去冲突（`workflow.react.*` 或 agent tag） | 5.2 | 仅本类 |
| 3 | `@Qualifier("chatClient")` 显式化 | 5.1 | 仅本类 |
| 4 | 收敛 ReAct 内核副本（复用 ReactDecisionParser/ReactResponseFormatter，记账下沉） | 5.3 | 本类 + 少量共享类 |
| 5 | currentTenantId 去重、TenantCostService 归包、其余记录项 | 4.1/4.2 | 随手或搁置 |

以上 1-3、5 项均不改变任何对外 API 签名；第 4 项若发现私有副本与抽取件存在行为差异，需先裁决语义（裁决结论应回流到 `audit-ReactDecisionParser.java.md` 记录的漂移清单），同样可以保持对外签名不变。

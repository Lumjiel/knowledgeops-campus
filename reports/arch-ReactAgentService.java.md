# 架构分析报告：ReactAgentService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/service/ReactAgentService.java`（470 行）
- **核查范围**：该类的全部出边（注入的 7 个协作者，逐个下钻其依赖清单）与全部入边（grep 全库核实调用方）；agent 包对 service 包的反向引用全量 grep；`CommonConfiguration` 的 ChatClient Bean 全景；两处流式指标的 Meter 注册名比对
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-ReactAgentService.java.md`（缺陷审查）、`arch-WorkflowReactAgentService.java.md`（姊妹编排器的同型分析，本报告多处引用其结论）、`audit-ReactDecisionParser.java.md`、`audit-TenantCostService.java.md`

## 1. 模块定位

该类是 ReAct 编排器（`service` 包），对外暴露两个入口：`chat`（:49，同步多步规划-执行循环）与 `stream`（:114，SSE 流式变体），步数上限 `MAX_STEPS = 4`（:39）。唯一生产调用方是 `ReactController`（`/ai/react/chat[/stream]`，`ReactController.java:20-38`）。

它是同库**三套 ReAct 实现中唯一完成收敛的参照实现**：已改用抽取件 `ReactDecisionParser`（:46）与 `ReactResponseFormatter`（:47），grep 证实这两个抽取件在全库的唯一消费者就是本类——姊妹实现 `WorkflowReactAgentService`（agent.workflow 包，服务 `/ai/workflow/react/*`）仍养全套私有副本（见 5.3）。因此本类的边界质量是两条 ReAct 链路中较好的一条，但它的包级位置与流式实现仍有结构性问题（见 3.1、4.1、5.4）。

## 2. 依赖全景

### 2.1 出边（注入的 7 个协作者，:41-47）

| 依赖 | 所在包 | 该协作者自身的依赖（已下钻核实） | 方向评价 |
| --- | --- | --- | --- |
| `AgentHarnessService` | agent.harness | `List<AgentRuntime>`、ActionPolicyGuard（→config.properties）、**HarnessEventRecorder（→agent.workflow.AgentWorkflowEngine）**、HarnessPayloadSanitizer | ⚠️ 使本类成为包级三角环的一条实体边（见 3.1） |
| `ChatClient` | config（Bean） | OpenAiChatModel + ChatMemory（MysqlChatMemory→repository） | ⚠️ 见 5.1 绑定方式 |
| `ModelRouter` | llm | config.properties、domain.ModelAbExposure、mapper.ModelAbExposureMapper | ✅ 叶子 |
| `TenantCostService` | service（同包） | 2 个 mapper（TenantBudget/TenantUsageDaily）+ properties + security | ✅ 叶子，落包见 4.4 |
| `MeterRegistry` | 基础设施 | — | ✅ |
| `ReactDecisionParser` | service（同包） | **零 iqk 依赖**（grep 其 import 证实），纯解析件 | ✅ 收敛良好的抽取件 |
| `ReactResponseFormatter` | service（同包） | domain.vo 两个 + `llm.ModelRouter`（仅类型引用：:24 把 `ModelRouteDecision` 用作参数类型，不注入 Bean） | ✅ |

经 `AgentHarnessService` 二跳可达的更深层：`BuiltinToolRuntime` → `rag.RagAnswerService`、`tools.CourseTools`（harness 向下触及特性服务，方向仍为向下，代价同 `arch-WorkflowReactAgentService.java.md` 4.3 所记：harness 无法脱离 rag/tools 独立测试）。

### 2.2 入边

- `ReactController.java:20` —— 唯一生产调用方（chat/stream 两个端点）。
- 无任何其他包引用本类（grep 全库证实；`WorkflowReactAgentService.java:39,278,353` 与 `CustomerServiceController.java:40` 仅在**注释**里提及本类，非调用）。
- 本类不引用任何 agent.workflow 类，类级入边干净。

### 2.3 横切依赖

- `TenantContext`（security）+ 直读 `MDC`（:455-457，私有 `currentTenantId()` 与 `TenantContext.currentTenantId()`（`TenantContext.java:21-23`）逐字重复）；
- 三个 `domain.vo`（ReactChatRequestVO/ReactChatResponseVO/ReactTraceStepVO）——与 `WorkflowReactAgentService` 共享同一组 VO，这是两条 ReAct 链路仅有的共享契约；
- `static import ChatMemory.CONVERSATION_ID`（:33）与 `ReactDecisionParser.ReasonDecision` 嵌套 record 的跨类引用（:31）——后者意味着决策解析的返回形态与本类强耦合，`ReasonDecision` 若增删字段，本类 `chat`/`stream` 两个循环同时受影响。

## 3. 循环依赖评估

### 3.1 结论：Spring Bean 级无环（应用可正常启动）；包级存在三角环 service → harness → workflow → service，本类是其一条实体边

**Bean 级证据链**（逐个下钻注入字段后闭环检查）：

```
ReactAgentService ─→ AgentHarnessService ─→ HarnessEventRecorder ─→ AgentWorkflowEngine ─→ mapper×3 / ObjectMapper / MeterRegistry
ReactAgentService ─→ TenantCostService ─→ mapper×2 / properties / security
ReactAgentService ─→ ModelRouter ─→ properties / domain / mapper
```

`AgentWorkflowEngine.java:3-20` 的 iqk 依赖只有 `security.TenantContext`，不回指任何 service/agent Bean——两条链均到此终止；`ReactDecisionParser` 零依赖。构造器注入下整图无环，Spring 能完成初始化，**该结构问题不会被启动暴露**。

**包级三角环的三条实体边**：

1. `service → agent.harness`：`ReactAgentService.java:3-4` import `agent.harness.AgentAction/AgentHarnessService`（grep 证实这是 service 包通向 harness 的唯一一条边）；
2. `agent.harness → agent.workflow`：`HarnessEventRecorder.java:3` import `agent.workflow.AgentWorkflowEngine`；
3. `agent.workflow → service`：`WorkflowReactAgentService.java:10` import `service.TenantCostService`（另有 agent.research 两处同类引用：`ResearchPlannerAgent.java:4`、`ReportWriterAgent.java:4`）。

### 3.2 后果

1. **环的维持依赖本类**：由于边 1 是 service→harness 的唯一出边，`ReactAgentService` 的存在本身参与维持了三角环——即使有人按 `arch-WorkflowReactAgentService.java.md` 3.3 修掉 harness→workflow 回边，若同时有第三处 service 代码引用 harness，环仍可能换形重现；反之，消掉边 2（端口下沉）后包图变为 service→harness、workflow→{harness, service}，环即消解。**本类的边 1 无需改动**。
2. **测试与构建耦合**：service 包的任何测试只要实例化本类，就会连带 harness 全家与 workflow 的引擎/mapper 链。
3. **演化风险**：未来若有人让 `AgentWorkflowEngine` 注入 service 侧组件（例如成本记账），Bean 级环即刻成形，Spring 启动失败。

### 3.3 修复选项（均不改任何对外 API 签名）

1. **端口下沉（推荐，与姊妹报告同一方案）**：在 `agent.harness` 定义单向接口 `HarnessEventSink`，`HarnessEventRecorder` 改依赖该接口，`agent.workflow` 放实现委托 `AgentWorkflowEngine.emitEvent`。本类**零改动**即随消解环。
2. **移动类**：把 `HarnessEventRecorder` 迁入 `agent.workflow`，公开签名不变。
3. **不动**：环在包级而非 Bean 级，短期无正确性风险；但每次在 service 或 agent 包新增跨包引用前应对照本节包图。

## 4. 越层调用与边界问题

### 4.1 stream 路径在 `Flux.defer` 内直读 MDC 取租户——中（本报告最重要的运行时边界发现）

`stream()` 把 `validateRequest` → `currentTenantId()` → `resolveRouteDecision` 全部放进 `Flux.defer`（:120-127），`currentTenantId()` 的实现是 `TenantContext.normalize(MDC.get(...))`（:455-457）。MDC 是**线程本地**状态，由 `RequestContextFilter` 在请求线程填充；而 defer 体在**订阅时**才执行，订阅线程由容器/响应式设施决定，不保证是请求线程。一旦订阅发生在异步线程（MVC 异步派发、Reactor 调度器、event loop），`MDC.get` 返回 null，`TenantContext.normalize(null)` 静默落到默认租户 `"public"`（`TenantContext.java:17-18`）——**预算断言（:432 `assertBudget`）与记账（:447 `recordUsage`）从此挂到错误租户头上，全程无报错**。`chat()` 同步路径无此险。姊妹类 `WorkflowReactAgentService` 的 `stream` 有完全相同的模式（其 :490 同款 `currentTenantId`），属同批结构性问题。

- **触发条件**：容器在非请求线程订阅返回的 Flux。是否实际触发取决于运行容器（MVC 异步桥接 / WebFlux）的订阅线程模型，静态审查无法证实命中频率，故定中危而非高危。
- **修复方向**（不改对外签名）：在方法同步段（请求线程内）先解析 tenantId，经方法参数或 Reactor Context 传入 defer 体；`executeAction`/`callModel*` 已是 tenantId 显式传参（:334-343, :407-423），只差入口一处。

### 4.2 service 层直读传输层 MDC + 重复实现——低

同 4.1 的根因之一：私有 `currentTenantId()`（:455-457）与 `TenantContext.currentTenantId()` 逐字重复，且让 service 层直接依赖 MDC 这一传输层状态。**修复**：删私有方法改调 `TenantContext.currentTenantId()`（零签名变化）；长期应改为显式传参（同 arch-WorkflowReactAgentService 4.1 的结论）。

### 4.3 ReactController 直注 repository 写会话历史——低（记录性）

`ReactController.java:21,27,35` 绕过 service 直接调 `ChatHistoryRepository.save` 落会话索引。写的是路由用会话索引而非业务数据，两端点对称，风险低；但"哪些前缀算一次会话"的策略散落在 controller，若 `WorkflowController` 等处还有第三种写法，语义会漂移。可接受，仅记录。

### 4.4 本类落位 service 兜底包，姊妹编排器却在 agent.workflow——低（结构观感）

本类、两个抽取件、`TenantCostService`、`AgentSessionService` 等互不相关的类同居 `service` 大杂烩包，而功能同源的 `WorkflowReactAgentService` 在 `agent.workflow`。作为 ReAct 参照实现，若未来把 agent 编排归拢进 `agent.*`，本类与两个抽取件是整体迁移的首选单元（纯包移动，不改任何签名）。不构成正确性问题。

### 4.5 harness runtime 下探特性服务——可接受

同 arch-WorkflowReactAgentService 4.3，不赘述；本类经由 `AgentHarnessService` 间接受影响。

## 5. 同层平行实现与隐性耦合

### 5.1 三个 ChatClient Bean 无 @Primary，绑定靠字段名巧合——中

`CommonConfiguration.java:20-46` 定义 `chatClient`/`serviceChatClient`/`pdfChatClient` 三个 Bean，全库 grep 无 `@Primary`/`@Qualifier`。本类 :42 字段名 `chatClient` 恰好等于默认 Bean 名，按"类型多候选 → 字段名消歧"的 Spring 兜底规则**巧合地**绑定了带 ChatMemory advisor 的默认客户端。重命名字段这种"安全重构"会引发 `NoUniqueBeanDefinitionException`；将来补 `@Primary` 会静默改变本类（及姊妹类）的绑定对象。**修复**：注入点显式加 `@Qualifier("chatClient")`（运行时行为不变，只把隐式变显式）。

### 5.2 指标名冲突：两个 ReAct 流式端点共用同名 Meter——中

本类 :243/:251/:259 注册 `react.stream.total.latency` / `react.stream.first_token.latency` / `react.stream.requests`（tag 仅 `outcome`），与 `WorkflowReactAgentService.java:464-472` **完全同名同 tag 集**。MeterRegistry 对同名同 tag 的 Meter 去重合并，`/ai/react/chat/stream` 与 `/ai/workflow/react/chat/stream` 两条不同代码路径的延迟与请求数混入同一序列，监控归因失效。**修复**：为两个端点引入区分维度——任一侧改前缀（如 `agent.react.*`）或双端各加 `endpoint` tag（Micrometer 允许同名不同 tag 值并存），不改任何 API 签名。

### 5.3 ReAct 内核收敛：本类已接抽取件，姊妹类仍是私有副本——本类是收敛目标而非漂移源头（记录性）

同库三套 ReAct 实现的现状（与 `audit-ReactDecisionParser.java.md` 记录的三重漂移对应）：

1. 本类——已复用 `ReactDecisionParser` + `ReactResponseFormatter`（:46-47），**全库唯一消费者**（grep 证实）；
2. `WorkflowReactAgentService`——自带全套私有副本且不接抽取件（`arch-WorkflowReactAgentService.java.md` 5.3 逐项列证）；
3. 成本记账模式第三副本：`CustomerServiceController.java:40-56`（注释自认 "same pattern as WorkflowReactAgentService.callModel"），与本类 :407-423（`callModel`）/ :425-449（`callModelStream`）三处复制同一"先 assertBudget 后 recordUsage"骨架。

**修复方向**（均不改对外签名）：漂移治理的主手术在 workflow 侧（向抽取件收敛）与记账下沉（`TenantCostService.recordChatCall(...)` 单一入口或 ChatClient 装饰器，三处调用点收敛）；本类作为参照实现无需动作，只需在抽取件行为变更时充当语义基准。

### 5.4 chat() 与 stream() 在本类内部构成第二层双实现——低~中

同一条 ReAct 循环在本类内出现两份：`chat` :63-108 与 `stream` :134-167 结构近乎逐字对应，finish 分支的 observation 组装重复两遍（:79-94 vs :138-153），决策 JSON 消毒也是两套机制（同步 :72-76 `extractAnswerFromRaw` 提取；流式 :189-208 `switchOnFirst` 缓冲全量后提取）。已经可见的行为分叉：

- **最终作答失败的语义不同**：`chat()` 走 `summarizeAnswer` 失败后返回兜底文案且 `fallback=true`（:361-367）；`stream()` 的最终作答走 `callModelStream`，失败被 :230-233 的 `onErrorResume` 吞成 `error` 事件，**不产生 fallback 标记**——同一端点族两种降级形态，消费端（如 eval 采集链路，见 `audit-eval_live_runner.py.md` 的 fallback 假绿灯发现）需分别适配。
- 两份循环共享 `MAX_STEPS`/prompt 常量靠复制，后续改动极易只改一边。

**修复方向**（不改对外签名）：抽私有共享件 `buildFinishObservation(...)`、统一最终作答的降级语义；这是纯类内重构，风险最低。

## 6. 结论与建议优先级

**总评**：类级依赖图干净——不碰 mapper、完整复用抽取件、入边唯一，是两条 ReAct 链路中边界最好的一条。结构性问题集中在四处：它作为 service→harness 的唯一出边参与维持包级三角环（自身零改动即可随端口下沉消解）、stream 路径 defer 内直读 MDC 的租户归属风险、隐式 ChatClient 绑定、以及 chat/stream 内部双循环的漂移土壤。

| 优先级 | 事项 | 章节 | 改动范围 |
| --- | --- | --- | --- |
| 1 | stream 租户解析移出 `Flux.defer`（请求线程解析后显式传参） | 4.1/4.2 | 仅本类 |
| 2 | 消包级环：HarnessEventSink 端口下沉（本类零改动） | 3.1/3.3-① | agent.harness + agent.workflow 各一文件 |
| 3 | 指标名去冲突（endpoint tag 或区分前缀） | 5.2 | 本类或姊妹类其一 |
| 4 | `@Qualifier("chatClient")` 显式化 | 5.1 | 仅本类 |
| 5 | 抽共享件收敛 chat/stream 双循环、统一降级语义 | 5.4 | 仅本类内部 |
| 6 | 记账下沉、workflow 侧接抽取件、TenantCostService/本类归包 | 5.3/4.4 | 跨类，随姊妹报告排期 |

以上各项均不改变任何对外 API 签名；第 1 项是唯一涉及运行时行为修正的改动，建议配合 `/ai/react/chat/stream` 的多租户集成测试验证订阅线程行为后再合入。

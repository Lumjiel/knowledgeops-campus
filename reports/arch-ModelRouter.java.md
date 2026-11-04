# 架构分析报告：ModelRouter 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/llm/ModelRouter.java`（234 行）
- **核查范围**：该类的全部出边（3 个协作者逐个下钻其依赖清单）与全部入边（8 个生产调用方 8 处 `resolve` 调用逐一定位并查看消费方式）；`llm` 包全量内容（仅本类一个文件）；`ModelRouterProperties` 全部字段默认值与 `application.yml:125-147` 覆盖值逐项比对；`ModelAbExposureMapper`/`ModelAbExposure` 全文；`ObjectProvider` 全库用法对照；3 个测试类的构造/打桩方式
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-ModelRouter.java.md`（同文件缺陷审查——分桶可操纵、曝光表膨胀、开关门控等发现以该报告为准，本报告不重复展开）、`arch-WorkspaceRuntime.java.md`、`arch-ReactAgentService.java.md`、`arch-WorkflowReactAgentService.java.md`

## 1. 模块定位

该类是全库**所有 LLM 调用的模型选型入口**：`llm` 包的唯一成员，向外提供 `resolve(…)` 两/四参两个公开入口与嵌套 record `ModelRouteDecision`（8 字段，`ModelRouter.java:219-227`）。职责链：profile 归一化 → 手动覆盖/AB 实验分桶（`applyExperiment`，:91-124）→ 禁用/启用两条解析路径（:126-180）→ fallback 链（`visited` 防环）→ 曝光落库（`saveExposure`，:71-89）。

它在调用链中的位置是"策略先行"：每个入口（controller/rag/service/agent）在发起 LLM 调用**之前**先 `resolve` 拿到 `decision.model()` 塞进 `ChatOptions`，同时用 `decision.costTier()` 驱动预算断言。路由决策本身是纯函数（只读 `ModelRouterProperties`），但类里还背着一条持久化出边（曝光 insert），这使它同时是"策略组件"和"DAO 客户端"——本报告 4.2/4.3 的核心议题。

## 2. 依赖全景

### 2.1 出边（注入的 3 个协作者）

| 依赖 | 所在包 | 该协作者自身的依赖（已下钻核实） | 方向评价 |
| --- | --- | --- | --- |
| `ModelRouterProperties` | config.properties | 零 iqk 依赖（纯 `@ConfigurationProperties` + Lombok `@Data`，经 `App.java:10` `@ConfigurationPropertiesScan` 注册）；yml 覆盖 `application.yml:125-147` | ✅ 叶子 |
| `ModelAbExposureMapper` | mapper | 仅 MyBatis-Plus `BaseMapper` + `domain.ModelAbExposure`，一行接口（`ModelAbExposureMapper.java:8`） | ✅ 向下（见 4.2） |
| `ModelAbExposure` | domain | 仅 MyBatis-Plus 注解 + Lombok，纯数据类 | ✅ 叶子 |

注入方式注意点：mapper 经 `ObjectProvider` + `initMapper` try/catch 静默降级为 null（:24-36），全库唯一一处"吞异常转 null"的 ObjectProvider 消费（另两处 `IngestionController:37`、`RequestContextFilter:23` 都保留 provider 延迟获取，且是 Tracer 这类可观测组件而非持久化组件）。

二跳可达的更深层：**没有**。三个协作者全部是叶子或一行接口，本类不触及 service/rag/tools/agent 任何特性层。`llm` 包对外的全部出边就是 config.properties + mapper + domain 三条向下边——它不知道任何调用方的存在。

### 2.2 入边（8 个生产调用方，5 个包层，grep 逐一核实）

| 调用方（包层） | 调用位置 | endpoint | subjectKey |
| --- | --- | --- | --- |
| controller：`ChatController` | :119（`trackedChatStream` 内） | "chat" | chatId |
| controller：`CustomerServiceController` | :39 | "service" | chatId |
| rag：`RagAnswerService` | :126 | "rag" | chatId |
| rag：`HybridRagAnswerService` | :80 | （动态） | chatId |
| service：`ReactAgentService` | :391（经私有包装，另有 :52/:122 两个业务调用点） | 动态 | subjectKey |
| agent.workflow：`WorkflowReactAgentService` | :308（经私有包装，:60/:135 两个业务调用点） | 动态 | subjectKey |
| agent.research：`ResearchPlannerAgent` | :33 | "research" | **自由文本 topic** |
| agent.research：`ReportWriterAgent` | :26 | "research" | **自由文本 topic** |

- 方向全部**指向 llm**：controller/rag/service/agent.workflow/agent.research 五层都依赖 `com.enterprise.iqk.llm`，而 llm 对它们零依赖——入边与出边无任何交汇。
- 消费契约：所有调用方拿 `decision.model()` 组装 `ChatOptions`、拿 `decision.costTier()` 喂 `TenantCostService`；`ReactResponseFormatter:24`（service 包）还把整个 decision 作为格式化上下文。嵌套 record `ModelRouteDecision` 事实上是跨 5 个包层的共享 DTO。
- 测试：`ModelRouterTest`（直接 `new ModelRouter(props, emptyMapperProvider)`，验证纯决策逻辑）、`ReactAgentServiceTest`/`ReactResponseFormatterTest`（mock 本类 / 手工构造 decision record）。
- 二参重载 `resolve(String, String)`（:38-40）grep 证实零调用（与 audit L-1 一致）。

### 2.3 横切依赖

- **仅一处隐性横切**：`saveExposure` 里 `normalizeTenant`（:212-217）与 `TenantContext.normalize`（`security/TenantContext.java:14-16`）行为一致但各写一份——本类不依赖 security 包，属刻意或偶然的解耦（与 audit L-3 相互印证）。除此之外不读 MDC、不注册 Meter、不发事件。

## 3. 循环依赖评估

### 3.1 结论：类级与包级均无环；llm 是全库依赖图的纯汇点

```
ChatController/CustomerServiceController ─┐
RagAnswerService/HybridRagAnswerService ──┤
ReactAgentService ────────────────────────┼─→ ModelRouter ─→ ModelRouterProperties（叶子）
WorkflowReactAgentService ────────────────┤              ├─→ ModelAbExposureMapper ─→ ModelAbExposure（叶子）
ResearchPlannerAgent/ReportWriterAgent ───┘              └─→ ModelAbExposure（叶子）
```

- **类级**：入边（8 个调用方 + 测试）与出边（三个叶子）无任何交汇，环在结构上不可能形成。Spring 侧构造器注入 + 零回指，无 Bean 循环依赖风险。
- **包级**：`llm` 包贡献的依赖边只有三条向下的。需要特别指出：`WorkflowReactAgentService → llm` 这条边**不参与** harness⇄workflow 包级环（arch-WorkflowReactAgentService 3.1 论证的环由 `HarnessEventRecorder → AgentWorkflowEngine` 构成）——llm 在那个环之外，且若未来按端口下沉方案消环，本类零改动。research 两 agent 同理：`agent.research → llm` 是单向叶子边。
- **演化风险（记录性）**：本类是纯汇点这件事目前靠"决策逻辑不回头调用任何特性层"维持。它唯一的对外输出是 `ModelRouteDecision` record，如果未来有人往这个 record 里加"回调/客户端句柄"类字段（例如为了省掉调用方再查一次配置），llm 就会开始反向认识特性层类型——纯汇点地位即告失守。当前无此迹象，仅标注为演化敏感点。

## 4. 越层调用与边界问题

### 4.1 "路由→预算→LLM 调用→记账"完整管线在 web 层编排——中（越层编排，本报告最重要发现）

`ChatController.trackedChatStream`（:112-137）与 `CustomerServiceController.service`（:32-56）在 controller 层完成整套编排：`modelRouter.resolve` → `tenantCostService.assertBudget` → **直接组装 `chatClient.prompt().options(ChatOptions.model(decision.model()))` 发起调用** → `tenantCostService.recordUsage`。这不是方向违规（controller→llm/mapper/cost 是向下边，Spring 全库皆如此），而是**职责越层**：模型调用编排与成本治理三步曲本属 service 层职责，此处 web 层直接持有并驱动 ChatClient。对照同库其余调用方——RagAnswerService/ReactAgentService/WorkflowReactAgentService 都把这段管线收在自己所在的 service/agent 层，controller 只传参。结果就是同一条管线在**至少 5 处**重复出现（见 5.2），而 web 层的两处恰恰是最不该出现它的地方（难以复用、难以统一加熔断/重试/日志）。**修复方向**（不改 `resolve` 签名）：把 chat/service 两条端点的"选型→预算→调用→记账"下沉为一个 service（如 `RoutedChatService`），controller 只做参数校验与响应封装。

### 4.2 llm 包直插 mapper 层：曝光落库让"策略组件"背上了持久化出边——低

`llm` 包对 `mapper`/`domain` 的依赖是合法向下边，但值得权衡：路由决策本是纯函数（读配置 + 字符串运算），可脱离一切基础设施独立测试（`ModelRouterTest` 正是这么做的）；而 `saveExposure`（:71-89）把一次同步 DB insert 缝进 `resolve` 的主路径（:67），使这个纯策略类同时是 DAO 客户端。后果有三：热路径多一次同步写（audit H-2 的性能面）、`ModelRouter` 的可测性依赖一个空 mapper provider 的造桩约定、`llm` 包的语义从"模型路由"扩散到"曝光存储"。**修复方向**（不改签名）：`resolve` 改发 `ApplicationEventPublisher` 事件（或 `@Async` 监听器），曝光写入移出本类——llm 包的 mapper/domain 两条出边随之消失，纯汇点更纯粹。

### 4.3 决策的"应用契约"靠调用方约定维持，无统一收口——低（记录性）

`resolve` 返回的 `decision.model()` 如何生效，完全由 8 个调用方各自决定：每处都手工 `.options(ChatOptions.builder().model(decision.model()).build())`（`ChatController:121`、`CustomerServiceController:47`、`RagAnswerService:129` 等）。存在一个理论上越层后的漏洞形态：任何调用方忽略 decision 而直接用请求参数里的 model 名，路由即被静默绕过——当前 grep 未见此情形（所有调用方都消费 `decision.model()`），但契约无类型强制。若按 4.2/5.2 收敛出统一的 routed client，此风险随之消除。

### 4.4 配置边界已核实无反向

`ModelRouterProperties` Java 默认（enabled=true、defaultProfile=balanced、实验 enabled=true/ab_auto/50%、experimentKey=quality_vs_cost）与 yml :125-132 的 env 兜底值逐项一致，无 Java/yml 默认反向（对照 arch-WorkspaceRuntime 4.4 的问题形态）；`endpoint-profiles`（chat=balanced、service=quality、rag=balanced）与 `profiles`（economy/balanced/quality 带 fallback 链）均在 yml 显式可配。开关语义问题（实验不受 router.enabled 门控）见 audit M-1，此处不重复。

### 4.5 无其他越层

本类不读传输层状态（不碰 MDC/请求上下文）、不依赖其他 service——除 4.2 的持久化边外，出边全部干净。对比 `BuiltinToolRuntime` 下探 rag/tools、`RagAnswerService` 直接持有 ChatClient，本类的依赖面在同类共享组件中是较克制的。

## 5. 同层平行实现与隐性耦合

### 5.1 `resolveRouteDecision` 纯透传包装两份完全重复——低

`ReactAgentService.java:387-391` 与 `WorkflowReactAgentService.java:306-308` 的私有 `resolveRouteDecision(四参)` 都是**零逻辑透传** `modelRouter.resolve(...)`，签名仅参数名不同。两文件本就是 harness⇄workflow 环的当事双方（arch-ReactAgentService 5.x 已记录多处平行实现），这是又一份。修复方向：直接内联调用 `modelRouter.resolve`，两处各删 5 行。

### 5.2 "resolve → estimateTokens → assertBudget → 调用 → recordUsage"管线 ≥5 处重复——中（与 4.1 同根）

逐处核实：`ChatController.trackedChatStream`（:119-135）、`CustomerServiceController.service`（:39-53）、`RagAnswerService`（:126 起）、`HybridRagAnswerService`（:80 起）、`WorkflowReactAgentService.callModel`（:311 起）、`ReactAgentService`（:52 起对应链路）。`ChatController:38` 的注释自己承认（"same pattern as WorkflowReactAgentService.callModel"）——项目已知此重复并选择复制而非收口。漂移风险真实存在：断言上限（600）、recordUsage 的 endpointTag 取值、输出 token 统计时机各处手抄，改一处漏五处。修复方向与 4.1 相同：收敛为单一 routed-execution 组件（决策与预算在内部串联），`ModelRouteDecision` 保持对外类型不变。

### 5.3 `normalizeTenant` 与 `TenantContext.normalize` 重复——低

见 2.3/audit L-3：行为一致、无引用关系、会漂移。若按 4.2 改事件发布，`saveExposure` 移出后此副本可一并消失（事件监听器侧复用 TenantContext）。

### 5.4 ObjectProvider 消费模式与库内其余用法不一致——低

全库三处 `ObjectProvider`：本类吞异常转 null（:29-36）、`IngestionController:37`/`RequestContextFilter:23` 保留 provider 延迟取 Tracer。本类的意图是"MyBatis 未装配时路由仍可用"（防御性降级），但降级全程无日志、且包装的是**持久化组件**而非可观测组件——两种语义、两种写法混用同一机制。若按 4.2 改事件发布，此处随 mapper 出边一并消失；若保留，至少补启动期 log.info（同 audit L-2）。

## 6. 结论与建议优先级

**总评**：依赖方向健康度极高——三个叶子出边、五层入边全部单向指向 llm，类级/包级零环、零横切（除一份 tenant 归一副本），是全库依赖图中的**纯汇点**，与 `WorkspaceRuntime` 并列为本项目边界最干净的共享组件。问题集中在**职责收敛**而非依赖方向：曝光落库把持久化缝进纯策略类（4.2），路由-预算-记账管线在 5 处手抄且两处落在 web 层（4.1/5.2）。

| 优先级 | 事项 | 章节 | 改动范围 |
| --- | --- | --- | --- |
| 1 | chat/service 两条端点的"选型→预算→调用→记账"下沉为 service，controller 退回参数层 | 4.1/5.2 | 新增 1 个 service + 2 个 controller 收缩 |
| 2 | 曝光落库改事件发布/异步监听，llm 摘除 mapper/domain 出边（联动消除 5.3/5.4 副本与热路径同步写） | 4.2/5.3/5.4 | 本类 + 新增监听器；audit H-2 的幂等迁移仍建议并行做 |
| 3 | 内联两处零逻辑 `resolveRouteDecision` 包装 | 5.1 | 2 文件各删 5 行 |
| 4 | 纯汇点演化敏感点（decision record 勿加反向类型）、决策应用契约收口 | 3.1/4.3 | 记录性，随第 1 项自然解决 |

以上各项均不改变 `ModelRouter.resolve` / `ModelRouteDecision` 的对外 API 签名；第 1 项会移动 controller 内的编排代码，需同步核对 `ChatController`/`CustomerServiceController` 现有流式语义（`trackedChatStream` 的 doFinally 记账）在下沉后保持等价。

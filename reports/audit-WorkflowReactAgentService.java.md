# 缺陷审查报告：WorkflowReactAgentService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/agent/workflow/WorkflowReactAgentService.java`（499 行，全文通读）
- **审查方式**：只读静态审查；跨类契约（`AgentWorkflowEngine`、`WorkflowState`、`AgentHarnessService`、`AgentObservation`、`TenantCostService`、`ModelRouter`、`WorkflowController`）已逐一核实
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件

---

## 概览

该类实现同步（`chat`）与流式（`stream`）两条 ReAct 工作流：规划循环（最多 `MAX_STEPS=6`）→ 工具执行 → 汇总作答，期间通过 `AgentWorkflowEngine` 落库任务/步骤/事件，并通过 `TenantCostService` 做成本治理。

总体结构清晰，但存在 **4 个高严重度缺陷**（预算治理被静默绕过、两处任务生命周期泄漏、取消路径失控）与若干中低severity问题。以下按严重度排列，行号以当前文件为准。

---

## 高严重度

### H-1 `reason()` 吞掉预算超限异常，成本治理被静默绕过

- **位置**：`WorkflowReactAgentService.java:230-237`（catch 块）；抛出点 `TenantCostService.java:41`（`assertBudget` 抛 `IllegalArgumentException("tenant budget exceeded, request blocked")`）
- **描述**：`reason()` 内部先 `callModel(...)`（其中第一步就是 `tenantCostService.assertBudget`），外层 `catch (RuntimeException ex)` 把**所有**运行时异常——包括预算超限的 `IllegalArgumentException`——吞掉后转入 `fallbackDecision()`。用户会拿到一条规则兜底答案，请求"成功"返回。
- **触发条件**：租户开启硬预算（`hardLimitEnabled=1`）且当月累计成本超过 `monthlyBudgetUsd`；或 `costGovernanceProperties.isEnabled()` 下的任何预算断言失败。
- **影响**：预算硬限制形同虚设——规划阶段被拦，兜底路径却不消耗预算地继续走完整个工作流（工具执行 + 汇总），治理意图被无声绕过；且无日志、无指标、无告警。
- **修复建议**：把 `assertBudget` 的断言从 `callModel` 的 try 覆盖范围中分离——在 `reason()` 的 try 之外先单独执行预算断言（或让 catch 精确匹配"模型调用/JSON 解析"异常类型，预算异常向上抛）。上层 `chat()`/`stream()` 已有 failTask + 错误透出通道，预算异常应当让任务 FAILED 并把 "tenant budget exceeded" 返回给调用方。

### H-2 `chat()` 的 `startTask`/`resolveRouteDecision` 在 try 之外，异常时产生孤儿任务

- **位置**：`WorkflowReactAgentService.java:56-61`（`startTask` 与 `resolveRouteDecision`），try 从 :66 才开始
- **描述**：`chat()` 中 `workflowEngine.startTask(...)` 先落库（状态 `CREATED→PLANNING`），随后 `resolveRouteDecision(...)`（内部含实验路由、曝光落库等 DB 操作）在 try 块之外执行。若路由解析抛出 `RuntimeException`，:113 的 `catch` 捕不到，任务记录将**永久停留在 PLANNING 非终态**，没有 `failTask`、没有 `recordTaskMetrics`。
- **触发条件**：`modelRouter.resolve` 抛异常（实验配置异常、曝光表写入失败等）；`startTask` 成功但紧随其后的任何非 try 内逻辑失败。
- **影响**：`agent_workflow_task` 表累积非终态脏数据；`WorkflowController` 的任务查询会看到永远"卡住"的任务。
- **修复建议**：将 try 块起点上移到 `startTask` 之后（`resolveRouteDecision` 纳入 try），保证任何时刻任务创建成功后必有终态闭环。`stream()` 侧因全部包在 `Flux.defer` + `onErrorResume` 内，无此问题。

### H-3 流式取消（cancel）路径：任务卡非终态 + 指标误标为 error

- **位置**：`WorkflowReactAgentService.java:206-218`（`onErrorResume` 不会在 cancel 时触发；`doFinally` 于 :218 忽略了 signal 参数）；`outcomeRef` 初始化 :123
- **描述**：客户端断开 SSE 连接时 Reactor 发出 cancel 信号：`onErrorResume` 不执行 → `failTask` 不调用 → 任务（及正在执行的 step）**永久停留在非终态**；`outcomeRef` 保持初始值 `"error"`，`doFinally` 把一次用户主动取消计入 `outcome=error` 指标。
- **触发条件**：前端调用 `stopGenerating()`（`AbortController.abort()`）、浏览器断开、网关超时等任何流取消场景。前端聊天页有明确的"停止输出"按钮，此路径是常态路径而非边缘。
- **影响**：非终态孤儿任务（同 H-2 的数据后果）；`react.stream.requests{outcome=error}` 把取消与真实失败混在一起，可用性统计失真。
- **修复建议**：`doFinally(signal -> ...)` 中区分 `Signal.ON_CANCEL`：执行 `failTask(taskIdRef.get(), "client cancelled")`（若有 taskId），并用独立 outcome 标签（如 `"cancelled"`）记录指标。

### H-4 工具执行抛异常时，step 记录永久停留 RUNNING

- **位置**：`WorkflowReactAgentService.java:97-103`（chat）、`:162-168`（stream）；`startStep` 落库在 :68-70 / :143-145；`AgentHarnessService.java:47-49` 说明 harness 只兜住 runtime 层异常
- **描述**：`startStep` 以 `status="RUNNING"` 落库。`agentHarnessService.execute` 对工具本体异常有兜底（转 error observation），但 policy 评估、payload sanitizer、`toMap()` 序列化以及 harness 之外的任何 `RuntimeException` 仍会抛出：chat 侧 :113 的 catch 只做 `failTask`，**不会回写 step 终态**；stream 侧 `onErrorResume` 同样只 `failTask`。
- **触发条件**：`ActionPolicyGuard.evaluate`、`HarnessPayloadSanitizer.limitObservation` 抛异常；或 `completeStep` 之前的任何运行时异常。
- **影响**：`agent_workflow_step` 表遗留 RUNNING 僵尸步骤，工作流回放/审计（`getTask` → `toStepVO`）失真。
- **修复建议**：在 chat 的 catch 与 stream 的 `onErrorResume` 中，若持有一条未关闭的 stepId，补一次 `completeStep(stepId, "FAILED", null, null, ..., errorMessage, ...)`。可将 `stepId` 存入 `AtomicReference` 与 `taskIdRef` 并列管理。

---

## 中严重度

### M-1 状态机 off-by-one：`SEARCHING` 永不进入，事件里的 `from` 与实际状态不符

- **位置**：`WorkflowReactAgentService.java:94-95`；`WorkflowState.java:19-31`（`canTransitionTo`）；`AgentWorkflowEngine.java:107-119`（`transitionStatus` 不校验 DB 当前态）
- **描述**：`startTask` 结束时任务状态为 `PLANNING`。循环内首个转换调用是 `transitionStatus(taskId, mapToWorkflowState(1)=SEARCHING, mapToWorkflowState(2)=RETRIEVING)`——`SEARCHING→RETRIEVING` 作为静态规则合法，但 DB 实际状态是 `PLANNING`，`updateStatus` 不校验当前态，直接把任务推成 `RETRIEVING`。结果：`SEARCHING` 与 `JUDGING`（step 2 的 from 语义）等状态从未真正出现；`STATE_CHANGED` 事件记录的 `from` 字段与事实不符。另外 stepNum=5 时 `WRITING→WRITING` 是非法转换（`canTransitionTo` 返回 false），每次 5 步以上任务都会打一条 warn 日志。
- **触发条件**：任何一次 `chat()` 执行。
- **影响**：工作流状态轨迹（事件溯源）失真；日志噪音。
- **修复建议**：循环前先做 `transitionStatus(PLANNING→SEARCHING)`；循环内改为"执行动作前推进到该 step 对应状态"（`from` 用 `currentState(taskId)` 或上一步的 to），step 5 之后的重复转换直接跳过。

### M-2 `reason()`/`summarizeAnswer()` 静默吞异常，可观测性缺失

- **位置**：`WorkflowReactAgentService.java:234-236`（reason）、`:267-269`（summarizeAnswer）
- **描述**：除 H-1 的预算问题外，模型调用失败（网络、超时、路由异常）也都被无日志地吞掉。`summarizeAnswer` 失败时返回固定文案"当前未能生成最终答案"，但没有任何 `log.warn` 或计数器，线上排障只能靠猜。
- **触发条件**：LLM 网关抖动、超时、`chatClient` 抛出任何运行时异常。
- **影响**：降级不可见；失败率被低估（任务最终 DONE）。
- **修复建议**：catch 中至少 `log.warn("react planner failed, using fallback", ex)` 并增加 `Counter("react.planner.fallback")` 类指标；预算类异常按 H-1 单独处理。

### M-3 整个 ReAct 循环以阻塞方式运行在响应式装配内

- **位置**：`WorkflowReactAgentService.java:126-204`（`Flux.defer` 内：循环里的同步 `reason→callModel`（`chatClient...call()`）、`executeAction`、多次 DB 写）；`WorkflowController.java:32-37`
- **描述**：`stream()` 把整段阻塞逻辑包进 `Flux.defer`，订阅时同步执行完整个规划循环后才开始发 trace 事件。当前栈是 Spring MVC（`ReactiveTypeHandler` 异步适配），阻塞发生在 servlet 异步线程上，与 `chat()` 等价、可接受；但 trace 事件实际是循环结束后一次性批量发出（`Flux.concat(traceFlux, tokenFlux)`），"流式"只覆盖最终作答阶段。若未来迁移 WebFlux/Netty，这段代码将阻塞 event loop。
- **触发条件**：当前部署下表现为 trace 不增量推送（功能体验问题）；WebFlux 迁移后为事件循环阻塞（故障级）。
- **影响**：中途 trace 无法实时展示；架构约束未文档化，迁移时易踩雷。
- **修复建议**：短期在方法 Javadoc 标注"MVC 专用，禁止 WebFlux event-loop 订阅"；中期把每步 trace 通过 `Sinks.many()` 边生成边发，阻塞段包 `Flux.defer(...).subscribeOn(boundedElastic)`。

### M-4 `stream()` 全程不推进工作流状态，任务停在 PLANNING 直到 DONE

- **位置**：`WorkflowReactAgentService.java:120-204`（无任何 `transitionStatus` 调用，对比 chat 的 :94-95）
- **描述**：流式路径中任务从 `PLANNING` 直接跳到终态，`SEARCHING/RETRIEVING/...` 状态与对应事件完全缺失。
- **触发条件**：任何 `stream()` 调用。
- **影响**：`chat` 与 `stream` 的状态轨迹口径不一致，基于状态的看板/审计对流式流量失明。
- **修复建议**：与 M-1 一起修——抽出公共的状态推进逻辑供两条路径复用。

### M-5 步骤审计数据失真：token 恒 0、错误观测仍记 COMPLETED

- **位置**：`WorkflowReactAgentService.java:85-88, 100-103`（chat）、`:155-158, 165-168`（stream）——`completeStep` 的 `inputTokens/outputTokens` 恒传 `0, 0`，`errorMessage` 恒传 `null`，status 恒 `"COMPLETED"`
- **描述**：`callModel` 已经估算过 token（:313-314），却没有回填到 step 记录；工具观测 `status="error"`（harness 策略拒绝/无 runtime/工具异常）时，步骤依然记 `"COMPLETED"`，错误信息只藏在 observation JSON 里。
- **触发条件**：所有步骤；错误场景下语义更糟。
- **影响**：`WorkflowStepVO` 暴露的 `inputTokens/outputTokens` 恒为 0，按步骤的成本分析、按状态的成功率统计全部失真。
- **修复建议**：`executeAction` 返回观测后按 `observation.get("status")` 决定 step status（error → `"FAILED"` + errorMessage）；`callModel` 把估算的 token 返回或写入上下文供 `completeStep` 回填。

---

## 低严重度

### L-1 `parseDecision` 只捕获 `JsonProcessingException`，`convertValue` 的 `IllegalArgumentException` 会丢弃整份合法决策

- **位置**：`WorkflowReactAgentService.java:364-366, 375`
- **描述**：模型返回合法 JSON 但 `action_input` 是标量/数组时，`objectMapper.convertValue(..., Map 类型)` 抛 `IllegalArgumentException`，不在 catch 范围内，异常上抛被 `reason()` 兜底成 `fallbackDecision`——一份本可用的计划（thought/answer 都在）被整体丢弃。
- **修复建议**：对 `node.path("action_input")` 先做 `isObject()` 守卫，非对象直接取空 Map；或 catch 面扩大到 `Exception` 但仅限该 convert 语句。

### L-2 `extractJson` 首尾大括号截取过于脆弱

- **位置**：`WorkflowReactAgentService.java:411-416`
- **描述**：取第一个 `{` 到最后一个 `}`。模型输出如"说明文字 {jsonA} 中间文字 {jsonB}"会截出非法 JSON。当前有 fallback 兜底（L-1 同路径），但会放大 planner 不稳定。
- **修复建议**：做括号平衡扫描取第一个完整 JSON 对象；或提示词中强制代码栅栏并按栅栏提取。

### L-3 `fallbackDecision` 关键词过宽，兜底路线易误判

- **位置**：`WorkflowReactAgentService.java:395-404`
- **描述**：`"source"`、`"姓名"`、`"联系方式"` 等高频词出现在任意问题中就会触发对应兜底（例如英文提问里出现 "source" 一词即走 rag_search）。仅影响 LLM 不可用时的兜底质量。
- **修复建议**：收紧为更具体的短语组合（如 "知识库"+"查"），或在兜底时优先返回"规划器暂不可用"的通用文案。

### L-4 `stream()` 的 `startStep` 输入缺 `rollingContext`，与 `chat()` 口径不一

- **位置**：`WorkflowReactAgentService.java:143-145`（仅 `Map.of("prompt", ...)`）对比 `:68-70`
- **描述**：流式步骤审计少了滚动上下文，回放时无法还原规划依据。
- **修复建议**：对齐 chat 的输入结构。

### L-5 空答案时 `firstTokenMs` 被兜底设为总时长，TTFT 指标失真

- **位置**：`WorkflowReactAgentService.java:195-197`
- **描述**：模型无任何 token 输出时，done-defer 里把 `elapsedMs(startedNs)`（总时长）当作首 token 延迟写入 `react.stream.first_token.latency`。
- **修复建议**：无 token 时跳过 TTFT 记录（保持 null），或单列 `empty_answer` outcome。

### L-6 `startedNs` 在方法调用时捕获而非订阅时

- **位置**：`WorkflowReactAgentService.java:121, 126`（`Flux.defer` 之外捕获）
- **描述**：`Flux` 从装配到被 MVC 订阅存在间隔（通常极小，但异步 servlet 下不保证），这段空窗被计入总延迟与首 token 延迟。
- **修复建议**：把 `startedNs` 的捕获移入 `Flux.defer` 内部。

### L-7 `Flux.defer` 内含副作用（`startTask` 落库），重订阅会重复建任务

- **位置**：`WorkflowReactAgentService.java:126-133`
- **描述**：`defer` 使每次订阅都执行 `startTask`。当前 MVC 单订阅无碍；一旦上游引入 retry/replay 语义会产生重复任务。
- **修复建议**：如保留副作用，加 `cache()` 或在 Javadoc 标注"仅允许单次订阅"。

### L-8 规划提示词直接内插 trace/观测 JSON，存在提示注入面

- **位置**：`WorkflowReactAgentService.java:228`（planner prompt 内插 `toJson(trace)`）、`:275`（final prompt 同理）
- **描述**：工具观测（如 rag 检索到的文档内容、用户此前输入）原样进入提示词，攻击者可通过文档内容诱导 planner 输出恶意 action（如 `add_course_reservation`）。harness 侧有 `HarnessPayloadSanitizer`，但规划器提示词未复用。
- **修复建议**：对进入提示词的观测做长度截断与结构转义；对 planner 输出的 action 白名单校验已有（:369-372，值得肯定），可补充对 `action_input` 键值的约束。

### L-9 `parseDecision` 的 `citations/evidence` 恒为空列表，字段形同虚设

- **位置**：`WorkflowReactAgentService.java:374`（`List.of(), List.of()`）
- **描述**：`ReasonDecision.citations/evidence` 只在 `fallbackDecision` 中非空，正常 planner 路径永远为空 → chat 的 finish 分支（:81-82）放进 obs 的 citations/evidence 恒为 `List.of()`。实际引用全部依赖 `extractTraceStrings` 从 rag_search 观测提取（`BuiltinToolRuntime.java:102-104` 确认该路径有效）。
- **修复建议**：要么从 planner JSON 里解析 `citations` 字段（若提示词允许），要么删除这两个死字段简化 record。

---

## 已核实为「非问题」的点

| 疑点 | 结论 |
|---|---|
| `Map.of(...)` NPE（:70, :145, :189） | `validateRequest`（:491-494）保证 prompt/chatId 非空；`rollingContext` 初始为 `""`，不会为 null |
| `estimateTokens(null)` | `TenantCostService.java:122-124` 对空白串返回 0，`callModel` 的 output 为 null 时安全 |
| `modelRouter.resolve` 返回 null 导致 NPE | `ModelRouter.java:42-65` 所有分支均构造非 null decision；`success()` 里的 null 防御（:296-301）属冗余但无害 |
| SSE 格式与前端解析器兼容 | `formatSse`（:446-448）输出 `event:`/`data:` 行，与 `frontend/src/api/client.ts` 的 `parseSseChunk` 匹配 |
| `callModelStream` 计费 | `AtomicBoolean recorded` + `doFinally`（:331-340）保证取消/完成/异常各恰好记一次，实现正确 |
| planner action 白名单 | `parseDecision` :369-372 校验 + `normalizeAction` 小写化，未知 action 回落 finish，逻辑正确 |
| `chat()` 顶层 catch 覆盖面 | 仅 catch `RuntimeException`；`Error` 泄漏属 JVM 级极端场景，实践中可接受（不建议改为 catch Throwable） |

---

## 修复优先级建议

1. **立即**：H-1（预算绕过）、H-2/H-3/H-4（任务/步骤生命周期闭环——三者可在一个 catch/doFinally 重构中一并解决）。
2. **随后**：M-1/M-4（状态机口径统一）、M-2（降级可观测）、M-5（步骤审计数据回填）。
3. **择机**：M-3（流式真增量 + 阻塞隔离）、其余 L 项随重构顺手处理。

> 注：所有修复均不需要变更 `chat(...)`/`stream(...)` 对外签名；内部私有方法调整即可。

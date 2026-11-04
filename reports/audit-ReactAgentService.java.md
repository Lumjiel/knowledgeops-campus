# 缺陷审查报告：ReactAgentService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/service/ReactAgentService.java`（470 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`ReactDecisionParser`、`ReactResponseFormatter`（全文）、`ConversationIdHelper`、`CommonConfiguration`（ChatClient/advisor 装配）、`HarnessEventRecorder`、`BuiltinToolRuntime`、`TenantCostService`、`TenantContext.normalize`、`ReactController`
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件
- **关联报告**：`reports/audit-WorkflowReactAgentService.java.md`（同族工作流变体，共用 harness/cost 契约）

---

## 概览

该类是 `/ai/react/chat` 与 `/ai/react/chat/stream` 的核心实现：ReAct 规划循环（`MAX_STEPS=4`）→ harness 工具执行 → 最终作答，决策解析与响应组装已抽到 `ReactDecisionParser`/`ReactResponseFormatter`。相比工作流变体，本类无任务持久化（无孤儿任务问题）、降级路径有日志（有改进），但存在 **3 个高严重度缺陷**：ChatMemory advisor 导致的上下文污染与成本低估、预算治理绕过、流式消毒路径的答案翻倍。

---

## 高严重度

### H-1 ChatMemory advisor 污染规划上下文，且 token 估算/预算断言系统性低估

- **位置**：`ReactAgentService.java:394-405`（`routedPrompt` 对**所有**调用挂 `CONVERSATION_ID` advisor）、`:407-423`（`callModel`）；装配根源 `CommonConfiguration.java:20-29`（注入的 `chatClient` 默认带 `MessageChatMemoryAdvisor`，底层为 `MysqlChatMemory`）
- **描述**：`callModel`/`callModelStream` 通过 `routedPrompt(...)` 以固定会话 id `react::<chatId>`（`:402-405`）调用模型。MessageChatMemoryAdvisor 会把每次调用的 user/system 消息与模型回复**写入并回灌**会话记忆。于是：
  1. 每个规划步的 planner prompt（含全量 rollingContext + trace JSON）与原始 planner 输出都被持久化进 `react::<chatId>` 的对话记忆；
  2. 下一次 `reason()` 调用时，advisor 把之前所有 planner 对话作为历史消息拼进请求——上下文随步数与用户轮次**二次膨胀**，planner 看到的"对话历史"是自己的决策 JSON 呓噪，规划质量被污染；
  3. `callModel` 的 token 估算只统计当前 system+user 两个串（`:413`），**不含 advisor 注入的历史**，`assertBudget`（`:414`）与 `recordUsage`（`:421`）随之系统性低估真实消耗，`react_final` 同理。
- **触发条件**：同一 chatId 的第二次 ReAct 调用起即生效（第一次也有记忆写入）；长会话/多轮使用下持续恶化。
- **影响**：上下文膨胀 → 延迟与真实 token 成本上升；预算治理低估；规划被历史 JSON 干扰。另注意 `stream()` 的 directAnswer 路径（`splitForStreaming`）不过 chatClient，同一会话的记忆内容在不同路径下口径不一致。
- **修复建议**：规划器与汇总调用不应共享带记忆的会话——用 `chatClient.mutate()` 派生去掉 MessageChatMemoryAdvisor 的 client（或按调用加 advisor 过滤参数）；若确需记忆，为 planner 使用独立命名空间并计入历史 token。对外 API 签名无需变更。

### H-2 预算超限异常被 `reason()`/`summarizeAnswer()` 吞掉，成本治理被静默绕过

- **位置**：`ReactAgentService.java:314-327`（reason catch）、`:352-367`（summarizeAnswer catch）；抛出点 `TenantCostService.java:41`（`assertBudget` 抛 `IllegalArgumentException("tenant budget exceeded, request blocked")`）
- **描述**：`callModel` 第一步就是预算断言，但两个调用方的 `catch (RuntimeException)` 都会捕获它：`reason()` 转入 `decisionParser.fallback()`，`summarizeAnswer()` 返回降级文案。请求"成功"完成（fallback=true），硬预算拦截形同虚设。相比 Workflow 变体，本类已有 `log.warn`（`:325`、`:365`），但仍无独立指标、无对预算异常的区分。
- **触发条件**：租户开启硬预算（`hardLimitEnabled=1`）且当月成本超 `monthlyBudgetUsd`。
- **影响**：规划被拦后兜底路径（工具执行 + 汇总）继续无预算地走完；治理意图被无声绕过，只有日志可查。
- **修复建议**：将 `assertBudget` 从 try 覆盖范围分离——在 try 之外先单独断言预算（或 catch 精确限定为模型调用/解析异常），让预算异常向上传播，由全局异常处理器返回明确的预算错误。

### H-3 流式消毒路径把答案追加两次，done 事件的 answer 翻倍

- **位置**：`ReactAgentService.java:194-204`（`switchOnFirst` 的 `collectList().map(...)` 内 `answerBuilder.setLength(0); answerBuilder.append(extracted);`）与 `:210-217`（`tokenFlux.map` 再次 `answerBuilder.append(token)`）
- **描述**：当流式最终作答的首个 token 以 `{` 开头时进入消毒分支：`collectList` 提取 answer 字段后先写入 `answerBuilder`，随后该分支返回的 Flux 仍会发出这同一个字符串，`tokenFlux` 的 map 又把它 append 一次——`answerBuilder = extracted + extracted`。`:220-228` 的 done 事件用 `answerBuilder.toString()` 组装 `success(...)`，**done 载荷里的 answer 是 SSE 已下发内容的两倍**。
- **触发条件**：最终作答（`callModelStream`）输出以 `{` 开头——代码注释（`:187-188`）自认 cheap 模型会偶发把决策 JSON 当答案，即该分支是真实可达路径；`directAnswer` 路径的兜底文案不以 `{` 开头，不受影响。
- **影响**：done 载荷与已下发 token 不一致；前端（`useChatEngine` 的 done 处理会用 `done.answer` 覆盖累积内容）最终渲染出重复答案。
- **修复建议**：删除 `:198-199` 的 `setLength(0)/append` 两行——`tokenFlux` 的 map 已负责全量累积；或反过来删掉 tokenFlux 对消毒分支的追加，二选一，保持单一写入点。

---

## 中严重度

### M-1 `executeAction` 传空 taskId/stepId，harness 事件溯源对整个 `/ai/react` 入口静默失效

- **位置**：`ReactAgentService.java:334-343`（`new AgentAction(..., "", "")`）；`HarnessEventRecorder.java:42-46`（`shouldRecord` 要求 taskId/stepId 非空白，否则直接 return）
- **描述**：工作流变体传真实 taskId/stepId，本类传空串。`AgentHarnessService.execute` 内部的 `eventRecorder.started/completed` 因此全部被静默跳过——`ACTION_STARTED/ACTION_COMPLETED/ACTION_FAILED` 事件对该入口**零记录**，且无任何警告。
- **触发条件**：所有 `/ai/react/chat` 与 `/ai/react/chat/stream` 请求。
- **影响**：基于 `agent_workflow_event` 的审计/回放/风险动作追踪对 react 主入口完全失明（对比 `/ai/workflow/react/*` 有完整事件）。
- **修复建议**：至少生成请求级标识（如 `"react-" + UUID`）传入 `AgentAction`；或若刻意不落事件，在代码处注释说明并让 `shouldRecord` 的跳过可观测（debug 日志）。

### M-2 `stream()` 取消路径指标误标为 error

- **位置**：`ReactAgentService.java:117`（`outcomeRef` 初始 `"error"`）、`:230-234`（`onErrorResume` 不覆盖 cancel；`doFinally(signal -> ...)` 忽略 signal）
- **描述**：客户端断开 SSE（前端"停止输出"按钮）触发 cancel：`outcomeRef` 保持 `"error"`，被计入 `react.stream.requests{outcome=error}` 与总延迟直方图。本类无任务持久化（相比 Workflow 变体无孤儿任务问题），但取消与真实失败在指标上不可区分。
- **触发条件**：任何流取消（用户主动停止、断连、网关超时）。
- **修复建议**：`doFinally` 中按 `Signal.ON_CANCEL` 使用独立 outcome 标签（如 `"cancelled"`）。

### M-3 规划循环以阻塞方式运行在 `Flux.defer` 内，trace 实为循环后批量下发

- **位置**：`ReactAgentService.java:119-167`（defer 内：同步 `reason→callModel`、harness 执行、MySQL 记忆读写）；`ReactController.java:32-38`（Spring MVC 接线）
- **描述**：整段 ReAct 循环在订阅时同步执行完，`traceFlux`（`:171-172`）在循环结束后才拼入——"流式"只覆盖最终作答阶段，中途 trace 不能实时推送。当前 MVC 下阻塞发生在请求线程（与 `chat()` 等价，可接受）；若迁移 WebFlux/Netty 将阻塞 event loop。
- **修复建议**：短期在 Javadoc 标注 MVC 专用约束；中期用 `Sinks.many()` 边生成 trace 边发，阻塞段 `subscribeOn(boundedElastic)`。

### M-4 `chat()` 消毒失败替换文案未标记 fallback，降级对外不可见

- **位置**：`ReactAgentService.java:72-78`（`.orElse("（模型输出异常，请重试）")`）、`:79-95`（observation 的 `fallback` 只含 `usedFallback`）
- **描述**：最终答案疑似决策 JSON 但提取失败时，答案被替换为固定文案，但 `usedFallback` 不变，响应 `fallback=false`。同理 `stream()` 的消毒分支（`:200-202`）替换文案后 `responseUsedFallback`（`:169`，在消毒前捕获）也不变。
- **触发条件**：cheap 模型输出 JSON 且其中无 answer 字段。
- **影响**：前端无法区分"正常回答"与"输出异常兜底"，评测/监控口径失真。
- **修复建议**：替换发生时置 `usedFallback = true` 并透传。

---

## 低严重度

### L-1 `stream()` 的请求校验错误变成 HTTP 200 + SSE error 事件，与 `chat()` 的 400 不一致

- **位置**：`ReactAgentService.java:119-120`（`validateRequest` 在 defer 内）对比 `:50`（chat 同步抛出）
- **描述**：缺 prompt/chatId 的流式请求返回 200 + `event: error`，而非 400。前端 `streamReactChat` 只在非 ok 响应时走 `formatHttpError`，200 的 error 事件靠流内错误处理兜住——可用但 API 语义不一致。
- **修复建议**：在 `stream()` 方法体内（defer 之外）先校验一次，让非法请求直接 400。

### L-2 `splitForStreaming` 按 char 切片可能切断 UTF-16 代理对

- **位置**：`ReactAgentService.java:267-274`（固定 24 char 步长 `substring`）
- **描述**：含 emoji/生僻字（代理对）的答案可能被从中间切开，下游按 token 拼接虽能复原，但单段是非法字符串，若前端按段渲染会闪现乱码。
- **修复建议**：按 code point 边界切分（`offsetByCodePoints`）。

### L-3 `parse()` 只 catch `JsonProcessingException`，`action_input` 为标量时整份决策被丢弃

- **位置**：`ReactDecisionParser.java:36-39, 49`（`convertValue` 抛 `IllegalArgumentException` 不在 catch 内）；影响本类 `reason()` 的 `:323`
- **描述**：模型返回合法 JSON 但 `action_input` 是字符串/数组时，`convertValue` 抛 `IllegalArgumentException` → 上抛被 `reason()` 捕获 → 整份（本可用的）决策进 fallback。
- **修复建议**：对 `node.path("action_input")` 先做 `isObject()` 守卫。

### L-4 `extractJson` 首尾大括号截取脆弱

- **位置**：`ReactDecisionParser.java:179-186`（供本类 `reason()` 使用）
- **描述**：输出含多段 JSON/前后缀文本时可能截出非法 JSON，放大 planner 不稳定（有 fallback 兜底，后果有限）。
- **修复建议**：括号平衡扫描取第一个完整 JSON 对象。

### L-5 `fallback()` 关键词过宽，兜底路线易误判

- **位置**：`ReactDecisionParser.java:96`（`"source"`、`"文档"` 等）、`:72`（`"姓名"`）
- **描述**：任意英文提问含 "source" 一词即被路由到 rag_search 兜底。仅影响 LLM 不可用时的兜底质量。
- **修复建议**：收紧为更具体的短语组合。

### L-6 `startedNs` 在方法调用时捕获而非订阅时

- **位置**：`ReactAgentService.java:115, 119`
- **描述**：Flux 装配到订阅的空窗计入总延迟与 TTFT。
- **修复建议**：把 `startedNs` 移入 `Flux.defer` 内部。

### L-7 TTFT 指标失真（两处）

- **位置**：`ReactAgentService.java:221-223`（空答案时把总时长当首 token 延迟）；`:194-204`（消毒分支整段缓冲后单发，"首 token" 实为全量完成时刻）
- **描述**：`react.stream.first_token.latency` 在这两类场景显著偏高。
- **修复建议**：无 token 时跳过 TTFT；消毒分支可接受失真但建议加标记。

### L-8 规划/汇总提示词直接内插 trace 与观测 JSON，存在提示注入面

- **位置**：`ReactAgentService.java:281-312`（planner prompt）、`:373-384`（final prompt）
- **描述**：rag 检索内容、用户输入等可携带指令文本进入提示词。planner 输出的 action 白名单校验（`ReactDecisionParser.java:33-35`）是有效缓解，但 `action_input` 键值未约束。
- **修复建议**：对入提示词的观测做截断/转义；对高风险 action（`add_course_reservation`）的 `action_input` 做模式校验。

### L-9 非 JSON planner 输出被原样当答案透出

- **位置**：`ReactDecisionParser.java:132-138`（`finishWithAnswer`：提取不到 answer 字段时 `answer = rawModelOutput`）
- **描述**：planner 输出无法解析且不含 answer 字段时，原始文本（可能含模型呓噪/半截 JSON 之外的内容）作为 finish 答案直达用户。`chat()` 的 `{` 前缀消毒（`:72`）只覆盖以 `{` 开头的情形。
- **修复建议**：提取失败时改用固定兜底文案并置 fallback=true（与 M-4 一并处理）。

---

## 已核实为「非问题」的点

| 疑点 | 结论 |
|---|---|
| `conversationId` 兜底 `"default"` 不可达 | `validateRequest`（`:459-466`）保证 chatId 非空，`:403` 的 fallback 分支是死代码但无害 |
| `ConversationIdHelper.build` 抛异常 | type/chatId 均非空白（validateRequest 已保证），不会触发 |
| `Map.of("token", token)` NPE | Spring AI stream 不发 null 元素；即便为 null，`emptyIfBlank`（`:441`）在 doOnNext 已守 |
| `estimateTokens(null)` | `TenantCostService.java:122-124` 对空白串返回 0，`callModel` output 为 null 安全 |
| `modelRouter.resolve` 返回 null | `ModelRouter.java:42-65` 所有分支构造非 null decision |
| `citationCards` 提取是死字段？ | 不是——`BuiltinToolRuntime.java:103` 确认 rag_search payload 含 `citationCards`，`ReactResponseFormatter.extractTraceMaps` 能取到 |
| `callModelStream` 计费 | `AtomicBoolean` + `doFinally`（`:435-448`）在完成/异常/取消各恰好记一次，正确 |
| SSE 格式兼容前端 | `formatSse` 与 `frontend/src/api/client.ts` 的 `parseSseChunk` 匹配 |
| chat() 孤儿任务 | 本类无任务持久化，异常直接上抛由全局异常处理器处理，不存在 Workflow 变体的孤儿问题 |
| switchOnFirst 误伤正常 `{` 开头回答 | 属设计取舍（缓冲全量再发），非缺陷；首 token TTFT 失真已在 L-7 记录 |

---

## 修复优先级建议

1. **立即**：H-3（删两行即可修的确定性正确性 bug）、H-1（派生无记忆 advisor 的 client，收益最大）、H-2（预算断言移出 catch 范围）。
2. **随后**：M-1（补 taskId/stepId 恢复事件溯源）、M-4 + L-9（降级可见性）、M-2（取消指标口径）。
3. **择机**：M-3（trace 增量化 + 阻塞隔离）、其余 L 项随手处理。

> 注：以上修复均在私有方法/协作类内部完成即可，`chat(...)`/`stream(...)` 对外签名无需变更。

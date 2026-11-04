# 缺陷审查报告：AgentWorkflowEngine.java

- **审查对象**：`src/main/java/com/enterprise/iqk/agent/workflow/AgentWorkflowEngine.java`（291 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`WorkflowState`（状态机全文）、`AgentTaskMapper`/`AgentStepMapper`/`AgentEventMapper`（三条 SQL 逐条核对，确认 `updateStatus`/`completeTask`/`completeStep` 均为**无条件** UPDATE）、`AgentTaskRecord`/`AgentStepRecord`/`AgentEventRecord`（`@TableId` 均存在，`updateById` 可用）、建表 SQL `V10__agent_workflow_tables.sql`（无外键、列类型/长度）；调用方核实全部 5 个：`WorkflowReactAgentService`（同步 + 流式两条路径的关键区段通读）、`DeepResearchService`（调用点上下文）、`HarnessEventRecorder`、`WorkflowController`、`DeepResearchController`；并核对了 `SecurityConfiguration` 中 `/ai/workflow` 的授权规则（无专属规则，落入 `anyRequest().authenticated()`）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm/mvn 操作
- **关联报告**：`reports/audit-WorkflowReactAgentService.java.md`（H-1/H-2 的触发链涉及该文件的调用方代码）、`reports/audit-DeepResearchService.java.md`（如已产出）、`reports/audit-WorkspaceRuntime.java.md`

---

## 概览

该类是 agent 工作流的持久化与可观测性中枢：任务/步骤/事件三张表的读写、状态机流转、best-effort 事件溯源、Micrometer 指标、租户内查询。做对的部分：

- 读路径租户过滤一致：`getTask`/`getTaskEvents`/`listTasks` 全部先经 `findByTenantAndTaskId`/`findByTenant` 按租户收敛，steps/events 虽按 taskId 追查，但前提是任务归属已验证（`:189-197`、`:208-215`）。
- 事件持久化失败只 `log.error` 不上抛（`:135-149`），事件侧故障不阻断 agent 主流程，是合理的设计选择。
- 状态机集中在 `WorkflowState.canTransitionTo`，非法流转有枚举级定义；ID 全部 UUID 去连字符，无可预测性。
- 指标标签基数受控：调用方传入的 `type`/`status`/`agentName` 均为字面量（"REACT"/"DEEP_RESEARCH"/"REACT_STREAM" 等），无用户输入进标签。

但状态机的**终态保护在 SQL 层并不存在**（日志与注释声称的防御是错觉）、**失败处理路径自身可抛 NPE**，以及查询面的分页/N+1 与租户内数据暴露问题。以下发现均不需变更任何对外方法签名即可修复。

---

## 高严重度

### H-1 `failTask` 对 null errorMessage 抛 NPE，且爆发在调用方的 catch 块内——失败处理路径自身失败

- **位置**：`AgentWorkflowEngine.java:102`（`Map.of("error", errorMessage)`——`Map.of` 不接受 null 值）；调用方证据：`WorkflowReactAgentService.java:114`、`:213`，`DeepResearchService.java:102`（三处均为 `failTask(taskId, e.getMessage())`）
- **描述**：`e.getMessage()` 对大量 RuntimeException（典型如 NPE 自身、部分包装异常）返回 null。`Map.of("error", null)` 立即抛 NPE。三处调用方全部在 **catch/onErrorResume 块内**调用 `failTask`：同步路径（`WorkflowReactAgentService:113-116`）里，这个 NPE 在 `:114` 抛出，取代了 `:116` 本应重抛的原始异常——排障者看到的是一句与根因无关的 NPE；流式路径（`:206-216` 的 `onErrorResume`）里 NPE 在 `:213` 先于 `:216` 的 error 帧下发抛出，SSE 客户端**收不到任何错误帧**；同时 `:214` 的 FAILED 指标被跳过。注意任务行已被 `:101` 的 `completeTask` 置为 FAILED，破坏只发生在异常传播与事件/指标侧。
- **触发条件**：任何 agent 主流程抛出 message 为 null 的 RuntimeException（生产中最常见的恰恰是 NPE 类故障）。
- **影响**：根因异常被遮蔽、错误帧丢失、失败指标漏记——恰好是系统最需要可观测性的时刻。
- **修复建议**：payload 构造容忍 null：`Map.of("error", errorMessage == null ? "unknown error" : errorMessage)`，或改用 `Collections.singletonMap`/`HashMap`；防御性更强的做法是在 `failTask` 内对 null 归一化。对外签名不变。

### H-2 终态保护在 SQL 层不存在：DONE 任务可被改写为 FAILED，`final_output` 被错误信息覆盖

- **位置**：`AgentTaskMapper.java:35-36`（`updateStatus` 无 `AND status = #{from}` 守卫）、`:38-41`（`completeTask` 无终态守卫）；`AgentWorkflowEngine.java:113-115`（`updated == 0` 被解读为"already in terminal state"——**该日志是错的**：无守卫 UPDATE 下 `updated == 0` 仅意味着 task_id 不存在）；触发链证据：`WorkflowReactAgentService.java:89→91`（completeTask 后仍会执行 `success(...)`，若其抛 RuntimeException 则落入 `:114` failTask）、`:201` 之后（completeTask 后 `formatSse`/`toJson` 抛出 → `onErrorResume:213` failTask）
- **描述**：`WorkflowState.isTerminal()`（`WorkflowState.java:15-17`）存在但**没有任何一层真正执行它**：`completeTask`/`failTask` 直接按 taskId 无条件覆盖 status 与 final_output。已完成任务（DONE，`final_output` 为完整答案）在完成后的任何后续异常都会把它翻转为 FAILED，并把 `errorMessage` 写进 `final_output` 列（`failTask:101` 的语义混用）——用户的成功结果被销毁，事件日志多出一条误导性的 `TASK_FAILED`，指标多计一次 FAILED。同理，`transitionStatus` 声称的终态拦截（`:113-115`）实际只拦"任务不存在"。
- **触发条件**：任务标记 DONE 之后的同请求路径内抛出任意异常（同步路径 `success()` 组装响应失败；流式路径收尾帧序列化失败等）。不需要并发竞争即可触发。
- **影响**：持久化结果损坏且不可恢复（无版本/审计列）；任务级指标与事件溯源双双失真。
- **修复建议**：给 `completeTask` 的 SQL 追加 `AND status NOT IN ('DONE', 'FAILED')`（新增带守卫的 mapper 方法或修改现有 SQL 均不改 engine 签名）；`updateStatus` 追加 `AND status = #{from}` 使乐观守卫成立；`updated == 0` 的日志改为真实语义（"任务不存在或状态已被并发修改"）。`failTask` 的 errorMessage 建议改写入 `error_message` 类字段或事件 payload，不要占用 `final_output`。

---

## 中严重度

### M-1 `transitionStatus` 静默吞掉非法流转，且 `STATE_CHANGED` 事件记录的是"调用者声称的 from"而非数据库真实状态

- **位置**：`AgentWorkflowEngine.java:107-119`（非法转换仅 `log.warn` 后 return void；`:112` 无守卫 UPDATE 成功后，`:117-118` 的事件照发）；实例证据：`WorkflowReactAgentService.java:94-95` 配合 `mapToWorkflowState`（`:343-350`）——`MAX_STEPS = 6`（`:41`）时第 5→6 步产生 `WRITING → WRITING`，属非法转换，每次跑满步数的任务都会**静默**触发一次
- **描述**：两个复合缺陷。其一，方法返回 void，调用方（如 `DeepResearchService:40/51/80` 的三段推进）无法感知流转是否生效，状态机退化成"建议性"的；本审查发现调用方代码正因为吞掉而"碰巧能跑"。其二，由于 `updateStatus` 没有 from 守卫（见 H-2），即使 DB 当前状态与 `from` 参数不符，UPDATE 也会成功，随后发出的 `STATE_CHANGED` 事件把参数里的 `from` 当作事实记录——事件溯源在关键的状态字段上可以是假的。
- **触发条件**：任何乱序/重复的状态推进调用；或对已完成任务再次调用 transitionStatus（updated=0 只 warn）。
- **影响**：状态机违规不可观测；事件流重建的状态序列不可信。
- **修复建议**：`transitionStatus` 返回 boolean（或非法时抛 `IllegalStateException`，由 `GlobalExceptionHandler` 统一 500/400）；配合 H-2 的 from 守卫，事件仅在真实发生 CAS 式流转时发出，from 字段即为事实。

### M-2 被取消的流式任务永久停留非终态：引擎缺少取消/超时收敛能力

- **位置**：引擎侧职责面——未提供 `cancelTask`/过期清理类 API（全文无此方法）；调用方证据：`WorkflowReactAgentService.java:193-204`（`completeTask` 位于 `concatWith` 的 `defer` 中，仅流正常走完才执行）、`:206-216`（`onErrorResume` 只覆盖 **error** 信号；客户端断开是 **cancel** 信号，两个分支都不会执行）、`:218`（`doFinally` 只记指标，不落库）
- **描述**：SSE 客户端中断连接（关页、超时）时 Reactor 发出 cancel，`concatWith` 的收尾不执行、`onErrorResume` 不触发，任务永远停留在 `PLANNING`/`SEARCHING` 等非终态。引擎没有提供任何取消或过期任务回收的入口，调用方只能眼看着记录滞留——`:209-210` 的注释"failed stream does not leave the workflow record orphaned"对 cancel 场景并不成立。
- **触发条件**：流式对话中客户端断开（移动端切换网络、用户关页面，均为日常高频操作）。
- **影响**：`agent_task` 中非终态孤儿记录持续累积；`listTasks` 与统计被污染；无任何机制区分"进行中"与"已死"。
- **修复建议**：引擎新增 `cancelTask(taskId, reason)`（校验非终态后置 FAILED/CANCELLED，新增方法不改既有签名）与/或定时扫描超时非终态任务的方法（可复用 `AuditRetentionScheduler` 的模式）；调用方在 `doFinally` 的 `SignalType.CANCEL` 分支调用之。

### M-3 `listTasks` 未钳制 pageSize，且存在 N+1 查询

- **位置**：`AgentWorkflowEngine.java:200`（仅 `Math.max(1, page)` 钳了页码，pageSize 原样使用）、`:201-205`（每条任务单独调 `stepMapper.findByTaskId`）；调用方证据：`WorkflowController.java:66-69` 把用户可控的 `page`/`pageSize` 查询参数直接透传
- **描述**：`pageSize` 为 0 或负数时，`LIMIT #{limit} OFFSET #{offset}` 产生非法 SQL（MySQL 对负 LIMIT 直接报错）→ 500；`pageSize=10000` 时一次拉取全租户任务，并对每条任务追加一次 steps 查询（10000 条 → 10001 次查询），且 `toTaskVO` 把每个任务的**全部步骤**（含 thought/observation 大文本）装进列表 VO——单次请求即可造成 DB 与响应体双重放大。
- **触发条件**：任何已认证用户请求 `GET /ai/workflow/tasks?pageSize=0`、`?pageSize=-1` 或 `?pageSize=100000`。
- **影响**：参数异常 → 500；参数超大 → DB 压力与响应膨胀，是最便宜的 DoS 面。
- **修复建议**：钳制 `pageSize` 到 `[1, 100]`（或项目内已有分页常量）；列表 VO 不装 steps（新建精简 VO 或传空列表，`getTask` 详情页再取全量）；如确需保留，改单条 `WHERE task_id IN (...)` 批量查询消 N+1。不改对外签名。

### M-4 任务详情/列表暴露 `userInput`/`finalOutput`，同租户内无属主隔离，GUEST 可读他人 prompt

- **位置**：`AgentWorkflowEngine.java:226-227`（`toTaskVO` 原样回填 `userInput`/`finalOutput`）、`:189-197`/`:199-206`（隔离仅到租户级）；授权面证据：`SecurityConfiguration.java:50-75` 无 `/ai/workflow` 专属规则 → 落入 `:75` 的 `anyRequest().authenticated()`，持有 GUEST JWT（tenant=public，`AuthController.java:95` 签发）即已认证
- **描述**：游客 token 与同租户其他用户（tenant=public 的所有 principal）共享租户边界，而任务的属主信息只有可选的 `chatId`/`sessionId`，查询与 VO 都不按属主收敛：任意游客可 `GET /ai/workflow/tasks` 枚举租户内全部任务，并读取**其他游客/用户提交的 prompt 与最终答案**。对宣称多租户隔离的系统，这是租户内的横向数据暴露。
- **触发条件**：游客模式下请求 `GET /ai/workflow/tasks` 与 `/ai/workflow/tasks/{taskId}`。
- **影响**：跨用户 prompt/结果泄露；若生产租户分配不严（多个真实用户落在 public），泄露面扩大到正式用户。
- **修复建议**：引擎侧提供不带 `userInput`/`finalOutput` 的列表 VO（详情 VO 保留但由 controller 决定暴露范围），或在 `agent_task` 增加属主列（principal/chatId 已可承载）并在查询条件中追加。需要与 controller/安全配置协同，但数据收敛的源头在本类。

---

## 低严重度

### L-1 `completeStep` 找不到步骤时 thought 静默丢弃，并以 `"unknown"` 污染事件表

- **位置**：`AgentWorkflowEngine.java:82-89`（`step == null` 时 thought/action 不落库，taskId 取 `"unknown"`）；建表证据：`V10__agent_workflow_tables.sql:46-58`——`agent_event` 无外键，`task_id='unknown'` 的行会成功入库
- **描述/影响**：步骤查不到（stepId 传错、步骤已被清理）时，调用方以为完整记录了思考轨迹，实际丢失且无任何告警；"unknown" 事件行在事件表永久留存，`getTaskEvents` 按任务查不到它，成了只进不出的垃圾数据。
- **修复建议**：`step == null` 时至少 `log.warn` 带 stepId；不再写 "unknown" 事件（或引入显式的 ORPHAN 事件类型）。

### L-2 `currentState` 对未知状态一律返回 FAILED，且全项目无调用方

- **位置**：`AgentWorkflowEngine.java:121-131`
- **描述**：status 列出现未知字符串（手工修数、迁移残留）时映射成 FAILED——把"数据异常"伪装成一个合法业务状态，调用方会按失败语义处理；经 grep 核实该方法当前无任何调用方，属死代码。
- **修复建议**：未知状态返回 null 并 log.warn（与"任务不存在"区分）；或删除该方法。

### L-3 `recordStepMetrics` 无任何调用方（死代码）

- **位置**：`AgentWorkflowEngine.java:153-168`
- **描述**：步骤级延迟/计数指标方法已实现但从未接线（grep 核实 5 个调用方只调 `recordTaskMetrics`），`agent.workflow.step.*` 指标序列在生产中永远为空，运维若基于方法存在性配置看板会扑空。另外每次调用都 `Timer.builder(...).register(...)`——Micrometer 按 name+tags 缓存 meter，功能正确但写法偏重。
- **修复建议**：在 `completeStep` 内接线调用（补齐步骤级可观测性），或删除；接线时注意 status 取值需收敛（当前步骤 status 字符串由调用方自由传入，见 L-4 同源问题）。

### L-4 JSON 序列化失败静默降级，损坏数据与空数据不可区分

- **位置**：`AgentWorkflowEngine.java:281-290`（`toJson` 失败返回 `"{}"`）、`:270-279`（`parseJsonMap` 失败返回空 Map）
- **描述**：`toJson` 吞掉 `JsonProcessingException` 后写 `"{}"`，观察/输出数据被静默替换为空对象，事后无从得知丢失（对比：`emitEvent` 至少 log.error）；`parseJsonMap` 同样把解析失败呈现为空 Map。另注：`completeStep` 的 `action` 参数直通 `agent_step.action VARCHAR(64)`（V10:31），超长会以 SQL 异常炸掉整个步骤完成写入。
- **修复建议**：`toJson` 失败时 log.warn 并携带长度/类型线索（或写显式的 `{"__serialization_error":true}` 哨兵）；`action` 写库前截断或校验。

### L-5 多写操作无事务边界

- **位置**：`AgentWorkflowEngine.java:51-52`（startTask：insert + transition 两次独立写）、`:78-88`（completeStep：`completeStep` UPDATE + `updateById` 二次写 + 事件插入）
- **描述**：事件侧 best-effort 是设计选择，但任务/步骤主体自身的多写也不原子：startTask 中 transition 失败会留下永久 CREATED；completeStep 中第二次 `updateById`（回写 thought/action）失败则状态显示 COMPLETED 而思考轨迹缺失。另注 `updateById` 会把刚查出的整行非空字段全部重写一遍，存在与其他写者的丢失更新窗口。
- **修复建议**：主体写合并为单条 SQL（`completeStep` 直接带 thought/action 参数，消掉第二次写）；必要时对 startTask 加 `@Transactional`（事件插入留在事务外）。

---

## 修复优先级建议

1. **P0**：H-1（failTask 空指针，一行归一化即可）；H-2（completeTask/updateStatus 补 SQL 守卫，两处小改）。
2. **P1**：M-3（pageSize 钳制 + 消 N+1，纯引擎内修改）；M-1（transitionStatus 返回值 + from 守卫，与 H-2 同一改动面）；M-4（列表 VO 数据收敛，需与 controller 协同）；M-2（cancelTask/过期回收，涉及新方法与调度器）。
3. **P2**：L-1~L-5 随下次触碰相关代码时清理（L-2/L-3 两个死代码方法可直接决定删或接线）。

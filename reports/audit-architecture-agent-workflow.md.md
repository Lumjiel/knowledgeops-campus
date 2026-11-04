# 审查报告：docs/architecture-agent-workflow.md

- **日期**：2026-11-04
- **审查方式**：只读通读 + 把文档的每条声明（状态机边、组件职责、方法表、表结构、示例代码、API 端点）与后端源码/建表 SQL 逐一比对
- **被审对象**：`docs/architecture-agent-workflow.md`（121 行）：AgentWorkflowEngine 状态机、核心组件、数据库设计、使用示例、API 端点
- **约束遵守**：未改任何源码；仅新增本报告文件；无 git commit/push；无删除文件；无 npm install / mvn build
- **关联报告**：`reports/audit-AgentWorkflowEngine.java.md`（引擎本体深度审查，本报告 L-1 直接引用其"mapper @Update SQL 全部无条件"结论）、`reports/audit-architecture-agent-harness.md.md`（ACTION_* 事件在 harness 侧文档已覆盖）

## 协作者清单（均已核实）

| 协作者 | 用途 | 关键证据 |
| --- | --- | --- |
| `WorkflowState.java` | 状态枚举与 `canTransitionTo` | 全文（switch 全量列出） |
| `AgentWorkflowEngine.java` | 方法签名与行为 | :35-208 |
| `AgentTaskMapper.java` | 状态更新 SQL | :34-36（`updateStatus` 无状态条件）、:38-43 |
| `V10__agent_workflow_tables.sql` | 三张表实际列 | :1-57 |
| `WorkflowController.java` | API 端点 | :37,43,49,59,65 |
| `SecurityConfiguration.java` | /ai/workflow 鉴权 | 无专用规则 → anyRequest().authenticated()（:69） |
| `WorkflowReactAgentService.java` / `DeepResearchService.java` | 指标采集调用方 | :90,110,115,214 / :90,103 |
| `HarnessEventRecorder.java` | ACTION_* 事件写入同一表 | emit 调用 `workflowEngine.emitEvent` |

---

## 概述：先说做对的部分

1. **五个 API 端点全部真实存在且方法/路径正确**（WorkflowController.java:37,43,49,59,65）——`POST /ai/workflow/react/chat`、`POST .../chat/stream`、`GET /tasks/{taskId}`、`GET /tasks/{taskId}/events`、`GET /tasks` 逐条对上。
2. **使用示例代码与引擎签名逐参吻合**：`startTask` 六参（tenantId/type/userInput/modelProfile/chatId/sessionId）、`startStep` 四参、`completeStep` 十一参（含末位 `errorMessage=null`）、`transitionStatus(taskId, from, to)`、`completeTask(taskId, DONE, finalReport)`——照抄即可编译。
3. **状态机图 30 条边中 29 条与 `WorkflowState.canTransitionTo` 完全一致**，含终态不可转换（DONE/FAILED → false）。
4. **关键方法表行为描述基本准确**：startTask 创建后即内部调 `transitionStatus(CREATED→PLANNING)`（AgentWorkflowEngine.java:52）；startStep 落库状态 RUNNING；completeStep 记录输出/耗时/token；completeTask/failTask 终态处理。
5. **「emitEvent 失败不影响主流程」正确**：同步 try/catch + error 日志，异常不上抛（AgentWorkflowEngine.java:135-151）。
6. **任务级指标确有接线**：`agent.workflow.task.latency/count` 由 REACT、REACT_STREAM、DEEP_RESEARCH 三类调用方真实写入（WorkflowReactAgentService.java:90,110,115,214；DeepResearchService.java:90,103）。
7. **三张表与唯一键约束存在**：task_id/step_id/event_id 均有 UNIQUE KEY，事件表按 (task_id, event_type)、created_at 建索引，支持文档声称的按任务回放。
8. **「通用工作流引擎，不绑定特定 Agent 类型」成立**：type 由调用方自由传入，引擎无类型耦合。

---

## 中严重度

### M-1 状态机图缺少 `NEED_MORE_EVIDENCE --> FAILED` 边——失败路径不完整

**位置**：architecture-agent-workflow.md:27-28（NEED_MORE_EVIDENCE 仅有两条出边）。

**对照**：`canTransitionTo` 中 `NEED_MORE_EVIDENCE -> target == SEARCHING || target == RETRIEVING || target == FAILED`（WorkflowState.java）——从 NEED_MORE_EVIDENCE 转入 FAILED 是合法且必然存在的路径（证据不足的任务同样会失败），但 mermaid 图漏画。其余 10 个状态全部有 FAILED 出边，唯独这个证据不足态没有，恰恰破坏了图自身的「任何非终态都可失败」不变量，读者据此做失败恢复/告警覆盖分析时会漏掉一个状态。

**触发条件**：任务在 NEED_MORE_EVIDENCE 状态下失败——`failTask` 会把状态写成 FAILED（AgentWorkflowEngine.java:100-103 直接 UPDATE，不经 canTransitionTo），事件流里出现一条图上不存在的转换。

**修复建议**：图中补一行 `NEED_MORE_EVIDENCE --> FAILED`。

### M-2 「事件异步记录，不阻塞主流程」与实现不符：emitEvent 是同步内联 insert

**位置**：architecture-agent-workflow.md:43（核心组件第 3 条）、:55（方法表 emitEvent 行）。

**对照**：`emitEvent` 无 `@Async`、无线程池、无 `CompletableFuture`——在调用方线程上直接执行 `eventMapper.insert`，仅以 try/catch 吞掉持久化异常（AgentWorkflowEngine.java:135-151）。两处「异步」声明均不成立；「失败不影响主流程」成立，但「不阻塞」不成立：事件表所在 DB 的每一次抖动都直接加进状态转换与每个步骤的耗时里。该误述的实际代价是读者会：①不做事件表写入的容量规划；②在事件库变慢时去查业务代码而非 DB；③误以为 agent_event 可换远端存储而不影响主链路延迟。

**触发条件**：agent_event 表所在 MySQL 出现慢查询/锁等待——所有 ReAct/DeepResearch 请求的每一步都同步变慢。

**修复建议**：两处改为「事件写入失败不影响主流程（异常吞掉记日志）；写入本身在调用线程同步执行」；若确有意做成异步，那是引擎改造项，文档不应预支。

### M-3 「指标采集：记录每步延迟」失实——step 级指标在主代码零调用

**位置**：architecture-agent-workflow.md:44（核心组件第 4 条）。

**对照**：`recordStepMetrics`（写 `agent.workflow.step.latency/count`）在 `src/main` 下**没有任何调用方**，仅测试 `AgentWorkflowEngineTenantIsolationTest.java:84` 引用；主代码只接了任务级 `recordTaskMetrics`。即文档声称的「每步延迟」指标在生产路径上永远没有数据——基于该指标建的监控面板/告警会一直是空的。

**触发条件**：运维按文档为 `agent.workflow.step.*` 配置 Grafana 面板或告警。

**修复建议**：文档改为「任务整体延迟/计数已接入（REACT/REACT_STREAM/DEEP_RESEARCH）；step 级指标方法已备但调用方未接线」，或在引擎侧把 `recordStepMetrics` 接进 `completeStep` 后同步修文档。

### M-4 数据库设计章与实际 schema 不符：列名错误 + 关键列缺失

**位置**：architecture-agent-workflow.md:59-82（三张表的字段表）。

**对照**（V10__agent_workflow_tables.sql）：
- **agent_step**：文档写 `action_input` / `observation`，实际列名是 `action_input_json` / `observation_json`（V10:25,27）；且完全漏掉 `status`（`completeStep` 第一个参数的落点，文档示例自己传了 "COMPLETED" 却在表里找不到该列）与 `step_order`；
- **agent_task**：漏掉 `tenant_id`——**多租户系统的隔离键**（索引 `idx_agent_task_tenant_status` 之首、`getTask/listTasks` 均按 tenant 过滤），还漏 `model_profile`/`chat_id`/`session_id`/`metadata_json`（后三者恰是文档自己示例代码传给 `startTask` 的参数）；
- **agent_event**：漏 `event_id`（唯一键）、`step_id`、`created_at`。

文档表格以「字段|说明」呈现、无「主要字段」限定，读者据此写查询/迁移/对账 SQL 会直接失败（列名错）或漏掉租户过滤（tenant_id 缺失）。

**修复建议**：三张表按 V10 SQL 重写；至少把 agent_step 的列名改正、三表各补 tenant_id/event_id 等关键列，并标注「完整 DDL 以 V10 迁移为准」。

---

## 低严重度

### L-1 `transitionStatus()` 「验证合法性」的语义未写全：非法转换是静默忽略，且无 DB 级保护

**位置**：architecture-agent-workflow.md:53（方法表）。

**描述**：`transitionStatus` 对非法转换只 `log.warn` 后 return——不抛异常、不写事件、调用方从返回值（void）无从感知转换被丢弃（AgentWorkflowEngine.java:107-111）。且 `updateStatus` 的 SQL 无状态条件（`UPDATE agent_task SET status=... WHERE task_id=...`，AgentTaskMapper.java:34-36）：`updated == 0` 实际含义是「任务不存在」，代码日志却解读为「已在终态」；并发/多实例下两个转换可同时通过 `canTransitionTo` 后互相覆盖，终态保护只存在于 JVM 内的单次校验。`canTransitionTo` 的合法性校验本身存在且与图一致，但「验证」一词让读者以为有强制约束。

**触发条件**：并发步骤同时推进状态、或调用方在任务完成后仍尝试 transitionStatus。

**修复建议**：方法表该行补注「非法转换静默忽略（warn 日志），无 DB 级 CAS；终态保护仅限 JVM 内校验」，并交叉引用 `audit-AgentWorkflowEngine.java.md` 的 SQL 下钻结论。

### L-2 `agent_event` 的 event_type 列表不全：漏 `TASK_FAILED` 与 `ACTION_*` 三类

**位置**：architecture-agent-workflow.md:80（`STATE_CHANGED / STEP_STARTED / STEP_COMPLETED / TASK_COMPLETED`）。

**描述**：`failTask` 会写 `TASK_FAILED`（AgentWorkflowEngine.java:102）；`HarnessEventRecorder` 经同一 `emitEvent` 写入 `ACTION_STARTED/ACTION_COMPLETED/ACTION_FAILED`（与 :109 自己说的「共享同一个 agent_event 表」呼应，但本表却没列）。按此表过滤事件类型的消费方会漏掉全部失败与受信动作事件——失败恰是回放时最关心的。

**修复建议**：补全为 STATE_CHANGED / STEP_STARTED / STEP_COMPLETED / TASK_COMPLETED / **TASK_FAILED** / **ACTION_STARTED / ACTION_COMPLETED / ACTION_FAILED**。

### L-3 API 表未标注鉴权要求，且与 /ai/harness/** 的权限级别不同

**位置**：architecture-agent-workflow.md:112-120。

**描述**：SecurityConfiguration 无 `/ai/workflow` 专用规则，五个端点全部落入 `anyRequest().authenticated()`——**任何已认证主体（含普通业务 key）可读任意本租户任务与事件**，这与姊妹端点 `/ai/harness/**`（要求 `PERM_AGENT_TRUSTED` 或 `ROLE_ADMIN`）不同级。文档对鉴权只字未提，读者无法区分两组端点的暴露面差异。

**修复建议**：API 表加一列或加注「/ai/workflow/**：任意已认证用户（租户内隔离）；对比 /ai/harness/**：需 PERM_AGENT_TRUSTED/ADMIN」。

### L-4 `type` 列被描述为固定三值，实际是无约束自由字符串且已有第四种标签

**位置**：architecture-agent-workflow.md:64（`REACT / DEEP_RESEARCH / CUSTOM`）。

**描述**：DB 层 `type VARCHAR(32) DEFAULT 'REACT'` 无 CHECK/枚举约束；引擎与调用方也未校验。且 `recordTaskMetrics` 已出现 `REACT_STREAM` 标签（WorkflowReactAgentService.java:214），与文档的三值集不一致。作为「数据库设计」章的类型说明，读者会把它当约束。

**修复建议**：改述为「自由字符串，当前调用方使用 REACT / DEEP_RESEARCH（指标标签另有 REACT_STREAM）；CUSTOM 为预留」。

---

## 修复优先级建议

1. **M-2 / M-3** 是两条会误导运维决策的行为性误述（事件「异步」、step 指标「已接入」），先改——各一两句话。
2. **M-4** 按 V10 SQL 重写数据库设计章，列名错误是会让读者写出跑不通的 SQL 的硬伤。
3. **M-1** 补一条 mermaid 边，顺手完成。
4. 低严重度合并为一次小修：transitionStatus 语义注记（L-1）、事件类型补全（L-2）、鉴权注记（L-3）、type 列措辞（L-4）。

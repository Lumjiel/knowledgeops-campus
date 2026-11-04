# 架构分析报告：AgentWorkflowEngine 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/agent/workflow/AgentWorkflowEngine.java`（291 行）
- **核查范围**：全部 5 个注入协作者；`AgentTaskMapper` 全部 7 条 SQL 逐条下钻（含 `updateStatus`/`completeTask` 的 WHERE 条件）；`WorkflowState.canTransitionTo` 全部 10 个状态的出边表；全部 5 个调用方及其方法级消费面（`HarnessEventRecorder`/`DeepResearchService`/`WorkflowReactAgentService`/`WorkflowController`/`DeepResearchController`，grep 证实）；`recordStepMetrics`/`recordTaskMetrics` 的全库调用点（含 test）；workflow 包 12 文件清单与包级依赖方向
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-AgentWorkflowEngine.java.md`（同文件缺陷审查）、`arch-WorkflowReactAgentService.java.md`（workflow⇄harness 包级环与端口下沉方案的完整论证，本报告从引擎侧印证）、`architecture-agent-workflow.md` 审查（状态图漏边/emitEvent 同步内联/死指标的文档对照结论）

## 1. 模块定位

workflow 包（12 文件：引擎 + 3 mapper + 3 record + 3 VO + `WorkflowState` + `WorkflowReactAgentService`）的**持久化与状态中枢**。`AgentWorkflowEngine` 承担四块职责：

1. **生命周期命令**：`startTask`/`startStep`/`completeStep`/`completeTask`/`failTask`（:35-103）；
2. **状态机**：`transitionStatus`/`currentState`（:107-131），`WorkflowState.canTransitionTo` 是转换矩阵的唯一权威；
3. **事件持久化**：`emitEvent`（:135-149）——同步内联 insert，异常仅吞并记日志；
4. **读门面 + VO 组装**：`getTask`/`listTasks`/`getTaskEvents`（:189-215）+ 全部 VO 转换器（:219-268）。

**调用方 5 个，消费面清晰两分**（grep 证实）：

| 调用方 | 所在包 | 消费面 |
| --- | --- | --- |
| `DeepResearchService` | agent.research | **全生命周期**：startTask→transitionStatus×3→startStep/completeStep→completeTask/failTask——状态机矩阵（SEARCHING/RETRIEVING/WRITING…）的**唯一现实驱动者**（:35-103） |
| `WorkflowReactAgentService` | agent.workflow（同包） | 生命周期 + recordTaskMetrics |
| `HarnessEventRecorder` | agent.harness | **仅 `emitEvent` 一条边**（:44）——包级环的唯一成因之一 |
| `WorkflowController` | controller | 只读：getTask/getTaskEvents/listTasks，租户取自 `TenantContext.currentTenantId()` |
| `DeepResearchController` | controller | 只读：getTask/getTaskEvents |

即：命令面消费者是两个服务 + 一个事件转发器，查询面消费者是两个 controller——一个类同时是**写侧状态中枢**和**读侧查询门面**。

## 2. 依赖全景

### 2.1 出边（5 个注入协作者）

| 依赖 | 所在包 | 方向评价 |
| --- | --- | --- |
| `AgentTaskMapper` / `AgentStepMapper` / `AgentEventMapper` | agent.workflow（**同包自持**） | 垂直模块自持 mapper 惯例的正面样本；三条 SQL 已下钻，见 4.1 |
| `ObjectMapper` | Jackson | 全局单例，`toJson` 失败静默返回 `"{}"`（:288）、`parseJsonMap` 失败静默返回空 map（:277）——数据失真不报错 |
| `MeterRegistry` | micrometer | 4 个 Meter（step/task × latency/count） |
| `TenantContext`（静态） | security | 写入口 normalize（:41）、读入口 normalize（:190/:201/:210）——显式传参样板，与 AgentSessionService 同款正确模式 |
| `WorkflowState`/`*Record`/`*VO` | 同包 | 自包含 |

**本类的出边只有这 5 条**——不触 ChatClient、不触任何特性服务、不读 MDC、不依赖 agent.harness。这是它作为「被依赖中枢」能成立的结构前提：下游只到 mapper 为止。

### 2.2 入边与包级方向

- 5 个调用方（第 1 节表格）+ 测试（`AgentWorkflowEngineTenantIsolationTest` 直 new）。
- 包图：`controller → workflow`、`agent.research → workflow`、`agent.harness → workflow` 三条入边；workflow 的出边无一指向这些包——**除一条例外**：同包的 `WorkflowReactAgentService → agent.harness.AgentHarnessService`（arch-WorkflowReactAgentService 已证），与 `HarnessEventRecorder → workflow.AgentWorkflowEngine` 组成 **workflow⇄harness 包级环**。环的两条边分属包内不同类，Bean 图无环，Spring 不报错——长期潜伏（该报告已给端口下沉方案：harness 定义 `HarnessEventSink` 接口、workflow 放实现，引擎与 harness 均零改动即可消环）。

### 2.3 横切依赖

- 事件无租户维度：`AgentEventRecord` 不含 tenantId（builder :137-144 全字段），事件隔离完全借道 task 归属（读路径先过 `findByTenantAndTaskId` 闸再取事件，:209-211——闸在入口、子资源跟随的单点纪律，新调用方忘加闸即漏）。
- 全类零事务注解：startTask 的 insert+transitionStatus（:51-52）是两条独立 SQL 无事务包裹，中途失败留下 CREATED 悬挂任务（无补偿/清扫）。

## 3. 循环依赖评估

- **Bean 级：无环**。5 个协作者均不回指本类。
- **包级：workflow⇄harness 一处环**（见 2.2），成因、证据与修复已在 arch-WorkflowReactAgentService 3.1 完整论证，本报告补充引擎侧视角：**环上 workflow 侧的全部重量都在 `WorkflowReactAgentService` 一个类上**——引擎自身对 harness 零依赖。若未来把 `WorkflowReactAgentService` 迁出 workflow 包（它本质是 harness 的一个消费者而非状态机的一部分），环即消散，引擎与本类状态机可留在原地不动。这与端口下沉方案互为备选，后者改动更小。
- `agent.research → workflow` 单向、`controller → workflow` 单向，无其他环风险。引擎「出边全叶 + 只到 mapper」的结构使它是适合作为**被依赖底座**的类——前提是别把 harness 回边引进来。

## 4. 越层评估

### 4.1 「终态保护」声称与 SQL 现实的三层断裂（本报告核心发现）

`transitionStatus` 呈现出三层防线的外形，逐层下钻后只剩零层：

| 层 | 代码声称 | SQL/逻辑现实 |
| --- | --- | --- |
| L1 状态矩阵 | `from.canTransitionTo(to)`（:108） | 校验的是**调用方声称的 from**（参数传入），非数据库实际状态——`DeepResearchService:40` 传 `PLANNING` 是它对自己上一步调用的记忆，不是 DB 事实 |
| L2 影响行数守卫 | `updated == 0` → 日志"already in terminal state"（:114） | `updateStatus` SQL 为 `UPDATE agent_task SET status=... WHERE task_id=#{taskId}`（AgentTaskMapper:35）——**无状态条件**，`updated==0` 唯一含义是 task 不存在；一个 DONE 任务被 transition 到 WRITING 会返回 1 并照常发事件 |
| L3 SQL 状态闸 | —— | 不存在 |

结论：状态机是**纯建议性**的——它的真实约束力 = 调用方的自觉。`completeTask`/`failTask`（:94-103）则完全绕开矩阵直接无条件 UPDATE（`failTask → completeTask(FAILED, msg)`），与 `canTransitionTo` 里 `DONE, FAILED -> false`（WorkflowState:29）的吸收态声明没有任何执行点连接。这与 audit-AgentWorkflowEngine「mapper @Update 全部无条件」互相印证，本报告把它升级为**架构结论：本类对外承诺的「状态管理」实为「状态记录」**。修复（不改对外签名）：`updateStatus`/`completeTask` 的 WHERE 加 `AND status = #{expected}`（L2 变真守卫），`transitionStatus` 的 from 改为读 DB 当前态——签名不变，调用方无需改。

### 4.2 事件溯源之名与审计日志之实

`emitEvent` 事件序列（STEP_STARTED/STEP_COMPLETED/TASK_COMPLETED/TASK_FAILED/STATE_CHANGED）形似 event sourcing，实为**同步内联审计日志**：无聚合版本、无重放消费方（grep 全库无人读事件做重建，唯一消费者是 `getTaskEvents` 展示）、失败仅 log.error（:146-148）即事件静默丢失且主流程继续——「事件总线」类声明（arch 文档审查已证实不实）在本类的实现现实是 best-effort 写表。架构定性：它不是引擎的输出边界，是引擎的**日志副作用**。评估：这个定性本身没问题（审计日志是合理需求），风险在于文档与调用方若按「可靠事件」设计消费逻辑（如 HarnessEventRecorder 的 harness 观测依赖它）会有静默缺口——`HarnessEventRecorder:44` 是 harness 全部观测的唯一出口，事件 insert 失败 = 该次 harness 动作在 workflow 视角永久失踪，且无补偿。

### 4.3 读路径的租户闸单点纪律

`getTask`（:190）与 `getTaskEvents`（:209）先以 `findByTenantAndTaskId`（带 tenant 条件，AgentTaskMapper:17-19）验证归属，再取子资源（steps/events 的 SQL **无租户条件**，按 taskId 直取，:194-195/:212）。模式本身成立（闸在入口、子资源跟随），但它是**逐调用点纪律**而非结构性保证：step/event mapper 一旦被新代码直接消费（无 task 前置校验），租户边界即漏。对照 `IngestionService` 的「claim SQL 带租户条件」——本类写路径（updateStatus/completeTask/completeStep 均仅按 taskId/stepId）同样零租户条件，写侧隔离完全依赖「调用方已在上游做过校验」这一未成文的约定。记录为边界扩产提示：写路径 taskId 是服务端生成的 UUID，风险受限，但约定应成文。

### 4.4 读门面与命令中枢同体：查询侧的结构瑕疵

引擎同时是命令面（生命周期+状态机）与查询面（getTask/listTasks + 3 个 VO 组装器）。查询侧两个结构问题：

1. **N+1**：`listTasks` 对每个 task 单独 `stepMapper.findByTaskId`（:203），分页 20 条即 21 次查询——非正确性问题，但它是「读门面长在命令中枢里」的直接代价：没人会在设计命令 API 时为列表页优化查询。
2. **`currentState` 的失败语义**：status 值非法时静默映射为 `FAILED`（:129）——脏数据被翻译成合法终态，调用方无法区分「真失败」与「数据损坏」。

收敛方向（记录项）：查询面可析出为独立读服务（VO 组装 + 批量 steps 查询），引擎回归纯命令侧；现规模下不紧迫。

### 4.5 死指标与孤儿状态（低危记录项）

- `recordStepMetrics`（:153-168）main 源码零调用方，仅测试引用（AgentWorkflowEngineTenantIsolationTest:84）——**死指标方法**：`agent.workflow.step.*` 两个 Meter 在生产中永不出现，监控面板若按文档设计会面对永久空指标（arch 文档审查已记「recordStepMetrics 仅测试引用=死指标」，本报告从引擎侧确认其成因为 API 已发布但无消费者）。
- `CREATED` 状态不可观测：startTask insert（CREATED）后立即 transition 到 PLANNING（:51-52），两条 SQL 间无任何外部可见窗口——状态机矩阵的 `CREATED -> PLANNING` 边是唯一出边，实际永远被引擎内部一次性走完。外部系统若按矩阵理解 CREATED 是可驻留状态，与实现不符（轻）。

## 5. 平行实现与职责重叠

- **状态权威三处平行**：`WorkflowState.canTransitionTo`（代码矩阵）、arch 文档的状态图（审查发现漏画 FAILED 出边）、SQL 现实（无约束）。本报告 4.1 已论证代码矩阵是三者中唯一可执行的——但它是**内存矩阵而非 DB 约束**。修复顺带建议：若加 SQL 状态守卫（4.1），矩阵的唯一权威应收敛到 SQL（WHERE expected），Java 侧 canTransitionTo 降级为快速失败的前置提示。
- **事件发布双通道**：引擎 `emitEvent`（workflow 表）与 `HarnessEventRecorder`→引擎转发（harness 观测）——后者是前者的转发器而非平行实现（:44 单行委托），方向健康；但 harness 侧另有一套自己的事件/步进记录词汇（AgentObservation 等），两套词汇的映射在 HarnessEventRecorder 内完成，属可接受的防腐层。
- **VO 组装器无平行副本**：grep 证实 WorkflowTaskVO/StepVO/EventVO 的组装仅本类一处——与 ReAct 三重实现漂移（arch-WorkflowReactAgentService）形成对照，本类的 DTO 边界是干净的。
- **tenantId 处理**：全类 6 处 `TenantContext.normalize` 显式传参，无 MDC 直读——与 ReactAgentService（defer 内读 MDC）同包对照，引擎是 workflow 包内租户处理的正确样板。

## 6. 修复优先级建议（均不改对外 API 签名）

1. **P0** 状态守卫下沉 SQL：`updateStatus`/`completeTask` 加 `AND status = #{expected}`，`transitionStatus` 的 from 读 DB 实际值——三层断裂收敛为「SQL 唯一权威 + Java 前置快速失败」（4.1/5），同时消除「updated==0 → already in terminal state」的失实日志。
2. **P1** workflow⇄harness 包级环消解：端口下沉（HarnessEventSink）或迁出 `WorkflowReactAgentService`（3.1，与 arch-WorkflowReactAgentService 的方案合并裁决）。
3. **P2** startTask 双写加事务或容忍并成文（2.3）；`currentState` 区分「数据损坏」与「FAILED」（4.4）；死指标 `recordStepMetrics` 决策——接线或删除（4.5）。
4. **P2 记录项**：事件审计日志的定性成文（best-effort，非可靠事件）；读门面析出与 listTasks N+1；写路径零租户条件的调用方纪律成文（4.3）。

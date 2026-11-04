# 架构分析报告：architecture-agent-workflow.md 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码与文档；不运行任何 build/mvn；全部结论来自静态交叉审查，每条结论附文档行号与代码位置证据）
- **分析对象**：`docs/architecture-agent-workflow.md`（120 行：状态机 mermaid :1-33 + 核心组件/关键方法 :35-55 + 数据库设计 :57-82 + 使用示例 :83-110 + API 端点 :112-120）
- **核查范围**：文档「依赖面」五节逐条对码——mermaid 21 条边逐边对 `WorkflowState.canTransitionTo()`（:19-31）；方法表 6 行对 `AgentWorkflowEngine.java`（291 行全读）；三表字段对 `V10__agent_workflow_tables.sql` 与三个 Record 类；示例代码 5 次调用逐参对方法签名；5 端点对 `WorkflowController.java` 全文 + `SecurityConfiguration.java:48-75` 权限规则；「异步」声明对 workflow 包全量 grep（@Async/Executor/ThreadPool 零命中）；引擎全部调用方 grep（harness/research/workflow 三包 8 个消费点）；type 枚举对全部 `startTask` 写入值；`recordStepMetrics/recordTaskMetrics` 调用方 grep
- **约束遵守**：未改源码与文档、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（所有修复建议均为文档侧编辑）
- **关联报告**：`audit-architecture-agent-workflow.md.md`（同文件缺陷审查：图漏边、异步不实、字段表列名漂移等的一手记录——本报告从边界角度归位不重审）；`arch-AgentWorkflowEngine.java.md`（终态保护三层断裂、emitEvent best-effort 定性、读路径闸在入口——本报告多条结论的代码侧主证）；`arch-WorkflowReactAgentService.java.md`/`arch-ReactAgentService.java.md`（workflow⇄harness 包级环两侧证据、端口下沉方案、service⇄workflow 三角环）；`arch-architecture-agent-harness.md.md`（共享 `agent_event` 表的姊妹文档，事件契约互补面）

## 1. 模块定位

本文档是**工作流引擎的状态机与数据契约文档**：交付四类依赖契约——①状态转换图（:7-33），②引擎方法表（:48-54），③三张表的数据契约（:59-82），④API 面（:112-120）加一段示例代码（:85-110）。

**与姊妹文档（architecture-agent-harness.md）的分工**：本文档声称 `AgentWorkflowEngine` 是「通用工作流引擎，不绑定特定 Agent 类型」（:39）。对**引擎类**这个声明成立——`AgentWorkflowEngine` 出边全叶（3 个自持 mapper + ObjectMapper + MeterRegistry，:27-31），确实不依赖任何具体 Agent。但对**所在包**不成立：`agent.workflow` 包 1098 行里 `WorkflowReactAgentService` 独占 499 行（46%），绑定 ReAct、ModelRouter、TenantCostService 与 harness——「通用性」是类级事实被写成了包级声明（见 3.2）。

**漂移分布**：本文档的问题模式与 harness 姊妹篇相反——示例代码与 API 表全对（正面），漂移集中在**行为语义层**：两处核心职责声明（「异步记录」:43/:55、「验证合法性」:53）与实现强度错配（4.1/4.2），数据契约层漏隔离键与 4 种真实事件类型（4.3）。

## 2. 依赖全景

### 2.1 出边（文档对代码的声称清单）

| 文档声称 | 位置 | 核实结果 |
| --- | --- | --- |
| mermaid 21 条状态边（:7-33） | WorkflowState.canTransitionTo:19-31 | ⚠️ 20/21 精确一致；漏 1 条：**NEED_MORE_EVIDENCE → FAILED**（代码 :27 允许，图 :25-28 只画了 →SEARCHING/→RETRIEVING）——全文档与代码对齐度最高的一节仍缺一角（4.5） |
| `startTask()` 状态 CREATED → PLANNING（:50） | AgentWorkflowEngine.java:51-52（insert + 立即 transition） | ✅ 精确（含一个未声明的推论：任务在 CREATED 态零驻留，外部观察者永远见不到 CREATED） |
| `startStep()`/`completeStep()`（:51-52） | AgentWorkflowEngine.java:56-92 | ✅ 职责描述属实；completeStep 实为**两段写**：completeStep SQL（:78-80）+ 条件回查 updateById 补 thought/action（:82-88），非原子——doc 未声明（4.4） |
| `transitionStatus()` 「状态转换，**验证合法性**，emit 事件」（:53） | AgentWorkflowEngine.java:107-119 + AgentTaskMapper.java:35-36 | ❌ 强度错配：验证的是**调用方声称的 from** 而非 DB 实际状态；`updateStatus` SQL 仅 `WHERE task_id` 无状态条件；`updated==0` 的唯一含义是 task 不存在，却打出 "already in terminal state" 失实日志（:113-115）；`failTask`/`completeTask` 完全绕开矩阵直接无条件 UPDATE（:94-103）。引擎对外承诺的「状态管理」实为「状态记录」（4.2） |
| `emitEvent()` 「**异步**写入事件，失败不影响主流程」（:43/:55） | AgentWorkflowEngine.java:135-148 + 包内全量 grep | ❌ 前半句不实：同步内联 insert，包内无任何 @Async/线程池设施；后半句属实（整体 try/catch 吞异常仅 log.error）。「失败不影响主流程」的正确表述恰恰掩盖了「同步 insert 在主流程线程内」（4.1） |
| 「指标采集：Micrometer Timer/Counter 记录每步延迟和任务整体延迟」（:44） | AgentWorkflowEngine.java:153-185 + 调用方 grep | ⚠️ 半实：`recordTaskMetrics` 有 4 处真实调用（DeepResearchService:90/:103、WorkflowReactAgentService:90/:110/:115/:214）；`recordStepMetrics` **main 源码零调用**（仅 AgentWorkflowEngineTenantIsolationTest:84）——文档声称的「每步延迟」是死指标；且计时不在引擎而在调用方手写 elapsedMs（4.4） |
| agent_task 字段表 5 列 + type=「REACT / DEEP_RESEARCH / CUSTOM」（:59-67） | V10 DDL:1-19 + 全部 startTask 写入值 | ❌ 漏 **tenant_id**（DDL:4，隔离键）与 model_profile/chat_id/session_id/metadata_json/时间戳 6 列；type 三值两错：实际写入 **REACT**（WorkflowReactAgentService:56）、**REACT_STREAM**（:130，流式端点真实写入的值，doc 漏）、DEEP_RESEARCH（DeepResearchService:35）；**CUSTOM 零写入者**（grep 证实，纯虚构/预留）——type 是 64 长自由字符串无 CHECK，枚举仅存在于文档（4.3） |
| agent_step 字段表（:68-75） | V10 DDL:21-44 + AgentStepRecord | ❌ 列名漂移：doc 写 `action_input`/`observation`，实际是 **action_input_json**/observation_json（DDL:32-33），且另有同型的 input_json/output_json 两列 doc 全漏；漏 status/step_order/error_message/started_at/ended_at/model_profile；**agent_step 无 tenant_id 列**（DDL 全表证实）——租户隔离完全靠 task 闸下推（4.3/4.6） |
| agent_event 字段表 + event_type=「STATE_CHANGED / STEP_STARTED / STEP_COMPLETED / TASK_COMPLETED」（:77-82） | AgentWorkflowEngine.java:90/:97/:102/:117/:69 + HarnessEventRecorder.java:22/:34 | ❌ 事件类型漏 4 种真实值：**TASK_FAILED**（engine:102）+ harness 侧 **ACTION_STARTED / ACTION_COMPLETED / ACTION_FAILED**（HarnessEventRecorder 经同一 emitEvent 写同一张表——姊妹文档 :102 声明的契约，本文档的表契约却不认识它）；列面漏 event_id/task_id/step_id 与 tenant_id（该表亦无租户列）（4.3） |
| 使用示例 5 次调用（:85-110） | AgentWorkflowEngine.java:35/:56/:73/:107/:94 | ✅ **逐参全对**：startTask 6 参、startStep 4 参、completeStep 11 参（含 11 位签名与参数序）、transitionStatus/completeTask 形参类型全对——与 audit 系列先例一致，示例正确是加分项（5.3） |
| API 端点 5 条（:112-120） | WorkflowController.java:37/:43/:49/:59/:65 | ✅ 全部真实存在、路径与方法全对（对照 arch-HybridRetrieval 的虚构端点先例）；但**零权限说明**：`/ai/workflow/**` 在 SecurityConfiguration 无专用规则，落入 `anyRequest().authenticated()`（:75）——POST `/ai/workflow/react/chat` 触发完整 ReAct 循环（LLM+harness 工具+计费）却仅需认证，而姊妹路由 `/ai/react/chat` 需 PERM_CHAT_WRITE（SecurityConfiguration:58）——**同一能力两条路由权限不对称，本文档这条更弱**（4.7） |

### 2.2 入边（谁依赖文档所描述的引擎）

- **业务生产方 2 个**：`agent.research.DeepResearchService`（PLANNING→SEARCHING→RETRIEVING→WRITING→DONE 全链，:35-103——状态图 :7-33 的边大部分只被这一个消费者行使）与 `agent.workflow.WorkflowReactAgentService`（REACT/REACT_STREAM 两链路）。ReAct 链路实际只走 CREATED→PLANNING→（DONE|FAILED）三态（WorkflowReactAgentService:56-115：无任何中间 transitionStatus 调用）——状态机的 10 态 21 边中，**ReAct 主流量只行使 2 条边**，状态机复杂度的真实偿付方是 DeepResearch 一条链路；
- **观测消费方 2 个**：`controller.WorkflowController`（getTask/getTaskEvents/listTasks，:51/:61/:69）与 `controller.DeepResearchController`（:43/:53/:59，另一组任务/事件读取端点）——后者在文档 API 表完全缺席（4.7）；
- **跨子系统回边 1 条**：`agent.harness.HarnessEventRecorder.java:44` → `workflowEngine.emitEvent`——harness 借引擎写审计事件，构成 workflow⇄harness 包级环的另一半（3.1）。

### 2.3 隐式依赖：两张表一条契约

本文档与 harness 文档共享 `agent_event` 表与 `emitEvent` 入口：workflow 侧写 STEP_*/STATE_CHANGED/TASK_*，harness 侧写 ACTION_*。任何一侧修改事件载荷结构（如 HarnessPayloadSanitizer 的 payload keys）都会改变另一侧声明的事件契约，而两份文档互不引用、各自维护事件类型清单且**清单互不一致**（本文档漏 ACTION_*，harness 文档不列 STEP_*/TASK_*）——同一条数据契约的两个平行半份描述（5.2）。

## 3. 循环依赖评估

### 3.1 workflow⇄harness 包级环（已知，本文档增量）

引擎自身零依赖 harness（Bean 级无环，出边全叶）——环的 workflow 侧重量全部在 `WorkflowReactAgentService`（:3-4 import harness）与 harness 侧 `HarnessEventRecorder`（:3 import workflow）。消环方案（端口下沉或迁出该服务）已在三份 arch 报告定型。本文档增量责任：:39「不绑定特定 Agent 类型」的声明恰恰把环边所在的类隐藏在「通用引擎」叙事之下——读者从本文档无从得知 workflow 包内存在一条指向 harness 的回边。修复环时本文档 :39 需同步改写为「引擎类通用；ReAct 适配服务绑定 harness，见 3.x」。

### 3.2 service⇄workflow 包级三角环

`workflow.WorkflowReactAgentService.java:13` → `service.TenantCostService`（workflow→service 边），闭合 `service.ReactAgentService` → harness → workflow 的三角（arch-ReactAgentService 已定位 service→harness 边唯一来源）。引擎类不在环上——两处「通用引擎」叙事均不被环污染，环全部由包内的 ReAct 适配层承担。

### 3.3 排除性结论（正面）

- 引擎 → 3 个自持 mapper 垂直自持，符合本项目「垂直模块自持 mapper」惯例；无任何对 controller/service/rag/tools 的出边；
- 状态图自身无环（DAG 除终态汇点），NEED_MORE_EVIDENCE→SEARCHING/RETRIEVING 的回路是设计内的重试环且与代码一致；
- 文档引用无环、无对外部文档的依赖声明。

## 4. 越层评估

### 4.1 「异步记录，不阻塞主流程」是全文档最重的失实声明（核心）

:43（事件溯源）与 :55（emitEvent 方法行）两处声明异步。实现是**同步内联 insert**（:135-148），workflow 包零异步设施（grep 证实）。这条失实有两个独立后果：①性能语义反了——每次 emitEvent 是主流程线程内的一次 DB 往返，ReAct 每步至少 2-3 次事件 + harness 每动作 2 次，全部串行计费在请求延迟里，「不阻塞」恰是它做的事；②容量语义反了——事件写入没有队列缓冲，DB 抖动直接传导到 LLM 循环（只是被 catch 吞掉不抛出）。「失败不影响主流程」的真实保障范围仅是**不抛异常**，不是**不阻塞**。修复位：:43/:55 改为「同步写入、失败仅记录日志不中断执行；事件写入参与请求延迟」；若要真异步须动代码（@Async 或队列），超出文档修复范围，可作 backlog。

### 4.2 「验证合法性」的承诺强度与实现错位

:53 把「验证合法性」列为 transitionStatus 的职责，:39 把「状态管理」列为引擎核心职责。实现的三层实况（arch-AgentWorkflowEngine 主证，本报告归位为文档责任）：canTransitionTo 校验的是调用方**声称**的 from 而非 DB 实际状态；`updateStatus` SQL 无状态闸（AgentTaskMapper:35-36）；failTask/completeTask 两个终态写路径完全绕开矩阵（:94-103），`DONE→FAILED` 这类图上不存在的转换会真实出现在事件流。文档读者会以为引擎保证状态机不变量，实际保证的只是「非法转换会打一条 warn 日志后照单全收或静默 return」。修复位：:53 与 :39 措辞降级为「按调用方声称的状态做转换校验（内存矩阵），终态写路径不经校验」；图 :31-32 的 `FAILED --> [*]` 旁注「failTask 可从任意状态直达 FAILED，不经矩阵」。

### 4.3 数据契约三表漏隔离键与 4 种真实事件类型

三表字段表（:59-82）合计列出 9 列，实际 3 表共 36 列——选择性简化可以接受，但漏掉的三类不是细节：①**tenant_id**（agent_task 唯一隔离键，DDL:4）与 agent_step/agent_event **无租户列**的结构事实——隔离模型「task 闸 + 子表按 task_id 跟随」是读路径安全的核心设计，文档只字未提；②事件类型清单缺 TASK_FAILED 与 ACTION_*×3（2.1 表末行）——其中 ACTION_* 是受信动作（文件/shell/外部调用）的审计痕迹，恰是这张表最有安全价值的行；③列名 action_input vs 实际 action_input_json——按本文档写查询的开发者第一次就会撞列名错误。type 枚举漏 REACT_STREAM（流式端点真实写入值）并虚构 CUSTOM。修复位：字段表补 tenant_id 与隔离模型一句；事件类型清单补全 8 种或改「至少包括」句式；列名对齐 DDL；type 枚举改实际三值。

### 4.4 引擎职责表与真实职责的四处出入

- **指标采集**（:44）：step 级指标死（recordStepMetrics 零 main 调用方），task 级计时在调用方（DeepResearchService/WorkflowReactAgentService 各自 nanoTime+elapsedMs，4 处手写副本）——引擎提供的是记数方法不是采集机制，与「Micrometer…记录每步延迟」的表述有主语错位；
- **completeStep 两段写**（:78-88）：completeStep SQL 与条件 updateById 非原子，中间失败会留下有 output 无 thought 的半步——「完成步骤，记录输出、耗时、token」未覆盖 thought/action 的第二写路径；
- **查询 API 缺席**（:48-54 方法表）：currentState/getTask/listTasks/getTaskEvents 四个读方法未列入方法表，而 API 端点一节（:112-120）全靠它们——方法表只写了写路径半边；
- **step status 无枚举**：completeStep 的 status 是自由字符串（示例传 "COMPLETED" 字面量，:99），与 agent_task status 的 WorkflowState 枚举双轨——同表内两套状态纪律，文档未声明。

### 4.5 状态图缺 1 条边 + 1 条「图外真实转换」未注记

- **NEED_MORE_EVIDENCE → FAILED**（canTransitionTo:27 允许，图 :25-28 未画）——唯一漏边，触发条件是反思环节失败且调用方按矩阵走 transitionStatus；
- **任意状态 → FAILED 经 failTask**（:100-103 绕矩阵）：DeepResearchService:102 与 WorkflowReactAgentService:114/:213 的 catch 块都走此路径——包括 CREATED→FAILED、DONE→FAILED 这类图上语义不可能的边。修复位：图补第一条边；第二条加注记（与 4.2 合并处理）。

### 4.6 读路径的租户闸是入口纪律而非结构保证

getTask/getTaskEvents 先 `findByTenantAndTaskId` 闸 task（:190/:209），listTasks 走 `findByTenant`（:201）——但闸后的 steps/events 查询按 task_id 直取、无租户条件（AgentStepMapper:14、AgentEventMapper:13）。「闸在入口、子资源跟随」是逐调用点纪律（arch-AgentWorkflowEngine 已记），任何新增读方法忘记先闸 task 即穿透租户边界；listTasks 还存在逐 task 回查 steps 的 N+1（:202-204）。文档 :120「租户任务列表」隐含了隔离承诺却未声明其实现模型。修复位：数据契约节补隔离模型一句（与 4.3 的 tenant_id 补全同一批编辑）。

### 4.7 API 面的两处结构性遗漏

- **权限不对称**（2.1 末行详述）：/ai/workflow/react/chat 仅 authenticated，姊妹路由 /ai/react/chat 需 PERM_CHAT_WRITE——文档 API 表无任何权限列，读者无从比较两条等能力路由的门槛差。修复位：API 表补权限列并标注不对称（安全修复属代码侧 backlog：给 /ai/workflow/** 补专用规则，本报告仅记录）；
- **平行读取面**：DeepResearchController（:43/:53/:59）经同一引擎暴露同构的任务/事件查询——API 表只写 workflow 前缀五端点，「任务详情/事件流」实际有两组入口，事件流消费方的完整清单缺一半。

### 4.8 存量正面项（无修复）

- 示例代码五次调用逐参全对（2.1）——系列第三份「示例正确」的文档；
- 5 端点全部真实、方法/路径全对；
- 「失败不影响主流程」的异常安全半句属实（4.1 仅为性能/容量语义失实）；
- 引擎出边全叶 + mapper 自持 + 读路径租户闸入口齐全 + AgentWorkflowEngineTenantIsolationTest 固化隔离行为——「适合做被依赖底座」的结构前提完整。

## 5. 平行实现

1. **状态权威三处平行**（本系列已定型，本文档添第四处）：Java 矩阵（canTransitionTo，唯一可执行）、SQL 零约束（updateStatus/completeTask）、本文档 mermaid 图（**已漂移副本**：漏 NEED_MORE_EVIDENCE→FAILED，且对 failTask 的图外转换沉默）——四份「状态机」中两份可执行两份静态，静态的两份已互不一致；
2. **事件类型枚举的两个平行半份**：本文档（4 种）与 harness 文档（ACTION_* 3 种）各自维护同一张 agent_event 的类型清单，合并仍漏 TASK_FAILED——8 种真实值的单一清单不存在于任何文档；
3. **elapsedMs 计时副本族第 5/6 处**：WorkflowReactAgentService（多处）与 DeepResearchService 各自手写 nanoTime 计时喂 recordTaskMetrics——与 harness 包 4 处副本同型，指标计时逻辑无单点；
4. **type 值域双轨**：文档枚举（REACT/DEEP_RESEARCH/CUSTOM）vs 写入实况（REACT/REACT_STREAM/DEEP_RESEARCH）——枚举无 CHECK 无共享常量，两份清单已漂移；
5. **引用存量结论（不重审）**：parseDecision/fallbackDecision 三重实现、react.stream.* Meter 同名合并、ChatClient 无 @Primary 绑定、private 副本 parseDecision 与 ReactDecisionParser 漂移——均在 workflow 包内但主证在 arch-WorkflowReactAgentService，本报告不展开。

## 6. 修复优先级建议（均为文档侧编辑，不改任何对外 API 签名；改动仅限本文件）

1. **P1** 事件语义重写（4.1）：:43/:55 「异步」改「同步写入、失败仅记日志；事件写入参与请求延迟」——这是对引擎性能与容量语义的方向性误导；
2. **P1** 状态管理承诺降级（4.2/4.5）：:53/:39 措辞对齐「校验调用方声称的状态、终态路径不经校验」，图补 NEED_MORE_EVIDENCE→FAILED 边并注记 failTask 图外转换；
3. **P2** 数据契约补全（4.3/4.6）：三表补 tenant_id（含 agent_step/agent_event 无租户列的隔离模型声明）、列名对齐 DDL、事件类型补全 8 种（或与 harness 文档约定单一清单互引）、type 枚举改实际三值；
4. **P2** API 表补权限列并标注 /ai/workflow 与 /ai/react 的门槛不对称（4.7）；补 DeepResearchController 平行读取面（2.2/4.7）；
5. **记录项**：方法表补 4 个读方法与 recordStepMetrics 死指标标注（4.4）；completeStep 两段写与 step status 自由字符串声明（4.4）；:39 「不绑定特定 Agent」改类级表述并在消环手术时同步（3.1）；与 harness 文档约定 agent_event 契约的单点归属（2.3/5.2）。

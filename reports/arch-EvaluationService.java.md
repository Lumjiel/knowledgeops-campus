# 架构分析报告：EvaluationService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/evaluation/EvaluationService.java`（473 行）
- **核查范围**：该类全部出边（4 个同包 mapper 的 SQL 逐段下钻、`HybridRagAnswerService` 的依赖清单及其全库消费方、scorer/renderer 纯度）与全部入边（grep 全库）；`evaluation` 包 13 个文件的全景与全库 mapper 分布惯例（`@Mapper` 注解类按包统计）；controller 九端点与租户来源；evaluation 包内异步设施 grep（@Async/@Scheduled/线程池）
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-EvaluationService.java.md`（同文件缺陷审查，status 语义/会话污染类发现以该报告为准）、`audit-eval_live_runner.py.md` / `audit-eval_demo.py.md`（采集侧视角）、`architecture-hybrid-retrieval.md` 审查记录（HybridRagAnswerService 无 HTTP 暴露的结论来源）

## 1. 模块定位

该类是 RAG 评测子系统的用例编排器（evaluation 包），管理数据集→用例→运行→结果四级对象：建库（`createDataset` :47）、触发运行（`triggerRun` :104）、读运行（`getRun` :158）、基线标记（`markBaseline` :164）、对比（`compareLatest` :174）、报告导出（`exportReport` :201）。

**evaluation 是全库模块凝聚力的正面样板**：13 个文件构成自包含垂直切片——4 个 mapper + 4 个 record + scorer + renderer + `vo` 子包 + controller 全部同居一包，出界依赖只有一个能力服务。本库的 mapper 分布惯例本就是"垂直模块自持 mapper"（agent/workflow 3 个、graph 3 个、memory 2 个、evaluation 4 个，`mapper/` 中央包只放横切域实体），evaluation 完全符合该惯例而非特例。

它有两组值得记录的"唯一关系"：

1. **唯一调用方**：`EvaluationController`（`/ai/evaluation`，9 端点，`EvaluationController.java:26`）——含两个平行的触发入口（路径参数版 :35-40 与契约体版 :42-48）。
2. **`HybridRagAnswerService` 的唯一消费者**：grep 全库证实 rag 包该服务的调用方仅本类一处（该服务自身无 HTTP 暴露，见 architecture-hybrid-retrieval 审查记录）。即 evaluation ⇄ rag 构成"单消费者 ⇄ 单提供者"的封闭配对——两者可以作为一个整体单元移动、测试甚至合并，互不影响第三方。

## 2. 依赖全景

### 2.1 出边（注入的 8 个协作者，:38-45）

| 依赖 | 所在包 | 该协作者自身的依赖（已下钻核实） | 方向评价 |
| --- | --- | --- | --- |
| `EvalDatasetMapper` / `EvalCaseMapper` / `EvalRunMapper` / `EvalResultMapper` | evaluation（同包） | MyBatis-Plus `BaseMapper` + 租户过滤注解 SQL（见 2.1.1） | ✅ 干净下游 |
| `HybridRagAnswerService` | rag | retrieval.*（HybridRetrieval/Citation/EvidenceJudge）、llm.ModelRouter、**service.TenantCostService**、security、RagProperties | ✅ 向下；rag→service 边即全库已知的 TenantCostService 消费家族一员 |
| `EvaluationScorer` | evaluation（同包） | **零 iqk 依赖**（纯打分函数） | ✅ 纯域逻辑 |
| `EvaluationReportRenderer` | evaluation（同包） | 仅 evaluation.vo 三个 VO | ✅ 纯渲染 |
| `ObjectMapper` / `TenantContext` / `ConversationIdHelper` | 基础设施/security/util | 叶子 | ✅ |

**2.1.1 mapper SQL 下钻结论**：四个 mapper 的自定义 `@Select`（含 `findByTenantAndDatasetId`、`findByTenantAndRunId`、`findRecentByDatasetId` 等）逐段核实**全部带 `tenant_id = #{tenantId}` 过滤**。唯一不带租户条件的调用是 `BaseMapper` 泛型方法：`insert`（:69/:79/:135/:267，租户在 record 字段里，安全）与 `updateById`（:153，按自增主键更新、无租户条件——record 来自同一请求刚插入的实例，当前安全，属风格问题见 4.4）。

### 2.2 入边

- `EvaluationController.java:26` —— 唯一生产调用方（9 端点）。
- 无任何其他包引用本类；`HybridRagAnswerService` 不回指 evaluation（其 import 清单核实）。
- 外部脚本（eval_*.py）经 HTTP 进入 controller，不构成类级入边。

### 2.3 横切依赖

- `TenantContext.normalize`：本类不直读 MDC，tenantId 全部显式传参、入口统一 normalize（:58/:98/:105/:159/:165/:175/:201）——controller 每端点 `TenantContext.currentTenantId()`（同步请求线程内读取）。与 `AgentSessionService` 同为租户传递的正确样板（对照 arch-ReactAgentService 4.1 的 defer 内读 MDC 反例）。
- 无 Meter、无事件、无 MDC 直读、无线程池。

## 3. 循环依赖评估

**结论：不可能成环。**

```
EvaluationController ─→ EvaluationService ─→ 4×EvalMapper（同包叶子）
                                         ─→ HybridRagAnswerService ─→ retrieval.* / ModelRouter / TenantCostService
                                         ─→ EvaluationScorer / EvaluationReportRenderer（纯叶子）
```

- 类级：出边全部向下，rag 的依赖清单不回指 evaluation，线性无环；
- 包级：evaluation 对外仅贡献一条边（evaluation → rag，经 service.TenantCostService 二跳到 service 包），不参与 service⇄harness⇄workflow 三角环；
- Bean 级：controller→service→(mapper|rag|scorer|renderer) 单向树，构造器注入无循环风险；
- **单消费者配对的额外红利**：evaluation⇄rag 封闭配对意味着即便未来 rag 包重构（如 HybridRetrievalService 接口化），影响面可静态判定为"仅本类"。

## 4. 越层调用与边界问题

### 4.1 triggerRun 在 HTTP 请求线程内同步内联执行分钟级 LLM 批处理——中（本报告最重要的结构发现）

`triggerRun` 的执行模型：插入 RUNNING 行（:135）→ **for 循环逐 case 内联跑完整 RAG+LLM 链路**（:138-140，每 case 经 `HybridRagAnswerService.answer` 触发检索+生成，分钟级耗时，见 eval 脚本审查记录）→ 汇总 → UPDATE 终态（:153）。grep 证实 evaluation 包内**无任何 @Async/@Scheduled/线程池/队列设施**。

架构含义：

1. **与 agent/workflow 并存的第二套 LLM 编排模型**：同库对"多步 LLM 批量工作"已有事件化基础设施（`AgentWorkflowEngine` 的 task/step/event 三表 + 状态机），evaluation 完全绕开它自建内联循环——两条编排路线无共享执行底座。这不是缺陷而是未整合；但后果实打实：HTTP 客户端超时即运行"蒸发"，且 **run 行无 catch 保护地停在 RUNNING**——循环中途进程退出/请求中断，终态 UPDATE（:143-153）永不执行，`findRecentByDatasetId` 会把僵尸 RUNNING 当作 `compareLatest` 的 current（:177-180）。
2. **单 case 故障隔离是做对的**：`runCase` 逐 case 捕获 RuntimeException（:234-237），单个 LLM 失败不毁整个 run——这是内联模型里正确的防御粒度。
3. **修复方向**（不改对外签名）：最小改法是复用 workflow 的任务表模型（run=task、case=step）异步化 + 终态兜底；轻量改法是 run 终态加"启动时间超时视为 FAILED"的读时修正。选择取决于产品对实时回显（当前同步返回 `EvalRunVO`）的依赖程度——契约脚本（eval_live_runner）按同步响应设计，异步化需连带采集脚本适配。

### 4.2 run 终态语义：`SUCCESS` 只表示"循环跑完了"——低（记录性，指向缺陷域）

循环正常走完即 `run.setStatus("SUCCESS")`（:143），与 case 通过率无关（全部 FAILED 仍 SUCCESS）——与 eval 脚本审查确立的"假绿灯"发现同源。架构视角的补充：`EvalRunVO.status` 是本模块对外契约的一部分，契约脚本与人工排障都消费它；语义修正（如引入 COMPLETED_WITH_FAILURES）属对外契约变更，需与采集端协同，故在本报告仅记录并指向 `audit-EvaluationService.java.md` / `audit-eval_live_runner.py.md`。

### 4.3 评测覆盖面：仅 RAG 链路，agent 链路无评测入口——低（边界空白，记录性）

evaluation 直连 `HybridRagAnswerService`（:220），即评测体系只度量"检索→引用→生成"这条链。平台的另两条 LLM 链路——`ReactAgentService`（/ai/react）与 `WorkflowReactAgentService`（/ai/workflow/react）——没有任何评测入口（grep 证实 evaluation 包不引用 agent.* 或 service 包的 ReAct 类）。同时评测也不经 `AgentHarnessService`，运行不留 harness 事件。作为平台级质量门禁，这是能力空白而非代码缺陷；若未来补 agent 评测，`AgentAction`/`AgentObservation` 的结构（actionInput 可携带 case、observation 可回填评分）天然适配 harness 通道，届时 evaluation→harness 是新增向下边，不引入环。

### 4.4 `BaseMapper` 泛型方法与租户过滤 SQL 双风格并存——低（惯例风险）

自定义 SQL 全租户过滤（2.1.1），但 `updateById`（:153）按主键更新、无租户条件。当前唯一调用点是同请求刚插入的 record，安全；风险形态是惯例性的：mapper 是包内公开接口，未来若有人用 `selectById`/`updateById` 做"按主键快捷读写"，就会绕过租户边界（MyBatis-Plus 的 TenantLineInnerInterceptor 未启用——全库 grep 无该拦截器配置）。**修复方向**：包内约定"泛型方法仅限 insert 与同请求回写"，或给三个非 insert 场景补租户条件 SQL；一行 javadoc 声明亦可达防回归效果。

### 4.5 无越层调用

不读传输层状态、不直拼 SQL、不依赖 controller 层类型；对 rag 的调用是 service→capability 的合法向下边。无越层调用。

## 5. 同层平行实现与隐性耦合

### 5.1 双触发端点共享同一 service 方法——可接受

`POST /datasets/{datasetId}/runs`（controller :35-40）与 `POST /runs`（:42-48，datasetId 在契约体内）都落到 `triggerRun`，后者为 CI 契约脚本设计（对齐 eval 契约夹具的调用形态）。两入口一实现，无逻辑复制，仅路由层冗余；记录性。

### 5.2 `resolveChatId` 的三级回退链含一个隐式跨模块契约——低

case 自带 chatId（:371-373）→ request 前缀（:374-376）→ datasetId 兜底（:377）。兜底意味着同 dataset 的所有 case 共享一个 chatId，经 `ConversationIdHelper.build("eval", chatId)`（:224）进入 `MysqlChatMemory` 持久化会话——跨 run 的对话记忆污染路径（eval 脚本审查已确认其对回归分数的影响，属缺陷域）。架构视角记录：**评测链路复用了面向对话的生产会话存储，而非一次性内存态**，这是 evaluation→memory 域的一条隐性运行时耦合（无代码依赖，经 ChatMemory advisor 间接成立）。

### 5.3 scorer/renderer 纯度是本模块的可测试性红利——正面记录

`EvaluationScorer` 零 iqk 依赖、`EvaluationReportRenderer` 仅依赖 vo——打分与渲染可脱离 Spring/DB 单测。这是"编排器薄、域逻辑纯"切片的正确形态，未来改动应保持这条线（勿在 scorer 里引入 mapper 或 Spring 类型）。

## 6. 结论与建议优先级

**总评**：依赖方向无可挑剔——垂直切片自包含、mapper SQL 全租户过滤、租户显式传参、单消费者封闭配对、域逻辑纯度高，是全库模块边界的正面参照。结构性风险集中在一处：**执行模型**（同步内联批处理，无异步设施、无僵尸 RUNNING 兜底、与 workflow 引擎双轨并存），其余为惯例级记录项。

| 优先级 | 事项 | 章节 | 改动范围 |
| --- | --- | --- | --- |
| 1 | triggerRun 异步化或至少 RUNNING 僵尸兜底（超时读时修正 / 终态 catch-all） | 4.1 | 本类 + 可选复用 workflow 任务表 |
| 2 | 双风格统一：泛型方法使用约定或补租户 SQL | 4.4 | evaluation 包内 |
| 3 | run 终态语义修正（需与采集脚本协同，属契约变更） | 4.2 | 跨模块协同，暂缓 |
| 4 | agent 链路评测入口（能力空白，待产品决策） | 4.3 | 新增，不引环 |
| 5 | chatId 兜底链与会话存储耦合——指向缺陷域修复 | 5.2 | 见 audit-EvaluationService |

以上各项均不改变任何对外 API 签名；第 1 项若选异步化会改变 `triggerRun` 的响应语义（同步返回终态 → 返回受理态），需先与契约脚本对齐——故表中同时给出不改语义的僵尸兜底替代方案。

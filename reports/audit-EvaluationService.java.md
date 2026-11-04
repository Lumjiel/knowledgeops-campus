# 缺陷审查报告：EvaluationService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/evaluation/EvaluationService.java`（473 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——4 个 Mapper（`EvalDatasetMapper` / `EvalCaseMapper` / `EvalRunMapper` / `EvalResultMapper`，含全部 SQL）、`EvaluationScorer`（全文）、`EvaluationController`（全文，确认两个触发入口）、`HybridRagAnswerService.answer`（全文，确认同步 LLM 调用与 `CONVERSATION_ID` advisor 语义）、`ConversationIdHelper`、`EvalRunRequestVO` / `EvalCaseCreateVO`
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件
- **关联报告**：`reports/audit-AgentSessionService.java.md`、`reports/audit-WorkspaceRuntime.java.md`

---

## 概览

该类是评测域（数据集 CRUD / 触发评测 run / run 查询 / 基线标记 / 对比 / 报告导出）的核心服务。做得好的部分：所有 Mapper 查询统一带 `tenant_id` 过滤（无越租户面）；`runCase` 对单案异常做了隔离（一例失败不影响整个 run）；`requireDataset`/`requireRun` 有统一的入参校验与 not-found 语义。**未发现注入或越权类漏洞**，但存在 1 个高危（同步阻塞执行整个评测 + 无超时，直接打线程池）与 5 个中危——其中"僵尸 RUNNING run"与"评分串话"会系统性扭曲评测结果本身，对一个评测模块而言是最要害的缺陷。所有修复均不需变更对外 API 签名。

---

## 高严重度

### H-1 `triggerRun` 在 HTTP 请求线程内同步执行全部用例的检索+LLM 调用，且无单案超时——线程池耗尽（DoS）与永久挂起

- **位置**：`EvaluationService.java:135-155`（run 记录落库后在 for 循环内逐案同步调 `runCase` → `hybridRagAnswerService.answer`，每次都是完整检索 + LLM 生成）；`EvaluationController.java:38-50`（两个入口都是同步 `POST`，直接返回完整 `EvalRunVO`）
- **描述**：评测状态虽写了 `"RUNNING"`（:121），但实际是**伪异步**——整个 run 在请求线程里跑完才返回。三重后果：① 一个 50 案的数据集，按每案检索+LLM 5~30s 计，单请求要挂 4~25 分钟，远超一切网关/客户端超时；客户端断开后 run 继续烧 LLM token，结果无人接收。② 无鉴频限——同租户/跨租户并发触发 N 个 run，就占死 N 个 Tomcat 工作线程数十分钟，常规并发压测即可耗尽线程池，波及全服务（经典 DoS 面）。③ `answer()` 调用链内**无任何超时包裹**（`HybridRagAnswerService.java:85-92` 的 `chatClient...call()` 同步等待），LLM 端挂起 = 该请求线程与该 run 永久挂起，run 永远停在 RUNNING（无超时中断、无清理路径）。另注：`createDataset` 对用例数也无上限（:54 只查非空），万案数据集叠加放大前两点。
- **触发条件**：任一已认证租户用户：`POST /ai/evaluation/datasets/{id}/runs` 带较大数据集，或并发发多个 run，或 LLM 端无响应。
- **影响**：服务级可用性事故（线程饥饿 → 全接口不可用）；token 成本失控；僵尸 run 污染后续对比（见 M-2）。
- **修复建议**：将执行改为真正异步——落库 RUNNING 后提交到有界独立线程池（`@Async`/`TaskExecutor`），接口立即返回 runId（不改签名，仅改返回时机的语义需与前端确认，或新增查询端点已被 `GET /runs/{runId}` 覆盖）；每案包 `CompletableFuture.get(timeout)` 或对 `answer()` 加超时；`createDataset` 加用例数上限；触发入口加租户级并发/频控。

---

## 中严重度

### M-1 run 中途异常后永久停留在 RUNNING，无收尾路径；`runCase` 只捕 RuntimeException

- **位置**：`EvaluationService.java:135-141`（结果插入循环无 try/finally 保护）、`:153`（`evalRunMapper.updateById(run)` 只有全程无异常才会执行）、`:234`（`catch (RuntimeException ex)` 不覆盖 `Error` 及 `evalResultMapper.insert(record)`（:267）自身抛出的 `DataAccessException`）
- **描述**：循环内任何未被 `runCase` 消化的异常（如结果落库失败、`summarize` NPE、JVM `Error`）会直接上抛到 controller 变 500，而 run 记录已带着 `status=RUNNING` 落库，**没有任何代码路径会再更新它**——成为僵尸行。`EvalRunRecord.errorMessage` 字段存在（:344 读取）但整个类从未在失败时写入。僵尸 run 随后被 `findRecentByDatasetId` 捞出当"最新 run"（见 M-2），且 `getRun` 对外展示永远 RUNNING。
- **触发条件**：单条结果 insert 失败（连接抖动/字段超长，`answerText` 无长度上限即可撑爆列）即必现；进程在 run 中途重启同样产生僵尸。
- **影响**：评测数据永久不一致；运维无法区分"还在跑"与"已死"；配合 H-1 的客户端超时，僵尸成为常态而非意外。
- **修复建议**：循环体外包 try/catch（或 try/finally + 状态机）：异常时 `status=FAILED`、写 `errorMessage`、补 `finishedAt` 并落库后再上抛；`runCase` 的结果 insert 也纳入失败隔离或重试。

### M-2 `compareLatest` 与 `markBaseline` 不检查 run 状态，僵尸/失败 run 可成为 current/baseline

- **位置**：`EvaluationService.java:177-180`（`recent.get(0)` 无论 status 一律当 current——H-1/M-1 产生的 RUNNING 僵尸正带着**全零指标**占据此位）、`:189-192`（baseline 兜底取 `recent.get(1)` 同样不看状态）、`:164-172`（`markBaseline` 对 RUNNING/FAILED run 照常设基线）
- **描述**：run 状态字段存在但所有消费方都不消费。僵尸 RUNNING run 的指标是 insert 时的全 0（:124-130），`compareLatest` 会把它当 current 返回，用户看到"评测成绩归零"却无任何警示；把失败 run 设为 baseline 后，后续所有对比都在和一份废数据比。
- **触发条件**：发生一次 M-1 的中途失败后调用对比/基线接口——必现，无需并发。
- **影响**：评测结论失真；这是评测模块的核心产出被污染。
- **修复建议**：`findRecentByDatasetId` 过滤 `status IN ('SUCCESS','FAILED')`（或至少排除 RUNNING），`markBaseline` 拒绝非终态 run；对比 VO 中透出 status 供前端标注。

### M-3 `createDataset` 边插入边校验、无事务——一条坏用例留下"幽灵数据集 + 半份数据"

- **位置**：`EvaluationService.java:69`（`evalDatasetMapper.insert(dataset)` 在**任何用例校验之前**执行）、`:72-75`（`if (item == null || !hasText(question)) throw` 在循环体内、前序用例已插入之后）
- **描述**：第 5 条用例为 null/空问题时，数据集 + 前 4 条用例已落库，异常上抛后无回滚（类上无 `@Transactional`，方法内也无手工补偿）。结果：列表里出现一个 `caseCount=4` 的残缺数据集，且 name 合法、完全可用，用户很难意识到它创建失败了；用例的 `expectedCitations` 等列表内容（元素是否合法）也从未校验。
- **触发条件**：一次带脏数据的 `POST /ai/evaluation/datasets` 请求即可，无需并发。
- **影响**：脏数据永久残留（无删除数据集的接口可清理——顺带指出该域**没有任何 delete/update 数据集的 API**，创建失败的数据集无法自救）；后续在该数据集上触发 run 会跑出以偏概全的评测结果。
- **修复建议**：两选一：① 方法加 `@Transactional(rollbackFor = Exception.class)`；② 先全量校验（null/空问题/重复 caseId/列表元素）再开始任何 insert。建议两者都做，②还能把错误一次性报全。

### M-4 无 chatId 的用例共享 `datasetId` 作为会话标识，LLM 对话记忆跨用例/跨 run 串话，系统性污染评分

- **位置**：`EvaluationService.java:370-378`（`resolveChatId` 兜底返回 `evalCase.getDatasetId()`——同数据集所有未指定 chatId 的用例共用一个 chatId）、`:219-225`（该 chatId 与 `ConversationIdHelper.build("eval", chatId)` 一起传给 `answer`，`HybridRagAnswerService.java:90` 将其绑定为 LLM 的 `CONVERSATION_ID` advisor 参数——即真实对话记忆键）
- **描述**：Spring AI 的 conversationId advisor 意味着**同一 conversationId 的多次调用共享对话历史**。兜底逻辑使同一数据集的全部用例在同一次 run 里共用一份不断增长的对话历史：第 N 题的答案受前 N-1 题的问答污染（"如上文所述……"），评测测的是"带上下文的第 N 题"而非独立问题。更糟的是跨 run 串话：两个 run 用同一数据集（或同一 `chatIdPrefix`，:374-375 只按前缀+序号生成，run 间不换）→ 第二个 run 从第一轮的对话历史开始答题，同一数据集两次评测结果不可比。每案独立提问本应传 `ConversationIdHelper.build("eval", runId + "-" + index)` 之类的一次性键。
- **触发条件**：数据集用例未逐条填 `chatId`（默认兜底路径）+ 触发两次 run——最常见用法即命中，完全无需并发。
- **影响**：评分系统性偏差且不可复现；faithfulness/keywordScore 全部失真，评测结论不可信——这是评测正确性缺陷，不只是数据卫生问题。
- **修复建议**：兜底键改为 `datasetId + "-" + runId + "-" + index`（或直接用 resultId），保证案与案、run 与 run 之间隔离；若有意测多轮场景，应在用例上显式声明，而不是靠共享键隐式发生。

### M-5 客户端提供的 `caseId` 不查重、格式不校验，可与默认 id 冲突

- **位置**：`EvaluationService.java:76-78`（`caseId` 仅 trim 后直接采用）、`:78`（默认 id 生成 `%03d` 与客户端 id 可能撞名，如客户端恰好传 `case-001`）
- **描述**：同一数据集内两条用例可携带相同 caseId（无去重校验；若 `eval_case` 表有 (tenant, dataset, case_id) 唯一键，则第二条 insert 直接抛异常，叠加 M-3 造成部分插入；若无唯一键，则脏数据入库）。下游 `eval_result.case_id` 引用该 id（:251），重复后 `getRun`/报告（`EvaluationReportRenderer` 按 caseId 关联）无法区分两条结果，对比与报告全量歧义。另外 id 未做长度/字符集约束，超长值会在落库时才失败（再次叠加 M-3）。
- **触发条件**：创建数据集时传入重复或与默认序列撞名的 caseId。
- **影响**：结果歧义或创建半途失败；报告/对比聚合口径错误。
- **修复建议**：循环前收集 `Set<String>` 做数据集内唯一性校验（重复即 IllegalArgumentException）；对与默认序列撞名的情况同样拒绝或强制改写；补长度上限。

---

## 低严重度

### L-1 `listDatasets` N+1 查询且无分页

- **位置**：`EvaluationService.java:99-101`（每个数据集单独发一次 `findByTenantAndDatasetId(...).size()` 计数）
- **触发条件**：数据集数量增长后，一次列表请求 = N+1 条 SQL。修复建议：单条 `GROUP BY` 计数查询（或 mapper 加 `SELECT dataset_id, COUNT(*) ... GROUP BY`），并补分页参数。

### L-2 `getRun`/报告返回全量 results 无界

- **位置**：`EvaluationService.java:161`、`:343`（run 的全部结果一次性载入并序列化；报告导出 `:201-203` 同样基于全量 VO）
- **触发条件**：大数据集 run 后 `GET /runs/{runId}`——响应体可达数 MB 且含全部答案/证据文本。修复建议：results 分页或在 VO 中默认裁剪（保留指标与失败案），报告渲染器按需取数。

### L-3 `writeJsonList` 吞异常静默降级为 `"[]"`

- **位置**：`EvaluationService.java:430-436`（catch 后返回 `"[]"`，无日志）
- **触发条件**：序列化失败时（含非法代理对的字符串等边界输入），预期引用/关键词**无痕丢失**，评测照跑但全部判 0 分。修复建议：至少 `log.warn` 并携带 datasetId/caseId；创建数据集场景可直接抛错（在写入前丢数据比跑完错评更糟）。

### L-4 `modelProfile` 两处口径不一致

- **位置**：`EvaluationService.java:114-116`（run 记录存 trim 后的 profile）、`:225`（实际传给 `answer` 的是 `request.getModelProfile()` **原始值**，未 trim）
- **触发条件**：payload 里 `"modelProfile": " balanced "`（带首尾空白）——run 记录写的是 `balanced`，实际 LLM 调用收到的是 `" balanced "`，记录与执行不一致，评测元数据失真。修复建议：`:225` 复用已 trim 的局部变量（需将其传入 `runCase`，仅内部签名调整）。

### L-5 `findRecentByDatasetId` 排序键不唯一，"最新 run"可不确定

- **位置**：`EvalRunMapper.java:26`（`ORDER BY created_at DESC` 无 tiebreaker；`created_at` 秒级精度下并发/批量插入并列常见），消费方 `EvaluationService.java:177` 取 `get(0)` 当 current
- **触发条件**：同秒内两次触发 run（或 H-1 的并发触发），两次对比请求可能取到不同"最新"。修复建议：`ORDER BY created_at DESC, id DESC`。

### L-6 `errorMessage` 将底层异常消息原样透出至 API

- **位置**：`EvaluationService.java:236`（`ex.getMessage()` 直接入库并在 `:366` 随 VO 返回）
- **触发条件**：LLM 客户端/检索层异常消息可能含内部端点、模型名、配额细节。影响轻微但有信息泄露面。修复建议：入库时归类为稳定错误码 + 简述，细节进服务端日志（关联 runId）。

### L-7 `shortUuid` 12 位无碰撞处理

- **位置**：`EvaluationService.java:466-468`（48 bit 随机，runId/datasetId/resultId 生成均无唯一键冲突重试）
- **触发条件**：概率极低但 resultId 生成频率最高；撞 `DuplicateKeyException` 时整个 run 挂掉（叠加 M-1）。修复建议：结果落库撞键重试一次，或加长至 16/32 位。

### L-8 评分口径：`hitRate` 分母含空白期望项（协作者缺陷，影响本类指标）

- **位置**：`EvaluationScorer.java:58-62`（hits 按 `hasText` 过滤统计，但除以**未过滤**的 `expected.size()`）
- **触发条件**：数据集里某条期望关键词为空白串——该案的 citationCoverage/keywordScore 永久封顶于 (n-1)/n，且 `EvaluationService.summarize`（:281-286）如实聚合这份失真分数。修复建议：分母同用过滤后数量，或 `createDataset` 时拒绝空白列表元素（与 M-5 的入参校验同处落地）。

---

## 修复优先级建议

1. **H-1**（异步化 + 单案超时）是结构性改动，但它是可用性事故与 M-1/M-2 僵尸问题的共同根源，应最先规划。
2. **M-4**（会话串话）一行键拼接即可修复，却直接决定"评测结果是否可信"——性价比最高的立即修复。
3. **M-1 + M-2**（run 生命周期收尾 + 消费方状态过滤）绑定修复，消除僵尸 run 的产生与放大。
4. **M-3 + M-5**（创建数据集先校验后插入、caseId 查重）同在 createDataset 入口，一次改动一并落地；顺带处理 L-8 的空白期望项拒绝。
5. L 级按顺手处理，L-3（丢数据要有痕迹）建议随任意相关改动带上。

# 缺陷审查报告：HybridRetrievalService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/retrieval/HybridRetrievalService.java`（163 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——四个 retriever 的**打分语义下钻**：`VectorRetriever.extractScore`（clamp01，score/distance 元数据，rank 衰减兜底 ≥0.1）、`KeywordRetriever`（title 0.6+content 0.4 的 overlap 比，0-1）、`GraphRetriever`（实体硬编码 0.85、fact 用 DB `confidence` 原值）、`WebRetriever`→`SearXNGBackend:83`/`BingSearchBackend:90`（rank 衰减 `1.0 - i*0.1`，**topK>10 时为负分**；backend 自带 connect/read 超时）；`ScoredDocument`（注释声称 raw score "0-1"，`finalScore` 可变字段由本类 `applyWeight` 原地改写）、`HybridWeights.normalize()`（全文，负权重/NaN 未防御，全零回退 BALANCED）、调用方 2 处：`HybridRagAnswerService:54-56`（topK=`retrieveTopK`=12，`application.yml:196`）与 `DeepResearchService:58-60`（每个子问题一次，topK=5）；**阻塞底座证据**：`application.yml:37` JDBC URL 无 `socketTimeout`/`connectTimeout`（Connector/J 默认均为无限），vector/keyword/graph 三源底层均走该 DB/向量库；`pom.xml:17`（Java 17，无虚拟线程可用）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm/mvn 操作
- **关联报告**：`reports/audit-RagAnswerService.java.md`、`reports/audit-HybridRagAnswerService.java.md`（如已产出）、`reports/audit-DeepResearchService.java.md`

---

## 概览

该类是混合检索融合层：四路源（vector/keyword/graph/web）经专用线程池并行执行 → 3 秒超时上限 → 按源加权 → 内容指纹去重 → 加权分排序取 topK。做对的部分：

- **专用线程池隔离阻塞 IO**：注释（`:33-34`）明确不用 `ForkJoinPool.commonPool()` 以防饿死 parallel stream——意图正确，方向对。
- **主链路延迟有上界**：`completeOnTimeout(..., 3, SECONDS)`（`:138`）保证任何源失联时 `retrieve` 在 3 秒内返回，单源故障降级为空列表不阻断其余三路（`:134-137`）——优雅降级设计成立。
- **权重归一化健壮**：`normalize()` 处理了和为零的回退（BALANCED）与"已归一"的浮点快速路径；调用方只需传相对权重。
- **去重保留高分副本**：`deduplicate` 用 `LinkedHashMap` 保序且跨源命中时留最终分高的那份（`:146-147`），多源命中本身是相关性信号。
- **可观测有基础**：Timer 按 outcome 打 tag + percentile histogram；`@PreDestroy` 关池（`:41-44`）。

但存在**超时保护与线程池模型错配（hung 源永久占线程直至全量静默降级）、并发排队吃掉超时预算、跨源分数刻度未校准**三类问题。以下修复建议均不需变更对外方法签名。

---

## 高严重度

### H-1 `completeOnTimeout` 只完成 future、不取消底层任务：hung 源永久占用固定池线程，8 个 hang 后检索全线静默降级为空

- **位置**：`HybridRetrievalService.java:35-39`（固定 8 线程池）+ `:138`（`completeOnTimeout` 仅使 future 提前完成，池内任务继续运行）；阻塞证据：vector/keyword/graph 三源底层走 JDBC/向量库，而 `application.yml:37` 的 JDBC URL **未设 `socketTimeout`/`connectTimeout`**（Connector/J 默认无限），graph 源还有每实体一次的 `getNeighbors` 放大查询次数（`GraphRetriever:44`）
- **描述**：`SOURCE_TIMEOUT_SECONDS=3` 约束的是调用方能等多久，**不是任务能跑多久**。源调用 hang 住时（DB 网络分区、慢查询、锁等待——JDBC 无 socket timeout 意味着可以无限期阻塞），该任务永久占用池中一线程，future 照常在 3 秒返回空。每次 `retrieve` 占 4 任务，固定 8 线程 = **两次请求各有一个源 hang 就吃满整池**；此后所有请求的四路任务全部排队，永远轮不到执行，全部在 3 秒后超时返回空——检索系统整体、持续、静默地退化为"永远检索不到内容"。由于源异常被吞、超时被吞（见 M-3），唯一外部可见的症状是 `outcome=empty` 与正常空结果不可区分，没有告警信号。
- **触发条件**：任一源的底层调用无限期阻塞（DB 网络抖动 + 无 socketTimeout 是最现实路径；web backend 自带超时，风险较低）× 足够次数累积占满 8 线程。
- **影响**：RAG 问答与 DeepResearch 的检索全部返回空（`HybridRagAnswerService` 落入"没有检索到可用内容"分支），服务实质不可用；恢复依赖 JVM 重启。
- **修复建议**（不改签名）：① 阻塞层兜底：JDBC URL 补 `connectTimeout=3000&socketTimeout=5000`（部署配置，不动本类）；② 池模型改为"任务级超时 + 线程可回收"：Java 17 无虚拟线程，可改 `Executors.newCachedThreadPool()` + 信号量限并发（或 `ThreadPoolExecutor` 核心小/最大 32/队列 SynchronousQueue），并在任务内用"超时后主动放弃等待的结果"模式；③ 至少给池加活跃度/队列长度 gauge 并在任务执行超 3s 时记 WARN，让 H-1 可被监控发现。

---

## 中严重度

### M-1 固定 8 线程 × 每请求 4 任务：并发 >2 时排队吃掉 3 秒预算，正常负载即系统性劣化

- **位置**：`HybridRetrievalService.java:35`（fixedThreadPool(8)）+ `:90-97`（每请求 4 个任务）+ `:138`（3s 超时从 supplyAsync **入队时刻**起算）；负载证据：`DeepResearchService:57-60`（每个子问题同步一次 retrieve，计划含多个子问题）叠加 `HybridRagAnswerService:54`（每问答请求一次）
- **描述**：即便没有任何 hang，8 线程只支撑 2 个并发 retrieve 的满速执行。第 3 个并发请求的 4 个任务排队，`completeOnTimeout` 的 3 秒计时从**提交时**开始，排队时间全额计入——排在队尾的任务即使实际执行只要 50ms 也会以"超时空结果"收场。10 个并发问答请求 = 40 个任务 = 后 24 个任务几乎必然超时；DeepResearch 顺序处理子问题进一步拉长单请求占用窗口。结果是**负载越高、检索质量越差**的隐性负反馈：慢源（graph 两段查询、web 外部调用）最先被牺牲，且无任何错误日志——`completeOnTimeout` 静默完成，不触发 `:134-137` 的 warn。
- **触发条件**：并发 RAG 请求 >2（生产常态；压测/高峰期必现）。
- **影响**：高峰期检索系统性缺源（尤其 graph/web），最终答案质量下降且与容量告警完全脱节。
- **修复建议**（不改签名）：与 H-1 的池模型改造合并处理——把池容量与预期并发挂钩（`并发数 × 4` + 余量），或改用有界信号量让超载请求快速失败/跳过 web+graph 源而非排队空等；超时计时改为任务实际开始执行后起算（包装 Supplier 记录开始时刻）。

### M-2 跨源分数直接加权融合，但刻度未校准：web 负分、fact confidence 不受控、graph 实体满分恒定

- **位置**：`HybridRetrievalService.java:121-126`（`finalScore = retrievalScore × weight`，假设各源同刻度）；打分证据：`WebRetriever` → `SearXNGBackend.java:83`/`BingSearchBackend.java:90`（`1.0 - i*0.1`，**i≥11 为负**——`HybridRagAnswerService` 传 topK=12 时 web 第 11、12 名必为负分）；`GraphRetriever:48`（实体硬编码 0.85，与相关性无关）、`:60`（fact 用 DB `confidence` 原值，无范围校验——脏数据 >1 时 `0.2×5=1.0` 压过全部 vector 结果）；`VectorRetriever.extractScore`（有 clamp01，是四源中唯一防御完整的）
- **描述**：`ScoredDocument` 注释声称 raw score "0-1"，四个源只有一个真的保证。后果分三层：① web 的排名衰减在 topK=12 时产出负 finalScore，排序沉底尚无害，但去重比较 `d.getFinalScore() > existing.getFinalScore()`（`:146`）的语义在负分域失真；② fact confidence 完全信任 DB 值，越界数据直接扭曲全局排序；③ graph 实体恒 0.85 意味着**任何**实体命中（哪怕与 query 无关）都以固定优势压过 keyword 中位结果——三路刻度各自漂移方向不同，权重 0.40/0.25/0.20/0.15 的调优因此失去稳定含义。包内已有 `RrfReranker`（RRF 对刻度天然免疫），本类未使用。
- **触发条件**：topK>10 的请求（HybridRag 默认即 12）；fact 表存在越界 confidence 数据。
- **影响**：排序结果随各源刻度漂移而漂移，检索质量不可复现；web 负分与 fact 脏数据是确定性 bug。
- **修复建议**（不改签名）：`applyWeight` 前对每源结果做刻度校准——web 负分 clamp01（一行）；fact confidence clamp01（本类内对 graph 结果统一 clamp 即可，不动 GraphRetriever）；中期建议切到包内现成的 `RrfReranker` 做 rank-based 融合，彻底摆脱刻度假设。

### M-3 源级故障不可观测：异常全吞、超时静默、source 参数不进指标，`error` outcome 几乎不可达

- **位置**：`HybridRetrievalService.java:134-137`（RuntimeException → log.warn + 空列表）、`:138`（completeOnTimeout 无任何日志/指标）、`:90-97`（`source` 参数仅用于 log.warn，不进任何 Meter）、`:88`/`:111`（outcome 只有 error 路径是 `:99` 的 join 抛异常——被吞后几乎不可达）
- **描述**：H-1/M-1 的两种劣化（源 hang、源排队超时）在本类产生的全部可观测痕迹：**零**。源异常只有一条 log.warn（无计数器、无 source tag 的 metric）；completeOnTimeout 触发时连日志都没有。`retrieval.hybrid.latency{outcome=empty}` 与"知识库确实没内容"无法区分——而这正是判断 H-1 是否已经发生的唯一线索。运维侧看到的是"RAG 偶尔回答没找到内容"，实际可能已经是全量降级。
- **触发条件**：任何源失败/超时/排队（与 H-1/M-1 同时发生）。
- **影响**：最危险的故障模式完全不可见，排障只能靠翻日志。
- **修复建议**（不改签名）：`retrieveAsync` 内补两类计数器——`retrieval.hybrid.source.result{source=...,outcome=success|empty|error|timeout}` 与超时专用的 `...{outcome=timeout}`（completeOnTimeout 可用 `orTimeout` + whenComplete 区分，或在包装任务里记录实际耗时 vs 超时值）；`source` 参数改为同时打进 metric tag。

---

## 低严重度

### L-1 内容指纹取前 200 字符：同前缀模板化文档被误合并，真实重复又可能漏合并

- **位置**：`HybridRetrievalService.java:153-158`
- **描述**：指纹只看归一化后的前 200 字符：共享同一模板开头（表单类、法务条款类、逐条 QA 格式）而正文不同的 chunk 会被误判为同一文档，**较高分的保留、较低的直接丢弃**——真实的不同文档从结果里消失；反之内容首部不同但主体重复的长文档（重排版）又漏合并。`replaceAll("\\s+", " ")` 对整个 content 跑正则却只用前 200 字符，长文档下是纯浪费。
- **触发条件**：知识库含同模板 chunk（企业文档常见）。
- **影响**：topK 中有效文档数量被静默削减。
- **修复建议**（不改签名）：指纹改为 `docId/chunkId 优先、content 哈希兜底`（有 chunkId 用 chunkId，无则对截断后的内容取 hash）；正则归一化先 `substring` 再执行。

### L-2 `HybridWeights` 接受负权重与 NaN，normalize 不设防

- **位置**：`HybridWeights.java:18-24`（`sum <= 0` 才回退，分量为负或 NaN 时照常归一）；消费点 `HybridRetrievalService.java:91-97`
- **描述**：`of(-1, 2, 0, 0)` 归一后 vector 权重为负 → 对应源 finalScore 为负全排沉底；权重 NaN 使 sum 为 NaN、两个分支都不命中，NaN finalScore 进入 `Comparator.comparingDouble` 后排序行为未定义（不抛异常，结果随意）。当前调用方全用预置常量，属未来调用方陷阱。
- **触发条件**：未来调用方传非常量权重。
- **修复建议**（不改签名）：`normalize` 内对每个分量 clamp 到 `[0, ∞)`、NaN 归 0，再归一化；sum 仍 ≤0 回退 BALANCED。`HybridWeights` 是 record，改方法体不动签名。

### L-3 topK 语义不一致：vector 源不接收 topK，子问题检索仍取全量 12

- **位置**：`HybridRetrievalService.java:91`（`vectorRetriever.retrieve(query, tenantId, chatId)`——四路中唯一不传 topK）；对照 `:92-97`；`VectorRetriever:34`（固定 `ragProperties.getRetrieveTopK()`=12）；调用方证据：`DeepResearchService:60` 传 topK=5
- **描述**：`retrieve(topK=5)` 时 keyword/graph/web 各取 5，vector 仍取 12——最终 `limit(5)` 会裁掉，但向量库 IO 与 embedding 计算已全额发生。行为不算错误（结果正确），但 DeepResearch 每个子问题多付约 2.4 倍向量检索成本，且三源与 vector 的候选池大小不对等会让融合权重隐含偏置。
- **触发条件**：topK < 12 的调用（DeepResearch 全部子问题）。
- **影响**：无效 IO/成本；候选池不对称。
- **修复建议**（不改签名）：为 vector 源补 topK 透传（`VectorRetriever` 增加重载或参数，`VectorRetriever` 不在本报告改动范围，此处记录衔接点）；短期至少在 Javadoc 说明 vector 源固定取 `retrieve-top-k`。

### L-4 线程命名与池边界诊断缺失

- **位置**：`HybridRetrievalService.java:36`（8 个线程全部命名 `"hybrid-retrieval"`，无序号）；`:35`（fixedThreadPool 队列无界）
- **描述**：同名线程在 thread dump 与日志中无法区分哪个线程卡在哪个源；无界队列使排队深度不可见（与 M-1 叠加时无法回答"任务排了多长"）。
- **触发条件**：任何线程级排障。
- **影响**：诊断成本。
- **修复建议**（不改签名）：线程工厂加 `AtomicInteger` 序号（标准写法，两行）；加队列长度 gauge（与 M-3 合并实现）。

### L-5 空/空白 query 未短路，四源各自降级路径不一致

- **位置**：`HybridRetrievalService.java:82`（无 query 校验）；各源自证：keyword `queryTokens.isEmpty() → empty`、graph `!hasText(keyword) → empty`，vector/web 会以空白 query 实际发起检索
- **描述**：空白 query 时 vector/web 白付一次外部调用成本（vector 还会用空串做 embedding），其余两源短路——行为不一致但无害；调用方当前都传真实 prompt，风险低。
- **触发条件**：未来调用方传空白 query。
- **影响**：浪费一次 embedding/外部调用；无正确性问题。
- **修复建议**（不改签名）：`retrieve` 入口对 `!StringUtils.hasText(query)` 直接返回空结果（新增一次短路，不动签名）。

---

## 修复优先级建议

1. **先做 H-1 + M-1（同一根因：池模型）**：这是本类唯一可能导致"服务实质不可用"的组合。第一步零代码：JDBC URL 补 socketTimeout（部署配置）；第二步改池模型 + 活跃度 gauge。
2. **M-3 紧随其后**：没有源级指标，H-1/M-1 发生时不可发现也不可验证修复效果——两行 Counter 注册的成本，收益是让前两项的劣化可见。
3. **M-2 的 web 负分 clamp 是一行确定性修复**，随任意批次带上；RRF 切换属中期优化。
4. L-1/L-2/L-3 各自独立、风险低，按顺手程度排入常规维护；L-4/L-5 与 M-3 的指标改造同批完成。

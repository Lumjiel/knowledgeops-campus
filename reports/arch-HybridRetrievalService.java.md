# 架构分析报告：HybridRetrievalService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/retrieval/HybridRetrievalService.java`（162 行）
- **核查范围**：全部 5 个注入协作者逐个下钻（`VectorRetriever`/`KeywordRetriever`/`GraphRetriever`/`WebRetriever` 全文、`MeterRegistry` 计量面）；`HybridWeights` 与 `ScoredDocument` 契约类全文；`retrieval.web` 子包 SPI（`WebSearchBackend` 接口）；全部 2 个 main 调用方逐调用点核对（`HybridRagAnswerService`/`DeepResearchService`，grep 全库证实）；`graph` 包反向依赖核查；同包 `RrfReranker`/`RerankPipeline`/`EvidenceJudgeService` 平行面比对；测试 `HybridRetrievalServiceTest` 3 场景核对
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`arch-RagAnswerService.java.md`（其 5.1 租户过滤谓词 4 处复制、5.2 双 RAG 门面、5.3 外迁件未接的结论，本报告在 retrieval 包内视角承接）、`arch-EvaluationService.java.md`（hybrid 门面封闭配对证明）、`audit-architecture-hybrid-retrieval.md`（/ai/rag/search 端点不存在的审查）。本类当前无同对象 audit 报告。

## 1. 模块定位

本类是 **hybrid 检索的融合编排器**：4 路召回（向量/关键词/知识图谱/Web）并行执行 → 加权融合 → 内容指纹去重 → 截断 topK。它把散装 retriever 组装成单一检索源，是 rag 包 `HybridRagAnswerService` 五步管线的 Step 1（HybridRagAnswerService:53-56 注释），也是 agent.research 深度研究的检索引擎。

职责内聚度高：融合权重（`HybridWeights` record）、去重、超时、降级全部在类内闭环，自身不碰 LLM、不碰租户上下文、不碰存储。

**main 源码调用方共 2 个**（grep `HybridRetrievalService` 全库证实）：

| 调用方 | 所在包 | 调用什么 | 上下文 |
| --- | --- | --- | --- |
| `HybridRagAnswerService` | rag | `retrieve(prompt, normalizedTenantId, chatId, ragProperties.getRetrieveTopK())`（:54-56） | hybrid 生产主链路 Step 1，4 参重载（默认权重） |
| `DeepResearchService` | agent.research | `retrieve(subQ, normalizedTenant, "research_" + task.getTaskId(), 5)`（:58-60） | 深度研究对每个子问题**串行**循环调用（:53-76 for 循环） |

测试侧 `HybridRetrievalServiceTest` 直接构造（Mockito mock 4 个 retriever），覆盖部分源失败、空结果、全源失败 3 场景——**降级语义是有测试固化的契约**。

**死 API 群**（main + test 全库无调用方，详见 5.2/5.3）：5 参重载 `retrieve(query, tenantId, chatId, topK, HybridWeights)`（:82-119，仅被 4 参重载内部委托 :64）；`HybridWeights` 的全部预设与工厂（`SEMANTIC`/`KEYWORD`/`BALANCED`、`of()`、`semantic()`/`keyword()`/`balanced()`）；本类 4 个权重常量 `VECTOR_WEIGHT` 等（:46-49，融合实际用的是 normalized record，常量在逻辑中零参与）。

## 2. 依赖全景

### 2.1 出边（4 个检索协作者 + 1 个横切）

| 依赖 | 所在包 | 调用点 | 下钻发现 |
| --- | --- | --- | --- |
| `VectorRetriever` | retrieval（同包） | :90-91 | 内部依赖 Spring AI `VectorStore` + `RagProperties`（retrieveTopK/similarityThreshold/sharedCorpusChatId）。**不接收本类 `topK`**，固定用 `ragProperties.getRetrieveTopK()`（VectorRetriever:32），见 4.2 |
| `KeywordRetriever` | retrieval（同包） | :92-93 | 同样裸调 `VectorStore` + 同包 `Tokenizer`；阈值硬编码 0.25（KeywordRetriever:42），见 5.5 |
| `GraphRetriever` | retrieval（同包） | :94-95 | **跨包出边**：`graph.GraphService` + `KgEntityRecord`/`KgFactRecord`（GraphRetriever:3-5），实体打分硬编码 0.85（:52）、fact 用 confidence |
| `WebRetriever` | retrieval（同包） | :96-97 | 依赖 `web` 子包 SPI：注入 `List<WebSearchBackend>` 策略列表（WebRetriever:37-39），默认禁用、无后端降级空列表 |
| `MeterRegistry` | micrometer | :87/:114-117 | 1 个 Timer：`retrieval.hybrid.latency{outcome}`；源级延迟由各 retriever 自己的 `retrieval.{vector,keyword,graph,web}.latency` 记录 |

二跳结构：`retrieval → graph` 是包级出边（graph 包**零反向 import retrieval**，grep 证实）；`retrieval → config.properties`（RagProperties，4 个文件用）；`retrieval.web` 为内部子包（backends 只被 WebRetriever 消费）。值得注意：**retrieval 包全部 com.enterprise 出边里没有 security**——本类不 import `TenantContext`（见 4.1，这是与 MemoryService/RagAnswerService 样板的关键差异）。

### 2.2 入边

- main 2 个直接调用方（第 1 节表格），均只消费 `retrieve` 两个重载与 `HybridRetrievalResult` record；`ScoredDocument` 是被调用方二次消费的共享契约类型（rag 侧 `EvidenceJudgeService.judge` 继续改写其 `finalScore`，见 4.4）。
- 包内无反向依赖：4 个 retriever 不 import 本类，`HybridWeights`/`ScoredDocument` 是纯数据类。
- 测试 1 个直接构造点。

### 2.3 横切依赖

- **专用线程池**（:33-39）：8 线程固定池、daemon、命名 `hybrid-retrieval`，注释明确记录「不用 ForkJoinPool.commonPool 以防饿死并行流」；`@PreDestroy` 关停（:41-44）。线程模型评估见 4.3。
- **超时降级**（:138）：每源 `completeOnTimeout(3s)` + RuntimeException 捕获降级空列表（:134-137）——单源故障不炸整链，warn 日志 + 源级 Timer 可归因。
- **无租户处理**：`tenantId` 原样透传给 vector/keyword/graph 三路（:91/:93/:95），web 路无租户概念。

## 3. 循环依赖评估

**Bean 级与包级均无环。**

- Bean 图：4 个 retriever 互不引用、不回指本类；`GraphRetriever → GraphService` 的二跳（graph 包）不 import retrieval；web backends 不引用包外业务类。无 Bean 环。
- 包图：入边 `rag → retrieval`、`agent.research → retrieval` 两条单向；出边 `retrieval → graph`、`retrieval → config.properties` 单向。四个节点无环。本类对 arch-RagAnswerService 记录的 agent⇄workflow 包级环零参与。
- 与经典管线对照：经典 `RagAnswerService` 直接裸调 `VectorStore`+`RerankPipeline`，不经本类；两条检索链路在本类**不交汇**——hybrid 是独立的一棵树（arch-RagAnswerService 5.2「调用方集合完全不相交」在检索层同样成立）。

**两条潜在环风险**（当前不成立，预埋记录）：

1. 若未来把 `EvidenceJudgeService`/`CitationService`（现处 retrieval 包、唯一消费者在 rag）的编排反向拉进 rag 之外（如 agent 需要引用卡），retrieval 包「基建+增值件」双职责（4.4）会诱使增值件回调 rag 类型，形成 `retrieval → rag → retrieval` 环。当前无此边。
2. `DeepResearchService → 本类 → GraphRetriever → graph.GraphService`：若 graph 侧未来需要检索增强（GraphService 调 hybrid），即成 `retrieval ⇄ graph` 环。当前 graph 是干净叶子。

## 4. 越层评估

### 4.1 租户归一化缺位于服务边界——契约靠调用方自觉（本类与全库样板的关键偏离）

全库正确样板是「服务入口 `TenantContext.normalize`」（arch-RagAnswerService 4.1、arch-MemoryService 4.3 同结论）：MemoryService 7 处 normalize、RagAnswerService 入口 normalize。**本类 0 处**（2.1 import 面证实），`tenantId` 从签名直通三路 retriever：

- 当前两个调用方都自觉归一了：`HybridRagAnswerService:51`、`DeepResearchService:33` 均先 `TenantContext.normalize` 再传入——**今天没有缺口**；
- 但纵深只剩调用方一层：`VectorRetriever:27-29`/`KeywordRetriever:35-37` 把原始值拼进 filter 表达式、`GraphRetriever:38/:58` 原样传给 `GraphService`（其内部只做 like 转义，不 normalize，GraphService:30/:63）。第三个调用方出现（尤其经 agent.harness 工具链）即重演 audit H-1 型缺口。

评价：修复位在本类 `retrieve()` 入口补一次 `normalize`，两个重载收敛到一处即可，不改任何签名，与现有调用方行为完全兼容。

### 4.2 `topK` 契约不对称：vector 路静默忽略调用方 topK

本类签名承诺 `topK` 作用于检索（javadoc :60），但只有 keyword/graph/web 三路转发它（:93/:95/:97）；vector 路 `retrieve(query, tenantId, chatId)` 不带 topK，内部固定 `ragProperties.getRetrieveTopK()`（VectorRetriever:32）。后果：

- `HybridRagAnswerService` 传的恰是 `ragProperties.getRetrieveTopK()`——**两值巧合相等**，契约漂移被掩盖；
- `DeepResearchService` 传 5——vector 路仍拉 12 条参与融合，`topK` 只在最终截断（:109）生效，权重融合的候选集大小与调用方意图脱钩。这是 audit M-1（`debugRetrieve` 死参数）的同型问题：调用方认真传参、服务端部分静默撕毁。
- 修复建议（不改签名）：vector 路转发 `Math.min(topK, ragProperties.getRetrieveTopK())`，或 javadoc 明示 vector 路的取值来源。

### 4.3 线程模型：同步阻塞 + 固定池容量有上限，DeepResearch 串行循环放大占用

- `retrieve()` 在调用线程 `future.join()`（:100）阻塞至多 3s——同步语义与两个调用方的同步管线一致，无伪流式问题（对照 arch-RagAnswerService 4.4 的 `PdfController` 案例）。
- 但 `completeOnTimeout`（:138）**只放弃结果不取消任务**：超时后底层阻塞 IO 仍占着池线程直到自然结束。8 线程 ÷ 每请求 4 源 = 理论并发上限 2 个检索请求；`DeepResearchService` 对子问题串行循环调用（:53-76），每个子问题占 4 线程至多 3s，与 hybrid RAG 主链路共享同一池——研究任务高峰会挤占问答链路的检索吞吐（排队表现为 `retrieve()` join 前的 supplyAsync 入队等待，**不 Governs 3s 超时**，实际延迟可超 3s）。
- 源失败被吞（:134-137）后，顶层 `outcome` 仍可为 success——`retrieval.hybrid.latency{outcome=success}` 不区分「4 源全绿」与「3 源降级」。源级 Timer 可归因，但混合健康度无单一指标。

### 4.4 retrieval 包双职责：检索基建与 hybrid 管线增值件同包，`ScoredDocument` 跨包共享可变状态

retrieval 包同时持有两类成员：

1. **可复用检索基建**：4 个 retriever、本类融合器、`RerankPipeline`/各 Reranker、Tokenizer 家族（经典管线与 hybrid 各取所需）；
2. **hybrid 管线专属增值件**：`EvidenceJudgeService`/`CitationService`/`EvidenceItem`/`CitationItem`——**唯一消费者是 rag.HybridRagAnswerService**（grep 证实），经典管线不接。

后果是 `ScoredDocument.finalScore` 的生命周期横跨两个包：本类 `applyWeight` 写入（:121-126，注释「after fusion」），rag 侧 `EvidenceJudgeService` 再改写（ScoredDocument:29 注释「after fusion + evidence judging」自证两段式），中间无不可变快照、无明确的所有者边界。管线分界知识只存在于注释与调用顺序里。评价：方向无越层（rag→retrieval 合法），但「增值件归属」是边界决策欠账——若认定增值件属 hybrid 管线私有，应随编排者走（归 rag 或独立 pipeline 包）；若认定属 retrieval 公共件，经典管线是潜在第二消费者（当前未接）。

## 5. 平行实现（本报告核心发现）

### 5.1 融合策略同包双轨：内联加权和 vs `RrfReranker`/`RerankPipeline`

同包存在两套完整的多路融合哲学，互不感知：

| 维度 | 本类（hybrid 链路） | `RerankPipeline`+`RrfReranker`（经典链路，唯一外部用户 RagAnswerService） |
| --- | --- | --- |
| 融合算法 | 加权和：`retrievalScore × source weight`（:121-126） | RRF（倒数排名融合，RrfReranker） |
| 去重 | 内容前 200 字符指纹（:141-158） | 无（RagAnswerService 侧未见等价物） |
| 多样性/重叠惩罚 | 无 | OverlapReranker |
| 权重/参数来源 | `HybridWeights` record（代码内） | RagProperties（配置） |

两条链路在「多信号怎么合成一个分」上做了两次独立决策，选型理由（何时该用 RRF、何时加权和）无任何文档。架构风险同 arch-RagAnswerService 5.2：不是重复本身，而是演进无路由——下一个「融合需求该长在哪棵树上」没有答案，且两套算法的效果差异只能靠评测侧（EvaluationService 只评 hybrid）单边感知。

### 5.2 权重值三处复制、常量死代码——「默认权重」知识无单点

同一组默认权重值（0.40/0.25/0.20/0.15）存在于三处：`HybridWeights.DEFAULT`（HybridWeights:9，**实际生效**）、本类 4 个死常量 `VECTOR_WEIGHT` 等（:46-49，逻辑零参与）、4 参重载 javadoc 的硬编码文本（:55）。任何一处调权（尤其改 `HybridWeights.DEFAULT`）都会让另外两处静默过期。死常量应删除或改为引用 `HybridWeights.DEFAULT` 的字段读取——均不影响签名。

### 5.3 权重调优 API 建好未接线：5 参重载与全部预设死代码

`HybridWeights` 提供了完整的调优面（4 个预设 + `of()` 工厂 + `normalize()` 归一），5 参 `retrieve` 是为其设计的入口——但全库（main+test）**没有任何调用方**使用带权重的重载（grep 证实，唯一引用是 :64 内部委托传 `DEFAULT`）。同时 `RagProperties` 无 hybrid 权重键（出边核查证实仅 vector/keyword 两侧消费）。结果：javadoc :73 认真写了调参建议（factual 查询升 vector 权重），实际调参路径需要改代码重新编译。死预设+不可配置是同一件事的两面：**评测调优闭环（EvaluationService→hybrid）缺最后一根配置管子**。

### 5.4 租户过滤谓词与转义仍在包内两处复制（承接 arch-RagAnswerService 5.1）

arch-RagAnswerService 5.1 记录的全库 4 处同构 filter 表达式复制，其中 2 处在本包：`VectorRetriever:27-29` 与 `KeywordRetriever:35-37`，配套 `escapeFilter` 两份逐字等价副本（VectorRetriever:112-115 / KeywordRetriever:100-103），且安全修正史注释只存在于 VectorRetriever 一份（:108-111）。**本类自身是正例**：它作为融合编排点没有复制任何谓词——说明收敛单点落 retrieval 包是自然的；但 4 处复制收敛至今未做（arch 报告 P0 项），hybrid 链路占其中一半。

### 5.5 兄弟 retriever 配置不对称 + 小项

- **阈值口径分裂**：同两个 VectorStore 消费者，`VectorRetriever` 走 `ragProperties.getSimilarityThreshold()`（:33），`KeywordRetriever` 硬编码 0.25 + 经验式扩召 `max(topK*2, 20)`（:41-42）——同源同库，一个可配一个写死，调参者无法在配置层统一行为。
- **WebRetriever 冗余初始化**：构造时设 `finalScore(r.getScore())`（WebRetriever:81），随即被本类 `applyWeight` 覆盖——无害，但暗示 web 路曾被设计为可独立使用，与「finalScore 由融合层写」的当前契约相悖。
- **指纹截断碰撞**（记录项，缺陷归属 audit）：去重指纹取归一化后前 200 字符（:153-158），长文档共享前缀（如同模板的条款文档）会误合并，保留的是 finalScore 较高者——对 hybrid 的 graph/web 路尤其值得评测侧验证。

## 6. 修复优先级建议（均不改对外 API 签名）

1. **P0** `retrieve()` 入口补 `TenantContext.normalize`（4.1）——消除「契约靠调用方自觉」的纵深缺口，两处重载收敛一次实现。
2. **P1** vector 路 topK 契约统一（4.2）：转发 `min(topK, retrieveTopK)` 并 javadoc 明示；同时修正 4 参 javadoc 的硬编码权重文本（5.2）。
3. **P1** 权重接线（5.3）：`RagProperties` 增加 hybrid 权重键，5 参重载接通配置——评测调优闭环补最后一根管子；`HybridWeights.DEFAULT` 之外的死预设与死常量（:46-49）随同清理成文。
4. **P2** 融合策略成文（5.1）：RRF vs 加权和的选型边界写进两处 javadoc 或 docs/，并明确「新融合需求归哪棵树」。
5. **P2** retrieval 包双职责边界成文（4.4）：`EvidenceJudgeService`/`CitationService` 的归属与 `ScoredDocument` 生命周期所有者需一个明确决定；若保留现状，至少把「融合层写 finalScore、评测层可改写」的约定写进 `ScoredDocument` 注释。
6. **P2 记录项**：filter 谓词 4 处收敛（5.4，与 arch-RagAnswerService P0 为同一修复，单点落 retrieval 包）；线程池容量与超时不取消任务的容量规划说明（4.3）；KeywordRetriever 阈值入配置（5.5）。

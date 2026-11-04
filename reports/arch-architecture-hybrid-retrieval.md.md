# 架构分析报告：architecture-hybrid-retrieval.md 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码与文档；不运行任何 build/mvn；全部结论来自静态交叉审查，每条结论附文档行号与代码位置证据）
- **分析对象**：`docs/architecture-hybrid-retrieval.md`（107 行：四路召回融合图 :1-23 + 各检索器说明 :25-45 + 融合策略 :47-51 + 证据评分 :53-63 + 引用溯源 :65-78 + API :80-84 + 返回结构 :86-107）
- **核查范围**：文档「依赖面」七节逐条对码——mermaid 图逐节点对 `retrieval` 包 14 文件（全读核心 6 个：HybridRetrievalService/HybridWeights/VectorRetriever/KeywordRetriever/GraphRetriever/WebRetriever + EvidenceJudgeService/CitationService 全文）；权重四值对 `HybridWeights.DEFAULT`（:9）与 `HybridRetrievalService` 常量（:46-49）；融合三步对 `retrieve()`（:82-119）；三维评分对 `EvidenceJudgeService`（:27-111）；引用字段对 `CitationService`（:15-30）+ `CitationItem`；返回结构逐字段对 `HybridRagAnswerService.HybridRagResult`（:40-120 全读）；API 表对 controller 目录全量 grep（`/ai/rag/search` 与 `/ai/retrieval/search` 各自核实）；管线消费面 grep（`HybridRetrievalService` 与 `HybridRagAnswerService` 与 `RerankPipeline` 的全部调用方）
- **约束遵守**：未改源码与文档、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（所有修复建议均为文档侧编辑）
- **关联报告**：`audit-architecture-hybrid-retrieval.md.md`（同文件缺陷审查一手记录）；`arch-HybridRetrievalService.java.md`（completeOnTimeout 不取消任务、固定池 + 无 socketTimeout 的 JDBC→hung 任务永久占线程、打分刻度逐源核查、RrfReranker 未接）；`arch-RagAnswerService.java.md`（单路管线全貌、sharedCorpusChatId 4 处复制、转义副本）；`arch-architecture-knowledge-graph.md.md`（kg_* 表零写入者→graph 源实践恒空）；`arch-architecture-agent-harness.md.md`（rag_search 工具消费的是单路管线）

## 1. 模块定位

本文档是**检索子系统的管线声明（数据流类文档）**：交付三类契约——①四路召回→融合→证据→引用→生成的数据流图（:5-23），②四个检索器的行为参数（:25-45），③融合/评分/引用的三段算法契约与 API（:47-107）。

**核心定位偏差（本报告主线）**：文档标题与通篇读感是「本平台的检索架构」，实况是**平台存在两条平行检索管线**，本文档只写了其中一条且未声明：单路管线（`RagAnswerService`：向量召回 + `RerankPipeline` 粗排精排，6 个 reranker 类的家）承载 `/ai/chat`、PDF chat、`rag_search` 工具的全部生产流量；四路混合管线（`HybridRetrievalService`）的生产消费方只有 DeepResearch（经 `/ai/research`）与评测（`HybridRagAnswerService` 仅 EvaluationService 调用、无 HTTP 暴露）。文档声明的 API（:84）整体虚构（4.1），流程图把两条管线的阶段合并成了一条不存在的端到端路径（4.2）。与之相对，**算法细节层的对齐度是全系列架构文档最高的**：权重四值、dedup 指纹、三维评分公式、引用字段、返回结构全部逐字对码成立（5.2）。

## 2. 依赖全景

### 2.1 出边（文档对代码的声称清单）

| 文档声称 | 位置 | 核实结果 |
| --- | --- | --- |
| 四路召回：Vector/Keyword/Graph/Web（:7-10） | HybridRetrievalService.java:27-30 | ✅ 四源真实存在且并发执行（CompletableFuture + 固定 8 线程池 :35-39） |
| 融合权重 0.40/0.25/0.20/0.15（:12-15） | HybridWeights.DEFAULT:9 = (0.40, 0.25, 0.20, 0.15) + 常量 :46-49 | ✅ 精确；未声明的是权重**可配置**（SEMANTIC/KEYWORD/BALANCED 三预设 + normalize() :22-27）与 per-source **3 秒超时 + 失败静默空列表**（:50/:128-138）——后者是融合可用性的核心行为（4.4） |
| VectorRetriever「pgvector + HNSW 索引」（:28） | VectorStore 抽象 + yml:12-13 | ❌ **无条件声明失实**：出厂默认 simple 文件后端（VectorStoreProperties backend=simple，SeedDataRunner:53-56），PgVectorStoreAutoConfiguration 在 spring.autoconfigure.exclude 里（yml:13）；pgvector 是可切换项非既成事实（4.3） |
| Vector 阈值 0.45（:29） | yml:198 `similarity-threshold: 0.45` → RagProperties | ✅ 精确 |
| Vector 过滤「tenant_id + chat_id」（:30） | VectorRetriever.java:27-29 | ⚠️ 漏共享语料分支：实际是 `tenant_id == X && (chat_id == Y \|\| chat_id == sharedCorpusChatId)`——sharedCorpusChatId 是检索侧的核心语义（4 处复制的口头契约，arch-RagAnswerService 已记），文档只字未提（4.3） |
| Keyword「标题 0.6 + 内容 0.4」（:34） | KeywordRetriever.java:60 | ✅ 精确 |
| Keyword「对向量检索的补充：捕获精确术语匹配」（:35） | KeywordRetriever.java:34-44 | ❌ **方向性失实**：Keyword 的候选池就是 `vectorStore.similaritySearch(threshold 0.25, topK×2)`（:38-44）——先向量召回再关键词重排，**无法补向量盲区**（0.25 以下或候选池外的精确术语永远不可见）；「补充」的实义是「向量候选池上的重排器」（4.4） |
| Keyword「Jaccard 类重叠系数」（:36） | KeywordRetriever.overlapScore:85-89 | ⚠️ `|query∩target| / |target|` 是**包含系数**（分母=target 全集）非 Jaccard（\|∩\|/\|∪\|）——「类」字保住了下半句，但分母选择使长文档（content token 集大）系统性低分，与「捕获精确术语」的意图相抵（4.4） |
| Graph「实体名/别名搜索 + 一跳邻居 + 三元组」（:38-41） | GraphRetriever.java:38-69 | ✅ 三能力齐全（searchEntities :38、getNeighbors :42、searchFacts :58-69）；未声明：实体分硬编码 0.85（:52）、kg_* 表除迁移种子外零写入者→**非 public 租户的 graph 源实践恒空**（arch-architecture-knowledge-graph 已证，4.5） |
| Web「占位实现，DeepResearch 模块中接入真实搜索 API」「权重最低仅按需启用」（:44-45） | WebRetriever.java:14-28 javadoc + WebSearchBackend SPI | ❌ **过时 ×2**：①已接 SearXNG/Bing 双真实后端（backend SPI :33、javadoc 配置指南 :17-27），非占位；②接入点是混合融合的第四源（HybridRetrievalService:30/:96-97），不是 DeepResearch 模块；「enabled 默认 false」半对（:49 确认默认禁用，与 :45 的「仅按需启用」吻合）（4.4） |
| 融合三步：加权→200 字符指纹去重保最高分→finalScore 降序取 topK（:49-51） | HybridRetrievalService.java:104-109/:141-158 | ✅ 三步逐字精确（fingerprint :153-158 首尾空白归一 + 200 截断；dedup 保 finalScore 最高 :146-149）——全文档最扎实的一节 |
| EvidenceJudge 三维权重 0.50/0.30/0.20 + 综合公式（:57-63） | EvidenceJudgeService.java:27-41 | ✅ 权重与公式精确 |
| Authority「graph > vector > keyword > web」（:60） | EvidenceJudgeService.java:83-91（0.90/0.75/0.65/0.50） | ✅ 序与值精确 |
| Timeliness「<30天: 1.0, <365天: 0.7」（:61） | EvidenceJudgeService.java:93-110 | ⚠️ 实为 **5 档**：<30:1.0 / <90:0.9 / <180:0.8 / <365:0.7 / ≥365:0.5，无时间戳默认 0.70——文档二档简化漏了默认值（无元数据时的实际主导档）与 ≥365 档（4.5） |
| Relevance「检索分数 + query-doc 关键词命中」（:59） | EvidenceJudgeService.java:65-81（base + min(0.3, hits×0.05) boost） | ✅ 语义精确（boost 封顶 0.3 未提，轻度省略） |
| Citation 字段 index/sourceType/title/chunkId/confidence/excerpt（:69-78） | CitationService.java:15-30 + CitationItem | ✅ 全对（confidence = 证据综合分 :25）；另有 url 字段真实存在（:23）文档 JSON 里没列，轻度 |
| **API：POST /ai/rag/search（:84）** | controller 目录全量 grep | ❌ **端点不存在**。全仓无 /ai/rag/ 任何映射；返回结构与文档 :86-107 逐字段吻合的 `HybridRagAnswerService.HybridRagResult` **无 HTTP 暴露**（唯一调用方 EvaluationService:220，评测链路）；最接近的真实端点 `POST /ai/retrieval/search`（RetrievalDebugController:19-25）走的是**另一条管线**（RagAnswerService.debugRetrieve，不进 LLM，返回 documents/耗时指标而非 answer/citations）（4.1） |
| 返回结构 answer/citations/evidence/traceId/memoryUsed（:88-106） | HybridRagAnswerService.java:74-80/:106-113 | ✅ 逐字段精确（EvidenceItem 九字段、reason 中文格式串 :122 与文档示例 :101 同构）——但结构对应的端点不存在（4.1）；memoryUsed 恒空 `List.of()`（:79/:110） |

### 2.2 入边（混合管线的真实消费面——文档完全未声明）

- `agent.research.DeepResearchService:27/:58`：混合管线的**唯一生产消费方**（经 `/ai/research` HTTP 可达）——但只消费融合结果（retrieve 后进规划/检索/写作步骤），**不经过 EvidenceJudge/Citation**（流程图 :17-21 的下半段对 DeepResearch 不成立）；
- `rag.HybridRagAnswerService:35/:54`：全流程消费方（融合+评分+引用+生成，与文档 :17-23 完全同构）——但唯一调用方是 `EvaluationService:220`，**评测专用、无 HTTP**；
- 生产主 chat 路径（`/ai/chat`、PDF chat、harness `rag_search` 工具→BuiltinToolRuntime:92）全部走 `RagAnswerService` 单路管线——**与本文档描述的管线零交集**。

### 2.3 隐式依赖：Keyword/Vector 同池双查

KeywordRetriever 与 VectorRetriever 各自独立调 `vectorStore.similaritySearch`（前者 threshold 0.25/topK×2，后者 0.45/topK）——同一查询对向量库打两次，Keyword 池严格包含 Vector 池（阈值更低、候选更多）。融合的「四路」在实践中是「3.5 路」：一路真正的向量 + 一路同源重排 + graph + web。文档 :13 的「Keyword → weight 0.25」画成独立召回源，掩盖了这层同池关系。

## 3. 循环依赖评估

- **Bean 级与包级均无环（正面）**：HybridRetrievalService 出边全叶（4 retriever + MeterRegistry）；4 个 retriever 无互相依赖（Keyword 不依赖 Vector 类，只平行访问同一 VectorStore）；retrieval 包对 rag/graph 包单向向下（HybridRagAnswerService→HybridRetrievalService、GraphRetriever→GraphService，零回边）；RagAnswerService（rag）→RerankPipeline（retrieval）同为向下。混合管线是全库少见的「声明即全貌」的干净扇出结构；
- **无管线间环但存在管线间暗合**：两条管线共享 VectorStore、RagProperties、Tokenizer、escapeFilter 副本（5.2）——共享面靠复制而非抽取件维持，任一侧改共享语义（如 sharedCorpusChatId、转义规则）需人工同步两处（arch-RagAnswerService 已记录一处已发生的转义修正史不同步）。

## 4. 越层评估

### 4.1 API 章节整体虚构：文档教一个 404 的调用（核心）

:84 `POST /ai/rag/search` 在全仓无任何映射（controller 目录 grep 证实）。三层错位叠加：①文档的返回结构（:86-107）与 `HybridRagAnswerService.HybridRagResult` 逐字段吻合——作者显然对着该服务写的文档，但该服务**唯一调用方是 EvaluationService**，从 HTTP 面永远不可达；②真实存在的最近端点 `/ai/retrieval/search` 走的是 RagAnswerService.debugRetrieve——**另一条管线**的调试端点，不进 LLM、返回 documents/耗时而非 answer/citations，语义与文档承诺完全不同；③混合管线唯一的生产出口是 `/ai/research`（DeepResearch），响应是研究报告，既非 :84 的路径也无 :86 的结构。按本文档写客户端的开发者第一次调用即 404，改试 /ai/retrieval/search 后拿到的是第三种结构。修复位：API 章重写为三行真话——混合全流程当前无 HTTP 端点（评测内部消费）；DeepResearch 经 /ai/research 间接消费融合结果；/ai/retrieval/search 是单路管线调试端点（若要暴露 /ai/rag/search 需新增 controller，属代码 backlog 而非文档编辑）。

### 4.2 流程图把两条管线合并成一条不存在的端到端路径

:5-23 的图（四路→融合→证据+引用→LLM→回答）与 `HybridRagAnswerService` 的执行序列完全同构（retrieve→judge→citations→context→LLM→footer，:54-100）——但该服务仅评测可达；唯一生产消费方 DeepResearch 在融合之后走的是规划/检索/写作步骤循环，**无 EvidenceJudge 无 CitationService**（DeepResearchService:58-89 全链无二者调用）。即：图的上半段（四路→融合）的生产现场在 DeepResearch，图的下半段（证据→引用→生成）的生产现场只在评测。文档把「评测链路」画成了「平台检索架构」。修复位：图拆两段或加泳道标注「下半段仅 HybridRagAnswerService（评测）行使」；并补一句「/ai/chat 生产主路走单路管线（RagAnswerService + RerankPipeline），见 <另一份文档或本节说明>」——平台级检索架构文档不写主路管线是覆盖面静默收窄（对照 arch-architecture-enterprise §1 的无 scope 声明同型）。

### 4.3 Vector 检索器的三个环境性声明失实/漏项

- 「pgvector + HNSW」（:28）：出厂默认 simple 文件后端、pgvector autoconfig 被排除（yml:12-13）——声明把「可切换项」写成「既成事实」；
- 过滤条件（:30）漏 `sharedCorpusChatId` 分支（VectorRetriever:27-29）——共享语料是「租户内 chat 隔离 + 全租户共享知识库」双轨模型的核心，漏掉它读者无法理解为何 rag_search 能命中非本会话文档；
- 阈值/维度两条（:29/:31）精确（yml:198/:39-42）。

修复位：:28 改「VectorStore 抽象：默认 simple 文件后端，可切 pgvector（yml exclude 移除 + backend 切换）」；:30 补共享语料分支及语义说明。

### 4.4 Keyword/Web 两节的行为声明与实现强度错位

- **Keyword「对向量检索的补充」（:35）**：候选池就是 threshold 0.25 的向量检索（:38-44）——它补的是「向量候选池内的排序盲区」，不是「向量检索的召回盲区」；文档标题级的「四路召回」在 Keyword 处退化为「同池重排」。叠加 4.3 的默认后端（simple/PGVector 都是纯向量），**平台不存在任何真正的词法倒排召回**——「Redis 缓存穿透」式精确术语若向量库未召回，Keyword 也救不回；
- **「Jaccard 类重叠系数」（:36）**：实为 `\|∩\|/\|target\|` 包含系数——分母是文档 token 集，长 chunk 系统性压分；「捕获精确术语匹配」的意图被分母选择部分抵消（短查询命中长文档时 overlap 趋零）；
- **Web「占位实现」（:44）**：SearXNG/Bing 双后端已接（WebSearchBackend SPI + javadoc :15-28），默认 enabled=false 是「可配置禁用」不是「占位」；「DeepResearch 模块中接入」的位置也错——它是融合的第四源，DeepResearch 只是间接经融合消费。修复位：三处按实况改写；Keyword 节明示「候选池=向量检索（threshold 0.25）」。

### 4.5 融合与评分节漏掉的行为事实

- **per-source 3s 超时 + 失败静默空列表**（HybridRetrievalService:50/:134-138）：任一源超时/抛错不失败整个融合，静默降级为三路/两路/单路结果——「四路召回」的实际保证是「最多四路」；配套的固定 8 线程池在 hung 任务下会被耗尽（arch-HybridRetrievalService 主证：completeOnTimeout 只完成 future 不取消任务，JDBC 无 socketTimeout 时 hung 检索永久占线程直至全量静默降级）——这是融合可用性的一等公民行为，文档 :47-51 三步里没有它；
- **权重可配置**（HybridWeights 三预设 + normalize）：文档权重写成常量，实为按调用传入可调（评测正是这么用的）；
- **Timeliness 5 档**（:93-110）：文档 2 档漏默认档 0.70（无时间戳元数据时的实际主导值——而 simple 后端的元数据恰恰常无 created_at）；
- **Graph 源实践恒空**：kg_* 表除迁移种子外零写入者、非 public 租户图谱恒空（arch-architecture-knowledge-graph 已证）——四路中的 0.20 权重在多数租户是乘在空列表上的。修复位：融合节补降级语义一句；:61 补全档位；:38-41 Graph 节注记「依赖 kg_* 写入链路，当前除种子数据外无写入者（见图谱文档）」。

### 4.6 存量正面项（无修复）

- 权重四值、融合三步（含 200 字符指纹与保最高分）、三维权重/公式/权威序、Citation 全字段、返回结构全字段——**算法契约层的对齐精度是全系列架构文档之最**（含示例 reason 中文串与 EvidenceJudgeService:122 格式同构）；
- 阈值 0.45、维度 1024、Keyword 标题/内容 0.6/0.4 全部精确；
- enabled 默认 false 的「仅按需启用」表述（:45 后半句）与实现一致。

## 5. 平行实现

1. **两条检索管线**（本报告主线）：单路+RerankPipeline（6 个 reranker 类的家，生产主路）vs 四路融合（本文档唯一对象，DeepResearch+评测）——文档层面的平行是「只写其一且不提其二」，代码层面的平行是两管线共享 VectorStore/properties/Tokenizer/转义副本而无共享抽象；RrfReranker 等 reranker 家族对混合管线完全未接（包内已有件不接的错位，arch-HybridRetrievalService 已记）；
2. **escapeFilter 逐字等价双副本**：VectorRetriever:112-115 与 KeywordRetriever:100-103——Vector 侧注释自证修正史（"The previous implementation only stripped single quotes"），副本当前一致但修正需人工双写（arch-RagAnswerService 已记第三处副本在 RagAnswerService）；
3. **同池双查**：Keyword/Vector 各自调 similaritySearch（2.3）——同一召回源的两次独立查询是「平行实现」的资源面：每条混合检索对向量库打两次；
4. **metaStr/truncate/Timer.Sample 脚手架四副本**：四个 retriever + EvidenceJudge 各养一套同型私有方法——与 harness stringVal 副本族同型，包内收敛成本最低项；
5. **memoryUsed 恒空字段**：HybridRagResult.memoryUsed 恒 `List.of()`（:79/:110）——文档返回结构如实照抄了一个死字段（saveTaskMemory 型死 API 的 VO 面）。

## 6. 修复优先级建议（均为文档侧编辑，不改任何对外 API 签名；改动仅限本文件）

1. **P1** API 章重写（4.1）：删除虚构的 `/ai/rag/search`，如实声明「混合全流程无 HTTP 暴露（评测内部消费）；生产出口仅 /ai/research；/ai/retrieval/search 是单路管线调试端点」；
2. **P1** 流程图拆分/加注（4.2）：上半段标注生产现场（DeepResearch），下半段标注仅评测行使；补一句主 chat 路径走单路管线的管线地图——否则文档持续把「评测链路」冒充「平台检索架构」；
3. **P2** Keyword 节按实况改写（4.4）：候选池=向量检索 threshold 0.25、包含系数公式与长文档压分效应、平台无真正词法召回的边界声明；Web 节去「占位」改双后端+默认禁用（4.4）；
4. **P2** Vector 节环境声明对齐（4.3）：pgvector 改可切换项、过滤条件补 sharedCorpusChatId；融合节补 3s 超时/静默降级/固定池与权重可配置（4.5）；
5. **记录项**：Timeliness 5 档补全与默认档标注（4.5）；Graph 节注记 kg_* 写入链路现状（4.5，引用图谱文档）；memoryUsed 死字段标注（5.5）；与 RagAnswerService 管线的关系在两份检索类文档间约定单点（5.1——主路管线文档目前不存在，属新文档 backlog）。

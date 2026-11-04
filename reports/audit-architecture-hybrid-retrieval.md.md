# 审查报告：docs/architecture-hybrid-retrieval.md

- **审查日期**：2026-11-04
- **审查方式**：只读通读全文，将文档每条声明（mermaid 图、各检索器参数、融合策略、评分表、JSON 示例、API 表）逐一对码核实，未改动任何源码，未 git commit/push，未删除文件，未执行 npm install / build / mvn 构建。
- **协作者清单**（结论均 grounded 到以下文件）：
  - 检索链路：`retrieval/HybridRetrievalService.java`、`VectorRetriever.java`、`KeywordRetriever.java`、`GraphRetriever.java`、`WebRetriever.java`、`web/WebSearchProperties.java`
  - 评分与引用：`EvidenceJudgeService.java`、`CitationService.java`、`CitationItem.java`、`EvidenceItem.java`
  - 问答管道：`rag/HybridRagAnswerService.java`、`rag/RagAnswerService.java`、`evaluation/EvaluationService.java`
  - 图谱：`graph/GraphService.java`、`graph/KgEntityMapper.java`
  - 控制器：`RetrievalDebugController.java`、`PdfController.java`（controller 目录全量核对）
  - 配置：`application.yml`、`RagProperties.java`、`pom.xml`（spring-ai 1.1.7）
- **关联报告**：`reports/audit-HybridRetrievalService.java.md`、`reports/audit-RagAnswerService.java.md`、`reports/audit-architecture-agent-harness.md.md`、`reports/audit-api-recipes.md.md`

## 概述（先说做对的部分）

1. **四路权重逐字一致**：mermaid 图与 §融合策略的 0.40/0.25/0.20/0.15 与 `HybridRetrievalService.java:46-49` 的 `VECTOR_WEIGHT/KEYWORD_WEIGHT/GRAPH_WEIGHT/WEB_WEIGHT` 完全一致。
2. **融合三步与代码逐步吻合**：加权（`applyWeight`，`:121-126`）→ 前 200 字符 fingerprint 去重、保留最高分（`fingerprint`，`:153-158`；`deduplicate`，`:141-151`）→ 按 finalScore 降序取 topK（`:107-109`）。
3. **证据评分表基本准确**：权重 0.50/0.30/0.20（`EvidenceJudgeService.java:27-29`）、综合公式 `relevance×0.50 + authority×0.30 + timeliness×0.20`（`:39-41`）、权威序 graph > vector > keyword > web（0.90/0.75/0.65/0.50，`:84-90`）、相关性 = 检索分 + query-doc 关键词命中加成（`:65-81`）均属实。
4. **JSON 示例与 VO 字段完全一致**：引用 JSON 的 `index/sourceType/title/chunkId/confidence/excerpt` 与 `CitationItem.java` 一致；返回结构的 `answer/citations/evidence/traceId/memoryUsed` 与 `HybridRagAnswerService.HybridRagResult`（`:144-151`）一致，`evidence[]` 字段与 `EvidenceJudgeService.java:43-53` 一致，`traceId` 的 `trace-<12hex>` 格式一致（`HybridRagAnswerService.java:47`）——但见 H1：没有任何端点返回它。
5. **GraphRetriever 三条声明全部属实**：实体名/别名搜索（`KgEntityMapper.java:11-19`，`name LIKE ... OR JSON_CONTAINS(aliases, ...)`）、一跳邻居（`GraphService.getNeighbors`，`:37-58`）、事实按 subject/object 关键词搜索（`GraphService.searchFacts`）。
6. **VectorRetriever 参数属实**：相似度阈值 0.45（`application.yml:198`）、维度 1024 + text-embedding-v4（`application.yml:33-34,161`）；"HNSW 索引"经 `PgVectorStore` `initializeSchema=true`（`VectorStoreConfiguration.java:70`）+ Spring AI 1.1.7 默认索引类型间接成立（文档若注明"由 Spring AI 自动创建"更严谨）。

## 高严重度

### H1. API 章节整体失实：`POST /ai/rag/search` 不存在，文档承诺的返回结构没有任何 HTTP 端点提供

- **位置**：`docs/architecture-hybrid-retrieval.md:80-84`（API 表）、`:86-107`（返回结构）；反证在 controller 目录全量 `@RequestMapping` 清单、`RetrievalDebugController.java:19-45`、`HybridRagAnswerService.java:33`、`EvaluationService.java:220`
- **描述**：三个叠加失实：
  1. 全仓库不存在 `/ai/rag` 路径——controller 目录下没有任何 RagController。
  2. 文档承诺的 `answer + citations + evidence + traceId + memoryUsed` 结构是 `HybridRagAnswerService.HybridRagResult` 的形状，而该服务**唯一的调用方是 `EvaluationService`**（评测流水线内部，`:220`），从未暴露为 HTTP 端点。
  3. 实际最接近的两个端点都与文档不符：`POST /ai/retrieval/search`（`RetrievalDebugController.java:25`）是**不进 LLM** 的调试端点（类注释明言"只跑多路召回 + 两段重排，不进 LLM"），返回 `ok/query/recallCount/degraded/recallMs/coarseMs/fineMs/documents`，没有 answer/citations；`POST /ai/pdf/chat`（`PdfController.java:99`）返回流式 HTML，且它用的 `RagAnswerService` 根本**不走四路混合检索**（自带单路向量检索 + `RerankPipeline`，`RagAnswerService.java:9,21-23`），citations 是字符串列表脚注而非结构化 `CitationItem[]`。
- **触发条件**：集成方按文档对接 `POST /ai/rag/search` → 404；或对照返回结构写解析代码 → 全部字段解析失败。
- **影响**：API 章节是文档中"可直接照做"的部分，全章不可用；同时造成"混合检索已对外开放"的错觉——它目前只是评测内部管道。
- **修复建议**：三选一并写明：① 新增 controller 把 `HybridRagAnswerService.answer` 暴露为 `POST /ai/rag/search`（新增端点，不变更任何既有 API 签名，与文档立即对齐）；② 文档改写为现状——"混合检索管道当前供评测（eval_run）内部调用，对外仅有调试端点 `/ai/retrieval/search` 与单路 `/ai/pdf/chat`"；③ 明确标注为 roadmap。禁止的做法是把 `/ai/retrieval/search` 改名冒充——它的返回形状和语义（无 LLM）都不同。

## 中严重度

### M1. KeywordRetriever 并非独立关键词通道，而是"放宽阈值的向量候选重排"，与文档承诺相反

- **位置**：`docs/architecture-hybrid-retrieval.md:33-36`；反证在 `KeywordRetriever.java`（候选获取段）
- **描述**：文档说关键词检索"捕获精确术语匹配，如 Redis 缓存穿透"、"对向量检索的补充"。实现上它**以向量库为唯一候选源**：`vectorStore.similaritySearch(threshold=0.25, topK=max(topK*2,20))` 后再按关键词重叠重排。这意味着 embedding 相似度低于 0.25 但关键词精确命中的 chunk **永远进不了关键词召回**——它无法补向量检索的盲区，因为它站在向量检索的候选池里。"Redis 缓存穿透"这类精确术语查询恰是 embedding 弱项，两路会在同一批文档上同时漏。
- **触发条件**：查询含精确术语、目标 chunk 与查询的 embedding 相似度 < 0.25（跨领域术语、代码标识符等典型场景）。
- **影响**：文档让读者相信存在一条独立的精确匹配通道；实际混合检索的召回上限 = 向量候选池上限，是检索架构的关键约束，文档完全未提。
- **修复建议**：文档如实描述"关键词检索 = 放宽阈值（0.25）的向量候选 + 标题/内容重叠重排"。代码侧若要真正补齐精确通道需引入独立倒排索引（如 MySQL FULLTEXT 或 ES），属较大改动，可先在文档标注现状与 roadmap。

### M2. WebRetriever "占位实现" 已失实：它是真实的多后端检索器，且启用语义与文档不符

- **位置**：`docs/architecture-hybrid-retrieval.md:43-45`；反证在 `WebRetriever.java`（类注释与 `retrieve`）、`WebSearchProperties.java:20-40`、`application.yml:167-175`
- **描述**：文档称 Web 检索"占位实现，DeepResearch 模块中接入真实搜索 API"。实际 `WebRetriever` 已委托 `WebSearchBackend`（SearXNG 或 Bing API v7 双后端，带连接/读取超时配置），只是默认 `app.web-search.enabled=false` 关闭。"仅在有外部信息需求时启用"也不对——它是**全局静态开关**，开启后对每个查询都尝试检索，不存在按查询判断"外部信息需求"的逻辑。DeepResearch 是另一个独立模块，与本检索器无关。
- **触发条件**：读者据"占位实现"跳过该路运维规划（如未给 SearXNG 出网权限后突然开启）。
- **影响**：文档描述落后于代码一个版本；启用条件（全局开关 + searxng-url/bing-api-key）这一运维关键信息缺失。
- **修复建议**：改写为"已实现 SearXNG/Bing 后端委托，默认 `app.web-search.enabled=false`；启用需配置 `app.web-search.searxng-url` 或 `app.web-search.bing-api-key`"。

### M3. 融合策略未写静默降级契约：任一路超时/异常即静默变空，调用方无感知

- **位置**：`docs/architecture-hybrid-retrieval.md:47-51`；反证在 `HybridRetrievalService.java:50,131-138`
- **描述**：文档把融合写成确定性的三步流程，未提任何降级语义。实现上每路有 3 秒超时（`SOURCE_TIMEOUT_SECONDS=3`，`completeOnTimeout(List.of(), ...)`，`:138`）且异常被 catch 后返回空列表（`:134-137`）——任一路失败或超时，融合照常返回，结果对象不携带任何"哪一路降级了"的信息（只有 Micrometer 指标里有 outcome tag）。另注：`completeOnTimeout` 只完成 future 不中断底层任务，超时的检索线程仍占用固定 8 线程池（详见 `reports/audit-HybridRetrievalService.java.md`）。
- **触发条件**：pgvector 慢查询、SearXNG 外网抖动、图谱 SQL 变慢——生产环境常态。
- **影响**：检索质量静默下降且难归因；评测与线上 bad case 定位时无法从响应判断"这次 graph 路是不是空的"。
- **修复建议**：文档在 §融合策略补一段降级语义（"每路 3s 超时，失败/超时路静默置空，不阻断整体返回"）。代码侧可在 `HybridRetrievalResult` 增加每路状态/来源计数字段（新增字段不改既有签名），调试端点 `/ai/retrieval/search` 已经输出 `degraded`，正文管道可对齐。

## 低严重度

### L1. "使用 Jaccard 类重叠系数评分"命名不准

- **位置**：`docs/architecture-hybrid-retrieval.md:36`；反证在 `KeywordRetriever.overlapScore`
- **描述**：实际公式是 `|query∩target| / |target|`——非对称的包含系数（overlap coefficient 变体，分母取候选侧 token 数），Jaccard 是 `|∩|/|∪|`。分母选择使长文档（content token 多）得分被系统性压低，这对评分行为有实际影响，不只是命名问题。
- **触发条件**：内容长、query 短的典型场景，关键词得分趋近于零，`score > 0.05` 门槛（`KeywordRetriever.java:60` 附近）可能筛掉有效候选。
- **影响**：轻微，但"文档声称的公式"与"实现"的偏差应记录。
- **修复建议**：文档改为"包含系数（query 命中数 / 候选 token 数）"，或注明 Jaccard 为简化表述。

### L2. VectorRetriever 过滤条件漏掉 shared-corpus 全局语料

- **位置**：`docs/architecture-hybrid-retrieval.md:30`；反证在 `VectorRetriever.java:27-29`
- **描述**：实际过滤器是 `tenant_id == ? && (chat_id == ? || chat_id == "shared-corpus")`——除会话级文档外还合并全局共享语料（`RagProperties.sharedCorpusChatId`，`RagProperties.java:21`）。文档只写 `tenant_id + chat_id`。
- **触发条件**：读者推导检索可见范围时——实际可见域比文档声明大一档（同租户内所有会话共享全局语料）。
- **影响**：低；租户隔离本身未被破坏（tenant_id 仍在过滤中）。
- **修复建议**：补一句"另有同租户全局共享语料（chat_id=shared-corpus）合并召回"。

### L3. 时效性分档表不完整

- **位置**：`docs/architecture-hybrid-retrieval.md:61`；反证在 `EvidenceJudgeService.scoreTimeliness`（`:93-111`）
- **描述**：实际分档为 <30:1.0、<90:0.9、<180:0.8、<365:0.7、≥365:0.5、**无时间戳元数据:0.70（中性默认）**。文档只写两档，且漏掉"无时间戳默认 0.70"这个最常见的实际路径（vector/keyword 路 chunk 的 metadata 未必有 created_at）。
- **触发条件**：文档读者按表格推算时效分。
- **影响**：轻微失真。
- **修复建议**：表格补全六档，或至少注明"无时间戳按 0.70 中性默认"。

## 修复优先级建议

1. **H1**：决定混合检索管道的对外形态（新增端点 or 改写文档现状），这是唯一会阻塞外部集成的问题。
2. **M1**：先改文档如实描述关键词路的实现本质；独立倒排索引列为 roadmap。
3. **M2 / M3**：随下次文档例行维护更正（Web 检索启用方式是纯配置说明，改动成本低）。
4. **L1–L3**：合并为一次小的表格/措辞修订。

## 约束遵守声明

- 未修改任何源码、配置或本文档本体；仅新增本报告文件。
- 未执行 git commit/push；未删除文件；未执行 npm install 或任何 build/mvn 构建。
- 修复建议均未要求变更任何对外 API 签名（H1 建议的新增端点为增量，M3 建议的字段为新增字段）。

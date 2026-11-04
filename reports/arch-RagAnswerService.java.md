# 架构分析报告：RagAnswerService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/rag/RagAnswerService.java`（429 行）
- **核查范围**：该类全部 9 个注入协作者逐个下钻其依赖清单；非构造器出边（`TenantContext`/`SecurityContextHolder`/`AuthIdentity`/`SystemConstants`）；全部 3 个调用方（grep 全库证实，含 `BuiltinToolRuntime`→`RagAnswerService` 的 agent 链路下钻到 `ReactAgentService:334` 的调用点）；同包 `HybridRagAnswerService` 全文比对；`retrieval.VectorRetriever`/`KeywordRetriever` 的过滤表达式复制面；`CommonConfiguration` 的 3 个 `ChatClient` Bean；`RagProperties` 全部 topK 键
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-RagAnswerService.java.md`（同文件缺陷审查，H-1 跨租户调试/M-1 topK 死参/L-4 身份键降级/L-6 过滤表达式复制等发现以该报告为准，本报告在架构层面承接）、`arch-ReactAgentService.java.md`/`arch-WorkflowReactAgentService.java.md`（ChatClient Bean 绑定与 Flux.defer 线程模型的完整论证）、`arch-EvaluationService.java.md`（hybrid 门面封闭配对的证明）、`architecture-hybrid-retrieval.md` 审查（/ai/rag/search 端点不存在的结论引用）

## 1. 模块定位

rag 包全库仅 2 个文件：本类与 `HybridRagAnswerService`。本类是**经典三级管线 RAG 门面**（向量召回 → 粗排+精排 → LLM 生成），并附带三个职责块：

1. `answer()`（:91-173）：生产主链路，含三层记忆接线（读 :98/:312-335、写 :153/:342-380）；
2. `debugRetrieve()`（:53-85）：只跑「召回+精排」不进 LLM 的调试入口，经 `RetrievalDebugController`（`/ai/retrieval/search`）暴露；
3. 结构化增值件：`CitationCard` record（:421-428）与 `retrievalTrace`（:279-291），即 spec-A1/A5 的条款级引用与检索轨迹面板数据源。

**全部调用方 3 个**（grep `RagAnswerService` 全库证实）：

| 调用方 | 所在包 | 调用什么 | 上下文 |
| --- | --- | --- | --- |
| `PdfController.chat()` | controller | `answer()`（:106） | HTTP `/pdf/chat`，MVC 请求线程同步调用后包成 `Flux.just(单元素)` |
| `RetrievalDebugController.search()` | controller | `debugRetrieve()`（:32-33） | HTTP 调试端点，离线评测（Hit@K/MRR）数据源 |
| `BuiltinToolRuntime.executeRagSearch()` | agent.harness | `answer()`（:92-98） | ReAct Agent 的 `rag_search` 动作（LLM 自主决定调用） |

对比同包：`HybridRagAnswerService` 唯一调用方是 `EvaluationService`（arch-EvaluationService 已证「唯一调用方⇄唯一消费者」封闭配对）。**两个 RAG 门面的调用方集合完全不相交**——这是理解第 5 节平行实现现状的关键事实。

## 2. 依赖全景

### 2.1 出边（9 个注入协作者 + 4 个静态/横切依赖）

| 依赖 | 所在包 | 方向评价 |
| --- | --- | --- |
| `VectorStore` | Spring AI 接口 | 部署期边界（向量库后端），本类直接裸调 `similaritySearch`（:179） |
| `ChatClient` | Spring AI | Bean 名绑定（见 4.2） |
| `ModelRouter` | llm | `resolve(modelProfile, "rag", ...)`（:126），`"rag"` 是路由域字符串，与 `HybridRagAnswerService` 的 `"rag_hybrid"`（:81）并存为两个路由域 |
| `RagProperties` | config.properties | 叶子数据类，但 4 个 topK 语义相近键的分片使用见 5.4 |
| `TenantCostService` | service | `estimateTokens/assertBudget/recordUsage`（:127-128/:137-138） |
| `RerankPipeline` | retrieval | 外迁件（`OverlapReranker.java:13` 注释证实 spec-B1 从本类私有逻辑外迁） |
| `MemoryService` | memory | 记忆读写 7 处调用（:315/:323/:346/:353/:356/:358/:373/:375），租户显式传参 |
| `SlotExtractor` | memory | 槽位解析/合并/序列化 |
| `MeterRegistry` | micrometer | 横切：4 个 Timer + 2 个 Counter（`rag.pipeline/retrieval/rerank.*`） |
| `TenantContext`（静态） | security | `normalize()`（:54/:96）——入口归一是全库正确样板 |
| `SecurityContextHolder`（静态） | Spring Security | `currentUserId()`（:300）读 ThreadLocal 身份，见 4.3 |
| `AuthIdentity` | security | principal 类型判断（:301） |
| `SystemConstants` | constants | RAG 系统提示词（:132） |

二跳结构：`retrieval` 包（RerankPipeline→各 Reranker）与 `memory` 包（MemoryService→mapper）都不回指 rag；`service.TenantCostService`、`llm.ModelRouter` 同样不 import rag（全库 import `com.enterprise.iqk.rag` 的只有第 1 节 3 个调用方 + 同包 2 文件，grep 证实）。

### 2.2 入边

- main 源码 3 个直接调用方（第 1 节表格），无任何包反向依赖本类出边之外的东西——`RagResult`/`PipelineDebugResult`/`CitationCard` 三个对外类型只被这 3 个调用方消费。
- 测试侧未发现直接构造本类的测试（与 WorkspaceRuntime 的纯叶性质不同，本类协作者多，测试经容器装配）。

### 2.3 横切依赖

- **双记忆体系并存**（边界记录项）：`answer()` 一方面经 `MessageChatMemoryAdvisor`+`CONVERSATION_ID`（:134）让 Spring AI `ChatMemory`（MySQL 持久化）记录对话历史，另一方面 `persistConversationMemory`（:342-380）写自研三层记忆——同一请求两条独立的记忆写入路径，互不感知，故障语义也不同（前者抛错上抛，后者 catch 全吞）。
- Meter 命名 `rag.pipeline.*`/`rag.retrieval.*`/`rag.rerank.*` 与 `rag.hybrid.pipeline.*`（HybridRagAnswerService:116/121）前缀不同无合并冲突，但 `rag.*` 前缀下两套管线的指标归因需要人知道前缀规则。

## 3. 循环依赖评估

**Bean 级与包级均无环，本类是干净的下游汇点。**

- Bean 图：9 个协作者无一 import rag 包，rag 也不被它们任何二跳依赖回指 → 无 Bean 环。
- 包图：`controller → rag`、`agent.harness → rag` 两条入边全部单向；rag 对 agent⇄workflow 的既有包级环（arch-WorkflowReactAgentService 3.1）**零参与**。
- 与姊妹类对照：`HybridRagAnswerService` 的入边只有 evaluation 一条，同样无环。rag 包整体是「被消费者拉取、不回调」的叶子型业务包，这在全库属于边界最健康的类别。

**唯一值得记录的潜在环风险**（当前不成立）：`BuiltinToolRuntime`（agent.harness）→ 本类 → `memory.MemoryService`。若未来 rag 侧需要反向触发 Agent 动作（例如记忆冲突时请求 Agent 裁决），将形成 `agent.harness → rag → agent.*` 的包级环。当前代码无此边，仅作边界扩产的预埋提示。

## 4. 越层评估

### 4.1 controller/agent 两条入边的租户信任模型不对称

本类对租户边界的处理是**显式传参 + 入口 `TenantContext.normalize`**（:54/:96），这是全库正确样板（同 arch-AgentSessionService 的结论）。但归一≠校验：

- `RetrievalDebugController:30` 的 `tenantId` 来自**请求 body**（客户端可控），直接透传给 `debugRetrieve` → `filterExpression`（:55）决定向量库租户过滤。本类既不校验「调用者身份 == tenantId」，也没有任何前置权限规则。这是 audit H-1 的架构根源：**租户隔离的纵深取决于每个调用方自觉**，`/ai/retrieval/search` 是全库唯一把租户作用域交给客户端任意填写的 RAG 数据出口。
- `PdfController:100-105` 从 MDC 取租户（认证链路写入，权威）；`BuiltinToolRuntime:94` 从 `AgentAction.tenantId()` 取（上游 `executeAction`（ReactAgentService:334）由请求链路填充）。这两条入边的租户来源是可信的。

评价：类内做法正确，边界缺口在调用方一侧。修复动作（权限规则/身份-租户一致性断言）应落在 `RetrievalDebugController`，属于与本类直接相关的文件且不改任何签名。

### 4.2 `ChatClient` 注入靠 Bean 名巧合（与姊妹服务同批问题）

`CommonConfiguration` 定义 3 个 `ChatClient` Bean（`chatClient`/`serviceChatClient`/`pdfChatClient`，:20/:31/:45），无 `@Primary`。本类字段名 `chatClient`（:40）恰好等于 Bean 名才绑对了目标——且绑到的是**通用助手** Bean（defaultSystem 为通用人设、无 RAG 指令），本类靠 :132 的 `.system(SystemConstants.RAG_ANSWER_SYSTEM + knownInfo)` 每次调用覆盖默认 system 才成立。`HybridRagAnswerService:38` 同款巧合。字段重命名这类「安全重构」会直接 `NoUniqueBeanDefinitionException`。修复：注入点加 `@Qualifier("chatClient")` 或为 RAG 管线定义专用 Bean，均不改对外签名。

### 4.3 `currentUserId()` 的线程上下文假设（audit L-4 的架构面）

`currentUserId`（:299-306）读 `SecurityContextHolder` ThreadLocal。三条入边的线程模型不同：

- `PdfController`/`RetrievalDebugController`：MVC 请求线程，身份在位（但调试端点本身 audit H-1 已覆盖）。
- `BuiltinToolRuntime` 路径：`ReactAgentService` 的 `stream()` 返回 `Flux.defer(...)`（:119），`agentHarnessService.execute` 在 defer 链内被调用（:334→AgentHarnessService→SPI 分发）。MVC 异步返回的订阅线程模型无法静态证实（同 arch-ReactAgentService 对 MDC 的中危定级逻辑），一旦订阅线程非请求线程，`SecurityContextHolder` 为空 → **记忆身份键从 user 级静默降级为 `chat:{chatId}` 会话级**，不报错、丢隔离、且同一用户在不同链路上记忆割裂。与本类对 `TenantContext`（显式传参）的严谨处理形成同文件内的双标：租户走参数、身份走 ThreadLocal。

评价：修复建议是 `AgentAction` 已携带身份信息的场合由调用方显式传入身份，或至少把降级事实打进日志——均不动 `answer()` 签名。

### 4.4 `PdfController.chat()` 的伪流式包裹

`PdfController:99-107` 在 MVC 请求线程内**同步跑完整个 RAG 管线（含 LLM 同步 `call()` 与 7 处 MySQL 记忆读写）**，再把最终字符串包成 `Flux.just(result.getAnswer())`。响应式类型包裹的是已完成的同步结果——流式语义是伪装的，客户端拿到的是「一个元素的流」。这是边界伪装而非缺陷爆炸点（MVC 栈下阻塞在线程池线程上，不炸 event-loop），但它让 `/pdf/chat` 的接口契约（`Flux<String>`）与实际行为（一次性返回）背离，且与 `ReactAgentService` 的真流式形成同库两种语义。属于 controller 侧记录项。

## 5. 平行实现（本报告核心发现）

### 5.1 租户过滤谓词全库 4 处复制，「租户+会话作用域」知识无单点

完全同构的 filter 表达式（`tenant_id == "..." && (chat_id == "..." || chat_id == "{sharedCorpusChatId}")`）存在于：

| 位置 | 说明 |
| --- | --- |
| 本类 `debugRetrieve` :55-57 | 调试入口 |
| 本类 `answer` :99-101 | 生产主链路 |
| `VectorRetriever:27-29` | hybrid 管线的向量召回 |
| `KeywordRetriever:35-37` | hybrid 管线的关键词召回 |

配套的转义函数也是两套逐字等价的私有副本：`escapeFilterValue`（本类 :391-394）与 `escapeFilter`（`VectorRetriever:112-115`、`KeywordRetriever:100`）。`VectorRetriever:108-111` 的注释还记录了转义规则的历史缺陷（旧实现只剥单引号可注入谓词）——**这条安全修正史只被 retrieval 包的 2 处继承，本类 2 处是独立复写**，当前恰好等价，未来任何一处改动（如支持 `in` 谓词、转义单引号）都会静默分叉。`sharedCorpusChatId` 的语义（全局共享语料混入会话检索）散落在 4 处字符串模板里，属于架构层面的「知识无单点」。修复：下沉为 retrieval 包的单一 `SearchRequest` 构造组件（含转义），4 处收敛——不改本类对外签名。

### 5.2 同包两个 RAG 门面骨架级重复，收敛方向无 owner

`HybridRagAnswerService` 与本类的 `answer()` 是同一骨架的两次实现，逐段比对：

| 骨架段 | 本类 | HybridRagAnswerService |
| --- | --- | --- |
| Timer.Sample + outcome 状态机 | :92-93/:161-172 | :46-48/:115-126 |
| 空召回兜底文案 | :114 逐字「没有在当前知识库中检索到可用内容。」 | :62 **逐字相同** |
| 预算闸门→LLM→记账顺序 | assertBudget(:128)→call(:129-136)→recordUsage(:138) | 完全同序（:83→:85-92→:95-96） |
| system 提示 + CONVERSATION_ID advisor | :132/:134 | :88/:90 |
| 引用脚注拼接 | `formatCitationFooter`(:150/:396-405) | `citationService.formatCitationFooter`(:99) |
| token 估算口径 | `estimateTokens(prompt+"\n"+context)`(:127) | 逐字同式(:82) |

差异仅在检索源（裸 `VectorStore`+`RerankPipeline` vs `HybridRetrievalService`）与增值件（本类多三层记忆、CitationCard、retrievalTrace；hybrid 多 EvidenceJudge/traceId）。结合调用方不相交的事实：**本类是存量生产链路，hybrid 是评测封闭的新一代管线**。架构风险不是重复本身，而是演进无路由——没有机制回答「下一个 RAG 需求该长在哪棵树上」。且 hybrid 无调试入口（`/ai/rag/search` 不存在，arch-hybrid-retrieval 已证），`debugRetrieve` 只服务经典管线，评测与调优能力只覆盖半边。

### 5.3 召回未复用已外迁的 `VectorRetriever`，外迁手术做了一半

spec-B1 已把重排逻辑外迁为 `RerankPipeline`（本类消费 :200），但**召回侧的同款外迁件 `VectorRetriever`（同过滤、同 sharedCorpusChatId、同 threshold，`VectorRetriever:27-35` vs 本类 :55-63/:99-107）本类未接**，仍裸调 `VectorStore` 并自带 `similaritySearchWithMetrics`（:175-194）。两个近似实现还各养一套 Timer（`rag.retrieval.latency` vs `retrieval.vector.latency`），同一物理动作两种指标口径。这与 arch-WorkflowReactAgentService 的「抽取件已存在但平行实现不接」同型：重排收敛了、召回没收敛。

### 5.4 topK 配置键碎片化 + `debugRetrieve` 死参数（契约漂移实例）

`RagProperties` 有 4 个语义相近的 topK 键：`retrievalTopN=50`（本类用，:60/:104）、`retrieveTopK=12`（VectorRetriever 用）、`rerankTopK=6`（本类用，:122）、`coarseTopK=20`（RerankPipeline 用）。键名不表达「哪个管线用哪个」，调参者需读 4 个类的源码才能对上。

在此之上是 audit M-1 的契约漂移：`debugRetrieve(String, String, String, int topK)`（:53）的 `topK` 参数**从未被消费**（:60 固定用 `retrievalTopN`），而 `RetrievalDebugController:29-33` 认真解析并传递了它——调用方遵守了一个服务端静默撕毁的契约。这是 5.3 未复用检索件的直接下游后果：若 `debugRetrieve` 走 `VectorRetriever`，参数传递天然成立。修复建议（不改签名）：在 `debugRetrieve` 内消费 `topK`（如 `Math.min(topK, ragProperties.getRetrievalTopN())`），并在 javadoc 标注与 `retrievalTopN` 的取小关系。

### 5.5 类内小平行（轻）

- `citationText`（:225-229）与 `citationCard`（:235-268）对同一 Document 的 file/chunk 元数据各取一遍，注释已声明二者并存意图（spec-A1「旧格式不动」），属可接受的有意平行；但 `.pdf` 后缀剥离只存在于 `citationCard`（:238），`citationText` 仍带后缀——同一引用在同一回答的正文脚注与卡片里文件名不同形。
- 记忆压缩逻辑（:358-376）内联在本类，`titleOf`/`describe` 的拼装是 `MemoryService` 域知识渗透——记忆生命周期策略长在 RAG 门面里，是 memory 包职责的越层下沉（中低优先级记录项）。

## 6. 修复优先级建议（均不改对外 API 签名）

1. **P0** 租户过滤谓词 + 转义下沉为 retrieval 包单点组件，4 处收敛（5.1）——同时消除本类与外迁件的转义分叉面；顺手让 `debugRetrieve`/`answer` 复用之。
2. **P0** `RetrievalDebugController` 补租户一致性校验或权限规则（4.1，audit H-1 的架构修复位）。
3. **P1** `debugRetrieve` 消费 `topK` 参数（5.4），恢复评测契约；4 个 topK 键至少在 `RagProperties` 注释里写明各自消费者。
4. **P1** `ChatClient` 注入加 `@Qualifier`，消除字段名巧合绑定（4.2）。
5. **P2** 本类召回侧改接 `VectorRetriever`（5.3），完成 spec-B1 未完的另一半外迁；`currentUserId` 身份显式化或降级日志化（4.3）。
6. **P2 记录项**：两个 RAG 门面的收敛路线（建议以 hybrid 为目标形态、本类调试入口泛化到双管线）；`PdfController` 伪流式的接口契约澄清；双记忆写入路径的职责边界成文。

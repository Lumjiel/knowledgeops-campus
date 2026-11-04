# 架构分析报告：AnswerFeedbackService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/service/AnswerFeedbackService.java`（162 行）
- **核查范围**：全部 3 个注入协作者 + 1 个静态出边逐个下钻（`AnswerFeedbackMapper` 全文、`FeedbackProperties` 全文含注释威胁模型、`AnswerFeedback`/`AnswerFeedbackSubmitVO` 契约类、`TenantContext`）；唯一调用方 `FeedbackController` 全文核对；建表契约 `V8__session_feedback_cost_platform.sql:24-39` 比对；数据集文件全仓消费者核查（src/scripts/eval/evaluation/docs 四处 grep）；同库其他文件写入面与双写失败语义比对（`MemoryService.emitEvent`、`WorkspaceRuntime`）
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-AnswerFeedbackService.java.md`（同文件缺陷审查：H-1 轮转与注释威胁模型不符、H-2 并发轮转、M-1 中文关键词失效、M-2/M-3 等，缺陷面以该报告为准，本报告在架构层面承接）、`arch-MemoryService.java.md`（双写失败语义对照）

## 1. 模块定位

本类是**用户反馈的唯一入口**：校验入参 → 落库 `answer_feedback`（V8 迁移表）→（`app.feedback.enabled` 开启时）把反馈拼装成评测数据集 JSONL 追加写盘并按大小轮转。单一 public 方法 `submit(String tenantId, AnswerFeedbackSubmitVO)`（:34-67），对外 API 面极小。

**main 源码调用方唯一**（grep 全库证实）：

| 调用方 | 所在包 | 调用什么 | 上下文 |
| --- | --- | --- | --- |
| `FeedbackController` | controller | `submit(TenantContext.currentTenantId(), payload)`（:20-22） | HTTP `POST /ai/feedback`，租户取 MDC（认证链路写入，权威），权限门 `SecurityConfiguration:66` + 全局 IP 限流兜底 |

测试侧无直接构造测试（src/test grep 零命中）——校验逻辑与轮转逻辑均无单测固化，与 MemoryService（有 TenantIsolationTest）、HybridRetrievalService（有降级语义测试）形成覆盖缺口。

**两条持久化路径在仓内均无读者**（详见 4.2/5.1）：`answer_feedback` 表的 mapper 是纯 `BaseMapper` 无任何自定义查询、无管理端点；数据集文件在 src/scripts/eval/evaluation 全仓 grep 零消费者——下游消费都在仓外（注释声明的训练管道 / 人工分析）。

## 2. 依赖全景

### 2.1 出边（3 个注入协作者 + 1 个静态依赖 + 1 个文件系统边界）

| 依赖 | 所在包 | 使用点 | 方向评价 |
| --- | --- | --- | --- |
| `AnswerFeedbackMapper` | mapper | `insert`（:62） | 纯 `BaseMapper<AnswerFeedback>`、零自定义 SQL；二跳仅 `AnswerFeedback`（`@TableName("answer_feedback")`） |
| `FeedbackProperties` | config.properties | `isEnabled`（:64）、`getDatasetPath`（:96）、`getMaxDatasetBytes`（:102） | 叶子配置类；注释里带着威胁模型声明（防单租户刷盘），audit H-1 已证实现与声明不符 |
| `ObjectMapper` | Jackson | `writeValueAsString`（:101） | **真实使用**（对照 MemoryService 的死注入）；且类内刻意只用它序列化手工构造的 `Map<String,String>`，时间字段走 `createdAt.toString()`（:80）——不依赖 JavaTimeModule，序列化边界是自洽的选择 |
| `TenantContext`（静态） | security | `normalize`（:48） | 全库正确样板：入口归一 + 空值兜底 "public"，不触碰 `tenant_id NOT NULL`（V8:26） |
| 文件系统 | — | `Files.createDirectories/move/writeString`（:98-116） | 隐式出边：`Path.of(properties.getDatasetPath())` 默认相对路径 `evaluation/feedback_dataset.jsonl`——**进程工作目录耦合**（同样式见 FeedbackProperties:10 与 application.yml:165），部署目录变化即写偏 |

二跳结构：mapper→domain→（MyBatis-Plus 注解）全部单向；properties 无依赖。service 包对 `com.enterprise.iqk.*` 的出边里本类只连 config.properties/domain/mapper/security 四个叶子方向，**不 import 任何业务包**。

### 2.2 入边

- main 1 个直接调用方（第 1 节表格），消费面只有 `submit` 一个方法与 `AnswerFeedbackSubmitVO` 一个入参类型。
- 无任何包反向依赖本类出边；`AnswerFeedback` 域对象全库仅 3 个文件引用（域类、mapper、本类）——反馈域是自包含的小闭环。

### 2.3 横切依赖

- **双写一致性语义**（详见 4.3）：DB insert（:62）成功后文件追加失败会 `IllegalStateException` 上抛（:117-119）→ 请求 500，DB 行已落、无补偿；对照 `MemoryService.emitEvent`（MemoryService.java:142-155）catch 全吞的 best-effort——同库两种双写失败哲学并存，无成文约定。
- **安全纵深在 filter 层**：本类信任调用链（权限门 + 限流都不在类内），与「服务入口只做业务校验」的分层一致。

## 3. 循环依赖评估

**Bean 级与包级均无环，本类处于全库依赖图的末端叶子位置。**

- Bean 图：3 个协作者全是叶子（mapper 接口、properties 数据类、Jackson 库对象），无一回指 service 包 → 无环、连潜在环都没有。
- 包图：`controller → service → {mapper, domain, config.properties, security}` 链条全单向。service 包内的兄弟类（`TenantCostService`/`ReactAgentService` 等）不 import 本类；rag 包对 service 包的既有依赖（`RagAnswerService → TenantCostService`）与本类无关且同向。
- 数据集文件是**文件旁路**：未来评测侧（evaluation 包）若消费该文件，走的是文件系统而非 Bean 注入，不会拉出 `evaluation → service` 的编译期依赖——这是文件旁路的解耦优点（代价见 5.1 的 schema 无契约）。若演进为「评测直接读 `answer_feedback` 表」，`evaluation → mapper` 也仍单向。**本类无任何值得预埋的环风险，是包级循环依赖评估中最干净的一档。**

## 4. 越层评估

### 4.1 校验与租户归一在服务入口：正面样板（无缺口记录）

入参四连校验（:35-46）+ 入口 `TenantContext.normalize`（:48）+ comment 截断 1024 与列宽 `VARCHAR(1024)` 精确对齐（:57，V8:32）——这是全库「入口归一 + 契约对齐建表」样板的标准实现（对照 arch-MemoryService 4.3、arch-RagAnswerService 4.1 的正面引用）。租户来源走 MDC 权威（controller :21），无客户端可控租户入口。**本节无修复项**，作为对照样板引用。

### 4.2 评测域知识长在业务服务里：数据集 schema 与正反例构造是 eval 域职责（核心越层项）

`appendToDataset`（:69-120）产出的不是「反馈的持久化表示」，而是**评测断言用例**：`category=user_feedback`、`expected_keywords`/`forbidden_keywords` 的正反例构造规则（rating≥4 → 关键词进 expected、"不知道/无法回答/胡编" 进 forbidden，:85-87；rating≤2 → 反转，:88-90）。这套规则是评测域知识（什么样的回答算好/坏），但它：

1. 内联在业务 service 的私有方法里，evaluation 包对它零感知（grep 证实无消费者）；
2. 词表硬编码在代码（:87/:89/:145），调评测集不能改配置；
3. 字段投影是第二份 schema：表有 `session_id/branch_id/message_id`（V8:28-30），数据集**丢掉这三个**、换上 `id/category/keywords`（:70-80）——同一条反馈的两套表示在类内各自维护，无共享契约。

评价：这不是方向性越层（service 写自己的下游文件合法），而是**领域归属错位**——若评测侧要消费这份 JSONL，schema 变更必须改业务服务代码；反过来业务侧改反馈字段也会静默破坏评测数据格式（无类型契约、无版本字段）。归位方向：数据集条目构造（schema + 正反例规则）应下沉为 eval 域组件（或至少独立类），本类只负责「校验 → 落库 → 通知」。

### 4.3 双写无事务边界，失败语义与 MemoryService 相反

`submit` 的两步持久化没有一致性协议：DB 成功 + 文件失败 → 异常上抛（:117-119），调用方拿到 500 但 DB 行已在，重试即产生 audit M-3 的重复行；DB 成功 + 文件成功是唯一 Happy path。文件侧定位矛盾：实现上是 best-effort 增值（开关可关 :64、轮转可关 :102），异常语义上却是 fail-request 的强依赖。对照同库样板：`MemoryService.emitEvent` 对事件表 insert 全吞异常（MemoryService.java:152-154）——**同为「主写 + 辅写」结构，一个辅写炸请求、一个辅写全吞，两种哲学无文档**。归位建议：明确文件侧为 best-effort（catch + log + 降级计数），或把双写改为异步解耦（事件/队列），均不改 `submit` 签名。

### 4.4 轮转/保留策略内联在反馈业务里（audit H-1/H-2 的架构归位）

大小轮转（:103-115）本质是**存储生命周期策略**（容量上限、文件命名、清理保留），与「反馈」域无关，却作为私有逻辑长在本类；注释声明的威胁模型（防刷盘）与实现不符（audit H-1：轮转文件永不清理，上限实际 50 MiB × 无限），并发轮转竞态（audit H-2）也源于策略与业务耦合后没有独立的组件边界可测试。归位方向与 4.2 相同：文件写入 + 轮转 + 保留收敛为一个独立组件（如 `FeedbackDatasetWriter`），本类只传数据——修复动作全部落在与直接相关的新文件与本校，不动 `submit` 签名。

## 5. 平行实现（本报告核心发现）

### 5.1 双 sink 并行：DB 表与评测 JSONL 各存半份真相

| 维度 | `answer_feedback` 表 | `feedback_dataset.jsonl` 文件 |
| --- | --- | --- |
| 字段 | 全量（session/branch/message 三个分支链路 ID 在） | 精简 + 评测增强（丢 3 个 ID，加 id/category/keywords） |
| 租户治理 | `idx_answer_feedback_tenant_created`（V8:38-39），可查询可保留策略接管 | 无租户分文件，单文件混流（tenant_id 只是行内字段） |
| 失败语义 | 权威（失败即请求失败） | best-effort 实现、fail-request 异常（4.3 矛盾） |
| 仓内读者 | 无（mapper 纯 insert，无查询端点） | 无（全仓 grep 零消费者） |

两条路径都只写不读，意味着：**当前反馈数据的全部价值兑现都在仓外**，而仓内的「读者缺位」让 schema 漂移（4.2-3）无人发现、轮转缺陷（H-1）无人报警。这不是缺陷爆炸点，但它是「写入路径有测试、消费契约无测试」的结构性盲区。

### 5.2 第三套分词器：`extractKeywords` 不接同库 Tokenizer 家族

类内私有 `extractKeywords`/`isStopWord`（:122-146）：正则 `\p{IsHan}\p{L}\p{N}` 切分 + 硬编码 9 词停用词表 + 取前 6 个。retrieval 包已有成型的分词组件家族（`Tokenizer` 接口 + `RegexTokenizer`/`BigramTokenizer`/`ConfigurableTokenizer`，被 VectorRetriever/KeywordRetriever/GraphRetriever/EvidenceJudgeService 四处消费）——本类是**平行第四处、且不复用 SPI**。后果具体化：audit M-1 已证这套私有实现对中文语料系统性失效（整句成 token），而 retrieval 家族的中文能力（ConfigurableTokenizer）本可直接复用。分词知识无单点 + 失效实现无人共享修复，是典型的平行实现代价。

### 5.3 评测断言词表硬编码在业务代码（与 5.2 同根）

正例禁止词 `["不知道","无法回答","胡编"]`、负例期望词 `["改进","更准确"]`、停用词 9 个（:87/:89/:145）——三组词表全部代码内联。对照同库惯例：RAG 系统提示词入 `SystemConstants`、检索参数入 `RagProperties`、本类自己的路径与容量入 `FeedbackProperties`。词表是三组里最可能按租户/按场景调整的，却是唯一不可配的。归位随 4.2：词表随数据集构造器下沉 eval 域并配置化。

### 5.4 文件写入通道与既有着 FileUtils 类实践无冲突（排除项）

核查过 agent.harness `WorkspaceRuntime` 的文件写入（:98-183）：那是 agent 工作区沙箱读写，与本类数据集追加无语义重叠；全库 JSONL 写入仅本类一处。**不存在可合并的平行文件通道**——本节记录为排除性结论，防止后续审查误报。

## 6. 修复优先级建议（均不改对外 API 签名）

1. **P1** 数据集条目构造（schema 投影 + 正反例规则 + 词表）从本类剥离为 eval 域组件（如 `FeedbackDatasetWriter`/条目工厂），文件轮转与保留策略随同收敛（4.2/4.4/5.3 三案同修）；本类只保留「校验 → 落库 → 委托写」。
2. **P1** 双写失败语义定版（4.3）：文件侧改 best-effort（catch + log + 指标）或异步化，与 `MemoryService.emitEvent` 的哲学差异写成文档；DB 已落 + 文件失败的重试重复行（audit M-3）随语义定版一并消除。
3. **P2** `extractKeywords` 改接 retrieval 包 `Tokenizer` SPI（5.2），消除第三套分词器与中文失效面；词表入 `FeedbackProperties`（5.3）。
4. **P2** 数据集路径绝对化或启动校验（2.1 文件系统出边）：`AppStartupValidator` 式的启动断言（可写、目录存在），消除 CWD 耦合。
5. **P2 记录项**：双 sink 消费契约成文（5.1）——仓内无读者的现状下，至少在 `FeedbackProperties`/类注释写明「JSONL 的唯一消费者是仓外训练管道、answer_feedback 的读路径待建」，让 schema 变更有据可依。

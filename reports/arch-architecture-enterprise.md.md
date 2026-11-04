# 架构分析报告：architecture-enterprise.md 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码与文档；不运行任何 build/mvn；全部结论来自静态交叉审查，每条结论附文档行号与代码位置证据）
- **分析对象**：`docs/architecture-enterprise.md`（53 行：Bounded Contexts :1-8 + Data Ownership :10-20 + Reliability :22-27 + Security :29-37 + Scalability :39-45 + Operational Recommendations :47-53）
- **核查范围**：本文档是全库级总览声明，核查按声明类型分五路——①数据所有权：`grep CREATE TABLE` 全量对 migrations（30 表）逐表核对文档清单；②可靠性：IngestionQueue 三后端（Noop/RedisStream/RabbitMqIngestionQueue 全读）+ DLQ 写读两侧 grep + IngestionService 重试链（:220-268）+ IngestionProperties；③安全性：RefreshTokenService 全读（旋转语义）、RateLimitFilter、AuditRetentionScheduler 覆盖面、SecurityConfiguration；④可扩展性：**grep 全仓 ConcurrentHashMap 找实例内存态反例**（4 处）、pom 依赖对 pgvector/对象存储声明、queue-backend 出厂默认（application.yml:102）；⑤上下文映射：四 context 对包/表分布的覆盖面
- **约束遵守**：未改源码与文档、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（所有修复建议均为文档侧编辑）
- **关联报告**：`audit-architecture-enterprise.md.md`（同文件缺陷审查的一手记录——本报告从边界角度归位不重审）；`arch-AuthController.java.md`（guestCounters 游客限流内联、refresh 签发编排）；`arch-IngestionService.java.md`（幂等键全局 UNIQUE 错配、db_polling 分支无 DLQ）；`arch-TrustedActionService` 经 `arch-architecture-agent-harness.md.md` §4.6（preview token 内存态生命周期）；`arch-WorkflowReactAgentService.java.md` 系（harness⇄workflow 环——3.2 的 agent 子系统环依赖证据）

## 1. 模块定位

本文档是**全库企业级总览（claims 类文档）**：六节交付六类全局声明——上下文划分、数据所有权、可靠性、安全性、可扩展性、运维建议。它的文体特征是**每条都是无行号、无组件名的裸断言**（"Stateless API instances can be horizontally scaled"、"DLQ sink for terminal failures"），这类断言的正确性完全依赖读者自行对码——本次对码结果：**六节中两节（§2 数据所有权、§5 可扩展性头句）存在方向性失实，两节（§3 可靠性、§4 安全性）真实但范围显著窄于表述**，§6 运维建议是全文档最诚实的一节（它甚至预判了 §5 头句的错误，见 4.1）。

**覆盖面边界（成文事实）**：文档没有任何 scope 声明，而四节 bounded contexts 实际只映射了约半数代码——agent/harness/workflow 子系统（全库最大子系统）、evaluation（4 表）、cost governance（2 表）、kg graph（3 表）在上下文地图上不存在（4.2）。

## 2. 依赖全景

### 2.1 出边（文档对代码/资产的声称清单）

| 文档声称 | 位置 | 核实结果 |
| --- | --- | --- |
| §1 四个 Bounded Contexts（:3-8） | 包/表分布 | ⚠️ Conversation/Knowledge/Security 三 context 有真实包与表支撑；**Operations Context 的 alerting/drills（:8）零代码锚点**（指标/日志/MDC tracing 真实存在：application.yml:210 含 request_id/trace_id）；四个 context 均未覆盖 agent 子系统（4.2） |
| §2 MySQL 9 表：conversation/ingestion_job/users/roles/permissions/api_keys/audit_log/course/school/course_reservation（:12-16） | migrations 全量 30 张 | ❌ **9/30**：漏 21 张，含 refresh_tokens（§4 :33 声称 refresh 机制的底座表！）、role_permissions/user_roles（§4 :36 RBAC 的底座表）、memory_item/memory_event（§1 :5 Conversation Context 声称 memory 的底座表）、agent_task/step/event/session_state、eval_*×4、kg_*×3、tenant_budget/tenant_usage_daily、model_ab_exposure、answer_feedback（4.3） |
| §2 Vector Store：knowledge chunks and metadata（:17-18） | pom.xml:186 spring-ai-starter-vector-store-pgvector + VectorStoreProperties | ✅ 真实（simple 后端文件态 SeedDataRunner:53-56 / pgvector 依赖在）——且无 chunk 平行表，向量库确是 chunks 唯一持久层，声明准确 |
| §2 「Local **or object storage**」（:19-20） | IngestionProperties.java:14 `storage-dir=./data/uploads`（yml:107）+ pom 全量 grep | ⚠️ 半实：local 半边真实；**object 半边零锚点**——pom 无 s3/minio/oss 任何依赖、无任何对象存储配置键，「二选一」表述的另一半是纯愿景（4.4） |
| §3 Idempotent ingestion by X-Idempotency-Key（:24） | IngestionController + IngestionService | ✅ 机制真实存在（幂等键模型缺陷见 arch-IngestionService——「幂等」承诺本身是跨租户 DoS 向量，引用不重审） |
| §3 Retry with bounded attempts and delay backoff（:25） | IngestionService.java:220-236 + yml:103-104（max-retries=3, base-delay-seconds=10） | ✅ 有界重试真实；backoff 实为**线性**（baseDelaySeconds × attempts，:226）——「delay backoff」措辞过宽但不算失实 |
| §3 「DLQ sink for terminal failures」（:26） | 三后端分支逐一核实 | ❌ **三种后端没有任何一种有可消费的 DLQ**：db_polling→NoopIngestionQueue.publishDlq 是字面 no-op（:17-19）；redis_stream（**出厂默认**，yml:102）XADD 到 ingestion:dlq（RedisStreamIngestionQueue:48-56）**无 MAXLEN、全仓零读侧**；rabbit DLQ exchange/queue/binding 已声明（IngestionQueueConfiguration:25-38）但**零消费者**。「terminal failures 的 DLQ 汇点」在所有分支上都是只写不读的记账簿（4.5） |
| §3 Queue backend abstraction (Redis Stream / RabbitMQ)（:27） | IngestionQueueConfiguration + 三实现类 | ⚠️ 抽象真实（SPI 三实现 @ConditionalOnProperty 互斥），但枚举漏了第三后端 **db_polling（Noop 队列）**——恰是 DLQ 声明的反例分支（4.5） |
| §4 API Key/JWT/RBAC/route-level（:31-32/:36） | SecurityConfiguration:57-69 + SecurityProperties | ✅ 真实 |
| §4 Refresh token rotation（:33） | RefreshTokenService.java:46-58（consume 内 conditional revoke，仅首个调用者翻转 revoked_at） | ✅ **真旋转**：一次性 token + 条件撤销防并发重放，签发走 issue() 换新——该表（refresh_tokens）却不在 §2 清单（4.3） |
| §4 Tenant-scoped key lifecycle（:34） | AuthController.java:204-228（rotate/revoke 按租户） | ✅ 真实 |
| §4 「single-instance in-memory buckets」（:35） | RateLimitFilter.java:28 buckets | ✅ **诚实自认**——但文档没说的是限流器有两个（AuthController.guestCounters :105-106 游客限流独立内联），且 §5 头句与本条内部矛盾（4.1） |
| §4 Audit log retention scheduler（:37） | AuditRetentionScheduler.java:16-24（每日 3:20 清 audit_log） | ⚠️ 真实但**只覆盖 audit_log 一张表**——memory_event/agent_event/answer_feedback 等同类流水表无任何保留调度（4.6） |
| §5 「Stateless API instances can be horizontally scaled」（:41） | 全仓 ConcurrentHashMap grep | ❌ **四处实例内存态**：TrustedActionService.pendingActions（:21，**硬功能断裂**——preview→execute 是两次 HTTP 请求，token 跨实例不可见，多实例下受信动作链路直接失效）、RateLimitFilter.buckets（:28，§4 自认）、AuthController.guestCounters（:105，游客限流/计数）、InMemoryChatHistoryRepository（:21，被 @Primary Mysql 实现遮蔽未上主路）（4.1） |
| §5 Async ingestion decouples（:42） | IngestionWorker + queue SPI | ✅ 真实 |
| §5 Vector backend can switch to pgvector（:43） | pom.xml:186 + yml:12-13（pgvector autoconfig 默认排除）+ SeedDataRunner:53 | ✅ **声明为真**（依赖在、属性切换在、默认 simple）——全文档罕见的有代码完全支撑的架构演进声明 |
| §5 Model router profile-based（:44） | ModelRouter | ✅ 真实 |
| §6 五条运维建议（:49-53） | — | ✅ 与代码实况自洽，其中「Use a shared rate-limit backend before horizontally scaling protected endpoints」（:51）正确预判了 §5 头句的条件性——但它只救限流偏差，救不了受信动作链路的功能断裂（4.1） |

### 2.2 入边（谁消费本文档描述的架构）

- 无代码/测试/脚本引用本文档（grep 证实）——它是纯声明性文档，无回归锚点；其断言的维护完全靠人工对码（本报告即对码产物）。
- 阅读路径位置：作为 enterprise 总览，它是新人理解「系统承诺什么」的第一入口，其失实声明（4.1/4.5）会被后续所有架构决策引用。

## 3. 循环依赖评估

- **文档引用图无边**：单文件、零外链、零被引——无文档级环。
- **「Bounded Contexts」地图的空白恰是环依赖所在地（结构性发现）**：四 context 划分暗示 context 间低耦合，但全库已定位的两组包级环——harness⇄workflow（HarnessEventRecorder→AgentWorkflowEngine + WorkflowReactAgentService→harness）与 service⇄harness⇄workflow 三角——全部位于**地图上不存在的 agent 子系统**。地图内的三个 context（Conversation/Knowledge/Security）之间实际是干净单向（harness→rag 向下、controller→security 向下）。也就是说：**上下文划分对它覆盖的区域是成立的，问题在于它没划分的区域恰是全库架构债的集中地**——「地图没有画迷宫，因为迷宫不在地图上」。修复位：§1 增补第五 context（Agent/Orchestration）并把已知环标注为其内部债务（3 处 arch 报告已定型修复方案）。
- 无数据所有权级环（表间无双向写入关系；conversation/agent_session_state 各自独立）。

## 4. 越层评估

（总览类文档的「越层」=声明越过实现强度/覆盖范围。）

### 4.1 「Stateless API instances can be horizontally scaled」是无条件的头句承诺，实况有条件且有一处硬断裂（核心）

§5 :41 是全文档唯一的加粗级架构承诺，逐一对实例内存态：

1. **TrustedActionService.pendingActions（硬断裂）**：受信动作协议是 preview 生成 token → execute 消费 token（两次独立 HTTP 请求，TrustedActionService.java:21 内存 Map、TTL 10min）。多实例部署下两请求落不同节点即「token not found」——这不是限流偏差类的量级失真，是**一条完整业务链路在水平扩展下功能性失效**。§5 头句没有任何限定，§6 :51 的补救建议也只谈 rate-limit backend，不覆盖此处；
2. **RateLimitFilter.buckets**（:28）：§4 :35 自己承认 single-instance——即文档内部 §5 与 §4 就同一事实给出相反读感（§5 说可以随便扩，§4 说扩前要先做 §6 :51 那件事）；
3. **AuthController.guestCounters**（:105-106）：第二个实例内限流器，文档完全未提——修复 §6 :51 时如果只迁 RateLimitFilter 会漏掉它；
4. InMemoryChatHistoryRepository（:21）：被 @Primary 的 MysqlChatHistoryRepository 遮蔽，不在主路——记录不计数。

修复位：§5 :41 改条件句——「主 chat/RAG/ingestion 读取路径无实例态可水平扩展；受信动作 preview/execute 与限流桶为实例内存态，扩展前需外置（§6 :51）」。纯文档编辑；受信 token 外置（Redis）属代码 backlog。

### 4.2 Bounded Contexts 地图缺最大子系统（结构性越层）

§1 四 context 未映射 agent/harness/workflow（全库最大子系统，含三张 agent_* 表）、evaluation（4 表）、cost governance（2 表 + TenantCostService）、kg graph（3 表）——合计约 12 张表与两条已定位的包级环不在任何 context 内。后果：读者按此地图做模块归属判断时，会把 agent 子系统误归入 Knowledge 或 Conversation context（rag_search 工具确实横跨两者）。修复位：§1 增 Agent/Orchestration 与 Evaluation/Governance context（与 3.1 同一批编辑）。

### 4.3 数据所有权 9/30：漏掉的恰是自己声明过的机制底座

§2 清单漏 21 表，其中三类漏法比「不完整」更伤：①**§4 声称的机制底座表**——refresh_tokens（:33 rotation）、role_permissions/user_roles（:36 RBAC）机制在、表不在；②**§1 声称的 context 底座表**——memory_item/memory_event（:5 memory 归 Conversation Context）context 在、表不在；③**成本治理整域**——tenant_budget/tenant_usage_daily（TenantCostService 断路器的底座）完全无归属。读者无法从 §2 得知「哪些数据是我要备份/迁移/合规审计的」。修复位：§2 补全 30 表或按 context 分组列全（与 4.2 的 context 增补天然同构——按新 context 分组列表）。

### 4.4 「Local or object storage」的二选一有一边是纯愿景

:19-20 的「or」暗示两条路径都存在。实况：local 唯一（IngestionProperties:14 `./data/uploads`，yml:107），pom 无任何对象存储依赖（grep s3/minio/oss/oss 零命中），无任何存储抽象接口。「or」应改「(object storage: planned)」或直接删掉半边——二选一表述在依赖缺失时会把「未实现」读成「已支持」。

### 4.5 「DLQ sink」按后端分支全灭

:26 的声明需按 §3 :27 自己列的后端逐一兑现，结果三种全不成立：db_polling 分支 publishDlq 字面 no-op（NoopIngestionQueue:17-19）；redis_stream（出厂默认 yml:102）只写不读且无 MAXLEN（死信流无限增长 + 永无消费）；rabbit 分支拓扑齐全但零消费者。**「DLQ sink」的真实语义是「DLQ 写入点」**——死信的存续（无 maxlen）与处置（无读侧）两端都缺。修复位：:26 改「terminal failures 写入 DLQ（redis/rabbit）；读侧消费与 db_polling 后端的 DLQ 为缺口」——或者诚实地把该行降级为 backlog 项。与 arch-IngestionService 的 claimIdle 毒消息环合并看，ingestion 可靠性声明的缺口是系统性的。

### 4.6 审计保留调度覆盖面窄于「Audit log retention scheduler」的读感

:37 声明真实（AuditRetentionScheduler 每日 3:20 清 audit_log），但同类事件流水表 memory_event、agent_event（含受信动作审计事件！）、answer_feedback 无任何保留/清理调度——长期运行的栈上这些表只增不减，其中 agent_event 恰是 4.5 死信缺口之外另一处「审计承诺 vs 存储现实」的落差。修复位：:37 补「仅 audit_log 表」限定。

### 4.7 存量正面项（无修复）

- Refresh token rotation（:33）是真旋转而非仅「有 refresh token」——conditional revoke（RefreshTokenService:52-56 注释自证防并发重用）是该声明超出平均实现质量的证据；
- pgvector 切换声明（:43）有 pom 依赖 + 属性开关 + 默认排除的完整锚链；
- §4 :35 主动承认限流单实例局限——总览文档罕见的自我限定；
- §6 五条建议全部与代码实况自洽，:51 更是预判了 §5 头句的条件性（文档内部最诚实的三行）；
- 幂等/重试/有界 backoff 三件套真实存在（缺陷在模型层，arch-IngestionService 已记）。

## 5. 平行实现

1. **限流器双副本**：RateLimitFilter（:28，composite 键）与 AuthController.guestCounters（:105-106，游客端点专用）——两个实例内限流器无共享抽象，§6 :51 的「shared rate-limit backend」修复若只指向其一即漏另一（arch-AuthController 已记 guest 限流内联成因）；
2. **ChatHistoryRepository 双实现**：InMemoryChatHistoryRepository 与 @Primary MysqlChatHistoryRepository 并存——内存版是被遮蔽的平行实现（零 conditional、靠 @Primary 裁决），属「旧实现未退役」型存量；与本系列「抽取件已存在但不接」同型，方向相反（新实现已接、旧实现未删）；
3. **文档-代码枚举双轨 ×2**：队列后端（文档 2 种 vs 代码 3 种）与数据表（文档 9 张 vs 代码 30 张）——总览文档的清单类声明全部落后于代码实况，且无任何机制（测试/CI/文档生成）保证同步；
4. **DLQ 语义双轨**：:26 的「sink」（有汇点有处置的读感）vs 代码的「写入点」（只写）——词义平行于实现漂移的微型样本；
5. **引用存量结论（不重审）**：幂等键全局 UNIQUE vs 租户域查询错配、claimIdle 毒消息环（arch-IngestionService）；resolveClientIp 与 clientIpOf 双信任模型（arch-AuthController）。

## 6. 修复优先级建议（均为文档侧编辑，不改任何对外 API 签名；改动仅限本文件）

1. **P1** §5 :41 无条件「Stateless」改条件句，点名受信动作 preview/execute 链路的多实例断裂与两处限流实例态（4.1）——这是会让架构决策（部署拓扑）直接踩坑的头句；
2. **P1** §3 :26 DLQ 声明按后端分支改写为「写入点已有、读侧消费缺失、db_polling 无 DLQ」（4.5），或降级为已知缺口；
3. **P2** §2 数据所有权补全 30 表并按 context 分组（4.3），同步 §1 增补 Agent/Orchestration 等 context 并标注已知包级环（3.1/4.2 同批编辑）；
4. **P2** §3 :27 队列后端枚举补 db_polling（4.5）；§4 :37 保留调度补「仅 audit_log」限定（4.6）；
5. **记录项**：§2 :19-20 「or object storage」删半边或标 planned（4.4）；§1 :8 alerting/drills 无代码锚点标注（2.1）；限流器双副本与 InMemoryChatHistoryRepository 退役属代码侧 backlog（5.1/5.2）；保留 §4 :35 自我限定与 §6 全节作为改写锚点（4.7）。

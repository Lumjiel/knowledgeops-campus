# 架构分析报告：IngestionService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/ingestion/IngestionService.java`（383 行）
- **核查范围**：全部 9 个注入协作者；`IngestionJobMapper` 全部 8 条 SQL 逐条下钻（claim CAS / 终态更新 / 重试拾取）；`V1__init_core_and_platform_tables.sql:36-57` 建表 DDL 与唯一键；全部 6 个调用方（2 controller + 1 config 引导 + 包内 worker/listener，grep 证实）；`IngestionWorker` 全文（两套 `@Scheduled` + redis 消费循环 + claimIdle 重投递）；`ingestion.queue` 全部 5 文件的条件装配（`@ConditionalOnProperty` 三后端互斥）；`application.yml:101-122` 后端默认值与全部覆盖键；既有 `audit-IngestionService.java.md` 的发现承接
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-IngestionService.java.md`（同文件缺陷审查，H-1 相对路径落盘/M-1 僵尸 RUNNING/M-2 非原子两步/M-3 重试重复 chunk 等以该报告为准）、`arch-RagAnswerService.java.md`（sharedCorpusChatId 语义散布的 4 处清单，本报告升级为 5 处）、`architecture-enterprise.md` 审查（三后端 DLQ 覆盖缺口的结论引用）

## 1. 模块定位

ingestion 包 = 文档摄取子系统：上传受理（幂等）、异步处理（claim-处理-终态）、重试调度、队列抽象（`queue/` 子包 5 文件）。`IngestionService` 是该子系统的门面，四块职责：

1. **受理**：`submitPdf`（:63-116）——安全扫描→幂等查询→落盘→insert→发布队列，`DuplicateKeyException` 兜底并发重复（:109-115）；
2. **查询**：`getByJobId`/`listByChatId`/`listBySourceAcrossScopes`（:118-143），全部租户过滤；
3. **处理**：`processQueuedJob` 两个重载（:145-259）——claimForRun CAS 认领→PDF 解析切分→`vectorStore.add`→终态写回，异常路径自带 RETRY/FAILED 状态机与 DLQ；
4. **重试入队**：`enqueueReadyRetries`（:261-275）。

**调用方 6 处**（grep 全库证实）：

| 调用方 | 调用什么 | 线程上下文 |
| --- | --- | --- |
| `IngestionController`（:45/:56/:66/:76/:87） | 受理/查询/管理处理 | HTTP 请求线程，MDC 有租户 |
| `PdfController`（:51/:79-80） | 受理/跨作用域查询 | HTTP 请求线程 |
| `config.SeedDataRunner`（:82-83） | 受理+同步处理（引导种子数据） | 启动线程，显式传 tenantId |
| `ingestion.IngestionWorker`（:81/:108/:133） | 消费/重试入队/db_polling 轮询 | 后台线程池与 @Scheduled，无 MDC |
| `queue.RabbitMqIngestionListener`（:43） | rabbit 消费 | rabbit 容器线程，无 MDC |

## 2. 依赖全景

### 2.1 出边（9 个注入协作者 + 静态依赖）

| 依赖 | 所在包 | 方向评价 |
| --- | --- | --- |
| `IngestionJobMapper` | mapper（中央包） | 垂直域 mapper 放中央包，与「垂直模块自持 mapper」惯例（agent/workflow/graph/memory/evaluation）不一致——轻度偏离，无环 |
| `VectorStore` | Spring AI 接口 | 部署期边界；另 `instanceof SimpleVectorStore` 嗅探（:320），见 4.3 |
| `IngestionProperties` ×1、`VectorStoreProperties`、`RagProperties` | config.properties | 叶子；其中 `RagProperties.getSharedCorpusChatId()`（:132）是跨子系统语义耦合，见 5.3 |
| `MeterRegistry` | micrometer | 横切：2 Timer + 4 Counter，tag 带 tenant（高基数，audit L-6 已记） |
| `IngestionQueue` | ingestion.queue（包内 SPI） | 端口在提供方包内定义、实现经 `@ConditionalOnProperty` 互斥装配——六边形方向正确 |
| `FileSafetyScanner` | security（**接口**） | 依赖抽象，实现 `DefaultFileSafetyScanner` 容器装配——方向正确 |
| `TenantContext`（静态） | security | 入口 normalize（:64/:119/:123/:128/:166-167） |
| `HashUtils` | util | 幂等键内容哈希（:369-370） |
| `MDC`（静态） | slf4j | `processQueuedJob` 的租户 fallback（:167），见 4.2 |

### 2.2 入边

- main 源码 6 处（第 1 节表格）。`IngestionProcessResult`/`IngestionJob` 作为对外类型被 3 个包消费。
- `queue/` 子包被 service、worker、listener 三方消费，三后端实现互斥装配（`NoopIngestionQueue` ← db_polling / `RedisStreamIngestionQueue` ← redis_stream / `RabbitMqIngestionQueue` + `IngestionQueueConfiguration` ← rabbitmq），yml 默认 `redis_stream`（application.yml:103）。

### 2.3 横切依赖

无事件发布、无 Agent/工作流耦合。文件系统（`storage-dir`）与向量库快照文件（`simpleStorePath`）是两条部署期边界，均在本类内收口。

## 3. 循环依赖评估

**Bean 级与包级均无环。**

- Bean 图：mapper/properties/queue SPI/security 接口/util 全部不回指 ingestion；`IngestionWorker`→`IngestionService` 是包内单向，listener→service 同向。
- 包图：`controller → ingestion`、`config(SeedDataRunner) → ingestion`、`ingestion → security/mapper/util`，全部单向。对 agent⇄workflow 既有环零参与。
- 结构上这是一个**自包含子系统**的正面样板：端口（IngestionQueue/FileSafetyScanner）都在消费方或独立包定义，后台执行器（worker/listener）与门面同包但不形成反向边。
- 唯一注意点：`SeedDataRunner`（config 包）→ `submitPdf`+`processQueuedJob` 是引导代码直连业务门面——方向合法（config 是组装层），且它显式传租户（:83），不依赖 MDC。

## 4. 越层评估

### 4.1 幂等键的「全局 UNIQUE」与租户域语义错配（本报告核心发现）

服务层的幂等模型是**租户域**的：查询按 `(tenant_id, idempotency_key)`（:75/:110），claim/终态 SQL 全带租户条件。但建表 DDL 是**全局**唯一的：

```sql
-- V1__init_core_and_platform_tables.sql:46
idempotency_key VARCHAR(128) NOT NULL UNIQUE,   -- 无 tenant_id 前缀
```

两类键的实际暴露面不同：

- `auto:` 键 = `sha256(tenantId|chatId|contentHash)`（:370），租户参与哈希，跨租户天然不撞；
- `client:` 键 = `"client:" + provided.trim()`（:366），**裸键直接进全局唯一池**。

后果链：租户 B 用与租户 A 相同的 `X-Idempotency-Key` 上传 → 租户域查询（:75）查不到（正确）→ insert 撞全局 UNIQUE → `DuplicateKeyException` → 兜底查询（:110）仍查不到 → **重抛，上传确定性失败**。这不是理论边界：`uploadPdf` 端点无 `@PreAuthorize`（IngestionController:40-45），任何已认证租户只要提供与别租户相同的 client key 即可持续 DoS 对方的文件上传，且不需要知道对方租户 ID。修复（不改对外签名）：唯一键改 `(tenant_id, idempotency_key)` 复合唯一，或 `client:` 键入库前拼租户前缀——后者与本类 `normalizeIdempotencyKey` 的既有职责完全吻合。

### 4.2 三类线程的租户上下文策略——全库最自觉的处理，但有一处静默兜底

`processQueuedJob(jobId, tenantId, traceId)` 重载（:149-167）的注释（:159-164）明文交代了三条路径的租户来源：HTTP 走 MDC、后台 worker/listener 显式传 `job.getTenantId()`（worker:77-80、listener:33-36 的注释同样自觉）。**这是对 ReactAgentService「defer 内读 MDC」缺陷的正面样板**：租户边界不依赖线程上下文的隐式传播，SQL 层（claimForRun `WHERE tenant_id=#{tenantId}`）还有第二道闸。

保留的缝隙：MDC fallback（:167）在 HTTP 直触发路径（IngestionController:87）上依赖 `RequestContextFilter` 已写入 MDC——与 PdfController 的取值方式同构，属全库既有模式；且该入口前置了租户校验（:83 先 `getByJobId` 再 process），实际暴露面收敛。claim 失败返回 `status=RUNNING`（:173，audit L-4）在租户探测场景信息量极小（能进到这步的 jobId 必已通过 ：83 校验或来自队列消息）。

### 4.3 `instanceof SimpleVectorStore` 后端嗅探——部署边界知识渗透进服务层

`persistSimpleVectorStoreIfNeeded`（:319-349）同时检查运行时类型（:320）与配置后端（:323）双重条件才落快照。服务层知晓具体 VectorStore 实现类是类型边界的渗透：新增向量库后端无需改本类（instanceof 兜住），但「simple 后端需要手动快照」这一部署知识以代码而非配置表达。配套约束：`snapshotLock`（:61/:336）只串行化**本进程**写——多实例部署下 SimpleVectorStore 快照互相覆盖（arch-enterprise 的「无状态性」反例同型）。风险受限（SimpleVectorStore 是内存/dev 后端，与生产后端互斥），记录为边界扩产提示。

### 4.4 管理端点的租户盲区（权限域选择，记录项）

`processOne` 无 jobId 分支（IngestionController:75-81）触发 `enqueueReadyRetries`，其 SQL（`findReadyRetries`/`requeueRetry`，mapper:90-108）**无租户过滤**——ADMIN 一次重排全库所有租户的到期 RETRY。查询与处理处处租户过滤的类内惯例在此让位于运维语义，`@PreAuthorize("hasRole('ADMIN')")`（:73）是唯一闸门。与 4.1 对比可见本类的租户边界是「按方法逐个决定」而非系统级不变量。

## 5. 平行实现与结构重复

### 5.1 三后端分支散落 6 处，后端特化知识无单点

`if ("db_polling")`/`"redis_stream"` 字符串分支分布在 **2 个类 6 处**：本类 `submitPdf`（:100，跳过发布）、`enqueueReadyRetries`（:262，直接返回 0）、`IngestionWorker.start`（:42，只对 redis 起消费池）、`enqueueRetryJobs`（:105，db_polling 跳过）、`pollDatabaseJobs`（:123，只对 db_polling 启用）。触发模型事实上是三轨制：

| 后端 | 新任务投递 | 消费 | 重试 | DLQ |
| --- | --- | --- | --- | --- |
| redis_stream | `publishJob` | worker 消费循环 + claimIdle 重投递 | `enqueueReadyRetries` @Scheduled | publishDlq（只写不读，arch-enterprise 已证） |
| rabbitmq | `publishJob` | `@RabbitListener` | 同上 | x-dead-letter-exchange 自动 + publishDlq 双通道（同样只写不读） |
| db_polling | 不发布（:100） | `pollDatabaseJobs` 轮询 findNextReadyJob | 轮询直接拾取 RETRY（故 :262 返回 0） | **Noop 吞掉**——FAILED 任务无任何死信痕迹 |

分支本身互斥自洽，但「新加第四个后端要同步改 6 处 + 记住 3 张表」没有机制保障。收敛方向：把「投递/重试/死信」能力声明为 `IngestionQueue` 端口的方法（或能力探测），让分支退回装配层。

### 5.2 RUNNING 僵尸与 claimIdle 毒消息环（audit M-1 的架构延伸）

`claimForRun` 的状态闸是 `status IN ('PENDING','RETRY')`（mapper:55），**无 RUNNING 超时回收**。worker 崩溃在 claim 之后、终态之前 → 任务永久 RUNNING。这不仅是「无法重跑」：

1. redis 端 `claimIdle`（worker:66-70）专为复活 crashed worker 的滞留消息设计，但复活后 `processQueuedJob` 的 DB claim 拒绝 RUNNING → `picked=false` → **不 ack**（worker:83-87 只 ack picked=true）→ 消息永挂 PEPL，每 `claim-idle-ms`（默认 30s）重演一次，无 max-delivery 上限——**毒消息无限循环**；
2. db_polling 的 `findNextReadyJob`（mapper:37-44）同样只拾取 PENDING/RETRY，无法回收 RUNNING。

跨层修复位（不动 `IngestionService` 对外签名）：DB 侧给 claim 加「RUNNING 且 started_at 超过租期」的回收条件（lease 语义，正好落在 `claimForRun` 的 WHERE 里）；或 worker 对 `picked=false && status==RUNNING` 的消息 ack/转 DLQ，掐断循环。

### 5.3 `sharedCorpusChatId` 语义第 5 处消费点

`listBySourceAcrossScopes`（:131-132）读取 `ragProperties.getSharedCorpusChatId()` 实现「会话级 + 共享语料」两层作用域——与检索端的过滤谓词（RagAnswerService/VectorRetriever/KeywordRetriever 共 4 处，arch-RagAnswerService 5.1 已记）共同构成**同一语义知识的第 5 个分散点**，且横跨 rag 与 ingestion 两个子系统：共享语料的键名或语义一变，爆炸半径是「3 个检索类 + 1 个摄取服务 + 1 个配置键」。写入端单点是优点：检索过滤依赖的全部向量元数据键（`tenant_id/chat_id/chunk_index/file_name`，:307-312）在本类 `splitDocuments` 唯一写入——读散写聚，契约单侧收敛，方向正确但值得成文。

### 5.4 轻度平行

- `processQueuedJob` 两个重载（:145-147 委托 :149）——worker/listener/seed 走显式租户重载，HTTP 走 MDC 重载，分工清晰非漂移，正面。
- 终态写回 `updateTerminalState`（:194-201/:230-237）两处调用共享一条无条件 SQL（仅 job_id，无状态守卫，mapper:70）——对照 `claimForRun` 的 CAS 是「认领严、落终态宽」。当前安全（RUNNING 不可被二次 claim，单一 claimer 才能走到终态写），但若 5.2 引入 lease 回收（允许超时 RUNNING 被重新 claim），终态写回必须同步加状态守卫，否则慢 worker 的迟到终态会覆盖新 claimer 的进行中状态——**两条 SQL 的守卫强度差是 lease 改造的隐藏前置**。
- 重试延迟计算 `baseDelaySeconds * max(1, attempts)`（:226）线性退避，与注释无关的自有策略，无平行副本。

## 6. 修复优先级建议（均不改对外 API 签名）

1. **P0** 幂等唯一键租户化：DDL 改 `(tenant_id, idempotency_key)` 复合唯一，或 `client:` 键入库前拼租户前缀（4.1）——前者需迁移脚本，后者一行改动即闭合 DoS 面。
2. **P1** RUNNING 僵尸治理：`claimForRun` 增加租期回收条件 + `updateTerminalState` 加状态守卫（5.2/5.4，两改必须同批）；redis worker 对不可认领消息终止重投。
3. **P1** 三后端分支收敛到 `IngestionQueue` 端口能力声明（5.1），DLQ「只写不读」在端口契约层面显式化为 `NoopDlqSink` 之类的诚实命名。
4. **P2** 垂直域 mapper 归位（`IngestionJobMapper` 迁入 ingestion 包，纯移动无签名变化）；`sharedCorpusChatId` 消费点清单成文并与检索侧收敛方案合并推进（5.3）。
5. **P2 记录项**：SimpleVectorStore 快照的多实例约束、管理端点租户盲区、`rag.*` 之外 `ingestion.jobs.*` 指标租户基数。

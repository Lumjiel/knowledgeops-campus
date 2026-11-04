# 审查报告：docs/architecture-enterprise.md

- **审查日期**：2026-11-04
- **审查方式**：只读通读全文，将文档每一条声明逐一对码核实（迁移 SQL、Java 源码、application*.yml、pom.xml），未改动任何源码，未 git commit/push，未删除文件，未执行 npm install / build / mvn 构建。
- **协作者清单**（结论均 grounded 到以下文件）：
  - 迁移 SQL：`src/main/resources/db/migration/V1__init_core_and_platform_tables.sql`、`V9__conversation_ingestion_tenant_isolation.sql`（全 30 张表名经 V1–V17 提取核对）
  - 摄取链路：`IngestionController.java`、`IngestionService.java`、`IngestionWorker.java`、`ingestion/queue/`（`IngestionQueue` / `NoopIngestionQueue` / `RedisStreamIngestionQueue` / `RabbitMqIngestionQueue` / `RabbitMqIngestionListener` / `IngestionQueueConfiguration`）
  - 安全组件：`RateLimitFilter.java`、`RefreshTokenService.java`、`AuditRetentionScheduler.java`、`AuthController.java`、`TrustedActionService.java`、`AgentHarnessController.java`
  - 向量与路由：`VectorStoreConfiguration.java`、`VectorStoreProperties.java`、`llm/ModelRouter.java`
  - 存储实现：`InMemoryChatHistoryRepository.java`、`MysqlChatHistoryRepository.java`、`LocalPdfFileRepository.java`
  - 配置：`application.yml` / `application-dev.yml` / `application-prod.yml`、`pom.xml`
- **关联报告**：`reports/audit-api-recipes.md.md`、`reports/audit-architecture-agent-harness.md.md`、`reports/audit-architecture-agent-workflow.md.md`、`reports/audit-IngestionService.java.md`、`reports/audit-ModelRouter.java.md`、`reports/audit-TenantCostService.java.md`

## 概述（先说做对的部分）

文档整体短小克制，多数可验证声明与代码一致：

1. **幂等声明属实且实现完整**：§3 "Idempotent ingestion by X-Idempotency-Key" 落地于 `IngestionController.java:43`；`IngestionService.normalizeIdempotencyKey`（`IngestionService.java:364`）在客户端未提供键时用 tenant|chatId|内容哈希自动派生兜底；建表 `V1:43` 为 `idempotency_key NOT NULL UNIQUE`，`V9:16` 升级为 `(tenant_id, idempotency_key)` 复合唯一键，`IngestionService.java:109-115` 还有 `DuplicateKeyException` 后回查的并发兜底。这是全文档最扎实的一条。
2. **限流声明准确**：§4 "Tenant + principal composite rate limiting (single-instance in-memory buckets)" 与 `RateLimitFilter.java:58-64`（键为 `tenant:{id}:principal:{name}` 或回退 IP）及 `RateLimitFilter.java:28`（`ConcurrentHashMap<String, Bucket>`）完全一致，且文档诚实地标注了 single-instance，并在 §6（`architecture-enterprise.md:51`）主动警告"先换共享后端再水平扩容"——自洽。
3. **刷新令牌轮换属实**：`RefreshTokenService.consume`（`RefreshTokenService.java:43-54`）用条件性 revoke（`revoke(...) == 0` 即拒绝）实现一次性消费 + 重新签发，防并发重放。
4. **pgvector 可切换属实且默认 fail-fast**：`VectorStoreConfiguration.java:21-34` 按 `app.vector-store.backend` 分支；`require-pgvector` 默认 `true`（`VectorStoreProperties.java:10`、`application.yml:154`），初始化失败直接抛异常而非静默降级到内存版 SimpleVectorStore。
5. **模型路由声明属实**：§5 "profile-based cost/quality routing" 对应 `ModelRouter.java:128-133` 的 profile→model/costTier 解析与 fallback 链。
6. **后台 worker 的租户隔离没有掉链子**：`IngestionService.processQueuedJob`（`IngestionService.java:159-169`）对无 MDC 的后台线程显式传 job 属主租户，`claimForRun` SQL 带租户校验——文档 §4 的租户主张在异步路径上也成立。
7. **队列抽象确实存在**：`IngestionQueue` 接口 + Redis Stream / RabbitMQ 两个真实实现（但见 M3 的遗漏）。

## 高严重度

### H1. §5 "Stateless API instances can be horizontally scaled" 与三类实例内存态直接冲突

- **位置**：`docs/architecture-enterprise.md:41`（声明本身）；反证在 `TrustedActionService.java:16-21`、`AuthController.java:105-125`、`RateLimitFilter.java:28`
- **描述**：文档把"无状态可水平扩展"写成无条件结论，但代码里至少有 4 处实例本地状态：
  1. `TrustedActionService.java:21` 的 `pendingActions = new ConcurrentHashMap<>()`，TTL 仅 10 分钟（`:16`），纯内存。而 harness 的信任动作流程是**两次独立 HTTP 请求**：`POST /agent/harness/actions/preview` 签发 token（`AgentHarnessController.java:40-43`）、`POST /agent/harness/actions/execute/{token}` 消费 token（`AgentHarnessController.java:48-51`）。
  2. `AuthController.java:105-106` 的 `guestCounters`（游客令牌每 IP 每小时 30 个的固定窗口限流，`:104`、`:115-125`）。
  3. `RateLimitFilter.java:28` 的内存桶——§4 自己承认是 single-instance，但 §5 的"无状态"结论没有把这一前提排除在外。
  4. （次要）`InMemoryChatHistoryRepository.java` 虽无活跃注入点（`MysqlChatHistoryRepository` 是 `@Primary`，见类头注释 `:16-18`），但仍是注册 bean。
- **触发条件**：任一运维按 §5 把 API 部署到 ≥2 实例（或滚动重启单实例）后：① 用户在实例 A preview、负载均衡把 execute 路由到实例 B → token 不可见，动作永远无法确认；② 游客令牌配额变成每实例 30/h，全局 ×N；③ 重启即丢失全部在途信任动作与限流/配额计数。
- **影响**：这是企业架构文档里最容易被直接照做的一句话；按它扩容会产生"偶发无法确认的高危动作"这类难排查的跨实例故障。
- **修复建议**：文档侧把 §5 改为条件式声明，并内嵌"实例本地状态清单"（trusted-action token、guest 配额、限流桶）作为扩容前置检查项。代码侧（不改任何对外 API 签名）：把 `TrustedActionService` 的 token 迁到 DB 或 Redis（agent_harness 已有 MySQL/Redis 可复用），guest 配额可与 `RateLimitFilter` 同后端。§6 已有"shared rate-limit backend"一句，可顺势扩成统一的"实例外置状态"小节。

### H2. §3 "DLQ sink for terminal failures" 在三种队列后端下只有两种有真实 sink，且全部只写不读

- **位置**：`docs/architecture-enterprise.md:26`；反证在 `NoopIngestionQueue.java:17-20`、`RedisStreamIngestionQueue.java:48-55`、`IngestionQueueConfiguration.java:25-38`
- **描述**：`IngestionService.java:248-249` 确实在终态 FAILED 时调用 `publishDlq`，但按后端分三种情况：
  1. `db_polling`（**dev 默认后端**，`application-dev.yml:8`）：绑定的队列实现是 `NoopIngestionQueue`，`publishDlq` 是空方法——终态失败不留任何 DLQ 痕迹（DB 里只有 `ingestion_job.status=FAILED` 一行）。
  2. `redis_stream`（出厂默认，`application.yml:103`）：`publishDlq` 写入 `ingestion:dlq` 流，但**全仓库没有任何消费者、回放工具或告警**引用该 key；且 `RedisStreamIngestionQueue` 全文没有 XTRIM/MAXLEN，DLQ 流（连同主流）无限增长。
  3. `rabbitmq`：durable DLQ 队列经 DLX 绑定（`IngestionQueueConfiguration.java:25-38`），同样无监听者，无队列长度上限。
- **触发条件**：① 任何终态失败 + db_polling 后端；② redis_stream/rabbitmq 长期运行且存在反复失败的 job。
- **影响**："DLQ sink"给读者"失败会被兜住、可事后处置"的印象，实际它是一个只进不出的死信堆：既没有人消费（失败原因永远不会被复盘处置），也没有上限（Redis 流无限增长挤占内存）。
- **修复建议**：文档如实标注当前形态——"DLQ 仅落盘/落流，无自动消费与告警；db_polling 后端无 DLQ"。代码侧最小改法：redis_stream 给主流与 DLQ 流加 MAXLEN 修剪 + 一个供运维查询 DLQ 内容的只读端点；RabbitMQ 同理加 basic.get 或告警集成。不建议在本轮改动消费语义。

## 中严重度

### M1. §2 Data Ownership 清单漏掉过半 schema，恰漏了隔离与状态机关键表

- **位置**：`docs/architecture-enterprise.md:10-20`
- **描述**：V1–V17 实际建了 30 张表，文档 MySQL 一节只列了 9 类。遗漏：`refresh_tokens`、`user_roles`、`role_permissions`、`agent_task/agent_step/agent_event/agent_session_state`、`memory_item/memory_event`、`eval_case/eval_dataset/eval_run/eval_result`、`kg_entity/kg_fact/kg_relation`、`answer_feedback`、`model_ab_exposure`、`tenant_budget/tenant_usage_daily`。其中 `tenant_budget/tenant_usage_daily`（租户成本隔离）、`agent_session_state`（带 `V16` lock_version 乐观锁的会话状态）正是"数据主权"章节最该交代的所有权/一致性属性。
- **触发条件**：读者据 §2 推导备份策略、分库分表或租户数据导出方案时。
- **影响**：按文档清单导出"全部业务数据"会漏掉 agent 全链路、评测、记忆与计费数据；做 DR 演练会漏表。
- **修复建议**：至少把"agent_*、memory_*、eval_*、kg_*、tenant_*"按域补进 §2，并为 `agent_session_state` 标注乐观锁、为 `tenant_usage_daily` 标注唯一键幂等这两条结构性事实。

### M2. §2 "Local or object storage" 中的 object storage 并未实现，且与 §5 组合出未提示的共享文件问题

- **位置**：`docs/architecture-enterprise.md:19-20`；反证在 `pom.xml`（无 s3/oss/minio 依赖）、`LocalPdfFileRepository.java`、`IngestionService.java:333-343`
- **描述**：上传文件一律 `Files.move` 落本地磁盘（原子移动到本地目录），代码库无任何对象存储依赖或抽象。写成 "Local or object storage" 会让读者以为对象存储是可选后端。另外：多实例 + 本地盘意味着"上传在实例 A、摄取 worker 在实例 B"时 B 读不到文件——`IngestionService.processPdfJob`（`:277` 起）直接 `new File(job.getFilePath())`。
- **触发条件**：API 与 worker 分开部署或多副本部署时。
- **影响**：摄取任务在异构节点上必然 file-not-found；文档对这一部署约束零提示。
- **修复建议**：文档改写为"当前仅支持本地磁盘存储；多副本部署需共享卷或将 API/worker 固定同节点"，把对象存储明确降级为 roadmap；或补一个存储抽象接口（保持现有持久化行为）。

### M3. §3 "Queue backend abstraction (Redis Stream / RabbitMQ)" 遗漏 db_polling 与出厂默认值

- **位置**：`docs/architecture-enterprise.md:27`；反证在 `application.yml:103`、`application-dev.yml:8`、`NoopIngestionQueue.java:10-11`
- **描述**：`IngestionQueue` 实际有三种行为模式：`redis_stream`（出厂默认）、`rabbitmq`、`db_polling`（dev 默认，且绑定 `NoopIngestionQueue`——`IngestionService.java:100-102` 在该后端下连主消息都不投递，靠 `IngestionWorker.pollDatabaseJobs`（`IngestionWorker.java:118-145`）扫表）。文档只写了前两个。
- **触发条件**：开发者按文档理解 dev 环境行为时——默认配置下根本没有消息队列参与。
- **影响**：中等偏低，但"抽象支持哪些后端、默认是哪个"是抽象类声明的核心信息；`db_polling` 的无队列特性也直接关联 H2（无 DLQ）。
- **修复建议**：括号补全为 "(redis_stream [default] / rabbitmq / db_polling [dev default, poll-only, no DLQ])" 一类的准确清单。

### M4. §4 把 "Audit log retention scheduler" 当作唯一保留机制陈述，掩盖了 refresh_tokens 无任何清理的事实

- **位置**：`docs/architecture-enterprise.md:37`；反证在 `AuditRetentionScheduler.java:20-24`（仅清 `audit_log`）、全仓库 grep 仅 `audit_log`（deleteBefore）与 `memory_item`（deleteExpired）两处删除路径
- **描述**：`refresh_tokens` 每次 `/auth/token`、`/auth/refresh` 都 insert，revoke 只置位不删除，且没有任何调度器清理过期/已吊销行——增长无上界。文档以一行 "Audit log retention scheduler" 概括数据保留，读者会以为保留策略覆盖了安全域全部表。
- **触发条件**：长期运行的生产实例 + 高频刷新令牌签发。
- **影响**：表膨胀拖慢 refresh 查询；安全审计上"已吊销令牌永久留存"也可能与合规保留期要求冲突（该删的不删、不该删的没说）。
- **修复建议**：文档在 §4 补一句保留范围现状（"retention 仅覆盖 audit_log，memory_item 有 TTL 清理，refresh_tokens 暂无清理"）；代码侧可在 `AuditRetentionScheduler` 同型增加 refresh_tokens 过期清理（纯内部行为，不变更对外 API）。

## 低严重度

### L1. §1 Bounded Contexts 与 §2/§4 存在轻微漂移

- **位置**：`docs/architecture-enterprise.md:3-8`
- **描述**：Security Context 只列 "api key lifecycle, jwt, permission checks"，未含 refresh token 生命周期、限流与审计日志（均在 §4 出现）；Conversation Context 含 memory，而 `memory_item/memory_event` 实际横跨租户与 agent 域（`V12`）。限界上下文是软性描述，故仅记低。
- **触发条件**：按 §1 划分代码模块归属或团队 ownership 时。
- **影响**：归属判断偏差，无运行时后果。
- **修复建议**：Security Context 补 "refresh token rotation / rate limiting / audit log"；memory 归属用一句话注明其跨域现状即可。

### L2. §6 "Keep chatId stable in clients" 缺少 chatId 的生成方说明

- **位置**：`docs/architecture-enterprise.md:49`；对照 `ChatController.java:51`（`@RequestParam("chatId")`，由客户端传入）
- **描述**：chatId 完全由客户端生成并明文传递，后端用 `ConversationIdHelper.build("chat", chatId)` 拼装会话键；文档只说"保持稳定"，未说明它是客户端自由字符串、其取值直接影响幂等键派生（`IngestionService.java:369-371` 的 auto 键含 chatId）。
- **触发条件**：新接入方实现客户端时。
- **影响**：轻微——换 chatId 的副作用（会话断裂 + 幂等键变化导致同一文件重复摄取）不易被预见。
- **修复建议**：一句话补全："chatId 由客户端生成；变更 chatId 会同时使自动派生的幂等键失效，导致同文件重复摄取"。

## 修复优先级建议

1. **H1**：先改文档措辞（条件式声明 + 实例态清单），成本最低、防误导收益最大；TrustedActionService 外置化排代码侧首位（安全动作跨实例不可用是功能缺陷而非性能问题）。
2. **H2**：redis_stream 加 MAXLEN + DLQ 只读查询端点；文档同步标注 db_polling 无 DLQ。
3. **M3**：一行括号补全，随 H2 一起改。
4. **M1 / M2**：补全数据所有权清单、修正存储现状描述。
5. **M4 / L1 / L2**：随下一次文档例行维护合并处理。

## 约束遵守声明

- 未修改任何源码、配置或本文档本体；仅新增本报告文件。
- 未执行 git commit/push；未删除文件；未执行 npm install 或任何 build/mvn 构建。
- 修复建议均未要求变更任何对外 API 签名。

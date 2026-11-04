# 缺陷审查报告：IngestionService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/ingestion/IngestionService.java`（383 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`IngestionJobMapper`（全部 SQL，含 claim/requeue 的 WHERE 条件）、`IngestionWorker`（全文：redis_stream 消费、claimIdle 重投递、db_polling、重试调度）、`RabbitMqIngestionListener`（租户传递）、`DefaultFileSafetyScanner`、`IngestionProperties` 与 `application.yml:107`（storage-dir 默认值）、`VectorStoreProperties`、全部调用方——`IngestionController`（全文）、`PdfController`（上传点）、`SeedDataRunner`
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件
- **关联报告**：`reports/audit-RagAnswerService.java.md`、`reports/audit-EvaluationService.java.md`、`reports/audit-AgentSessionService.java.md`、`reports/audit-WorkspaceRuntime.java.md`

---

## 概览

该类是 PDF 摄取管线（提交 → 幂等去重 → 落盘 → 队列/DB 轮认领 → 解析分块 → 向量入库 → 快照持久化 → 重试/DLQ）。做得好的部分值得先说明：`claimForRun` 用状态机 WHERE 条件防重复认领且强制租户作用域（mapper:46-61，worker 与 MQ listener 均显式传 ownerTenant）；SimpleVectorStore 快照走"临时文件 + ATOMIC_MOVE"且有并发锁；幂等键三级设计（client 键 → 内容哈希）；终态失败有 DLQ；重试退避计算正确。但存在 **1 个高危**（默认配置下上传文件落点错误，摄取管线确定性失败）与 4 个中危——其中"崩溃的 RUNNING 任务永久滞留"使注释里宣称的恢复机制实际失效。所有修复均不需变更对外 API 签名。

---

## 高严重度

### H-1 `persistFile` 用相对路径调用 `MultipartFile.transferTo`，默认配置（`./data/uploads`）下文件落进 Servlet 临时目录——每个摄取任务确定性失败

- **位置**：`IngestionService.java:353-358`（`Path root = Path.of(ingestionProperties.getStorageDir())` 未做 `toAbsolutePath()`；`file.transferTo(target)`）、`application.yml:107`（`storage-dir: ${APP_INGESTION_STORAGE_DIR:./data/uploads}` **默认即相对路径**，仓库内未见任何部署文件设置该环境变量）
- **描述**：Spring `MultipartFile.transferTo` 底层走 Servlet `Part.write`——**相对路径由容器解析到 multipart 临时目录**（Tomcat 的 `javax.servlet.context.tempdir`），而非应用工作目录。于是：`Files.createDirectories(root)`（:356）在工作目录下创建了空的 `./data/uploads`，文件却写进了容器临时目录；`:358` 返回的 `target.toAbsolutePath()` 是工作目录下的路径，与实际落点**不一致**。后台任务执行 `processPdfJob` 时 `new File(job.getFilePath()).exists()`（:278-279）为 false → 抛 "PDF file missing"（:280）→ 按重试策略跑满 `maxRetries` 后 FAILED。上传接口返回 accepted，任务却全军覆没，且错误信息把人引向"文件丢失"而非"存储路径配置"。
- **触发条件**：`APP_INGESTION_STORAGE_DIR` 未设置为绝对路径即默认触发——上传任意 PDF 后查询 job 状态即为 FAILED/RETRY、errorMessage="PDF file missing: …/data/uploads/…"（路径存在但文件不在）。
- **影响**：默认部署下 PDF 摄取功能整体不可用；每次失败还叠加 L-1 的孤儿文件与无意义的重试 LLM/嵌入开销（本例无嵌入）与告警噪音。
- **修复建议**：`persistFile` 首行 `root = root.toAbsolutePath()`（一行修复，语义与 `:358` 返回值一致）；或改用 `Files.copy(file.getInputStream(), target)` 绕开 `Part.write` 的路径解析。辅以启动时校验 storageDir 可写并打日志。

---

## 中严重度

### M-1 worker 崩溃后 RUNNING 任务永久滞留——claimIdle 重投递与 db_polling 都无法重新认领 RUNNING 行

- **位置**：`IngestionJobMapper.java:55`（`claimForRun` 的 WHERE 只允许 `status IN ('PENDING','RETRY')`）、`IngestionService.java:170-177`（claim 失败即返回 picked=false）；对照 `IngestionWorker.java:64-71`（claimIdle 注释宣称"Reclaim messages left pending by crashed/slow workers so jobs do not stay RUNNING forever"）与 `:83-88`（picked=false 时故意不 ack、留给 idle-claimer 重试）
- **描述**：恢复链路自相矛盾：worker 在认领之后、`updateTerminalState` 之前崩溃（或被 kill -9），DB 里任务停在 RUNNING；消息未 ack，30 秒后被 idle-claimer 重投递，但 `claimForRun` 拒绝 RUNNING 状态 → claim=0 → picked=false → 又不 ack → **无限空转循环，任务永远 RUNNING**。db_polling 模式更彻底没有恢复：`findNextReadyJob`（mapper:38-39）同样只扫 PENDING/RETRY。另外 `:217` 的 `catch (Exception)` 不覆盖 `Error`（OOM/StackOverflow），任务同样滞留 RUNNING。
- **触发条件**：任意一次 worker 进程崩溃/重启发生在处理中途（大 PDF、嵌入服务慢时窗口不小）；此后该任务既不会被处理也不会被 ack。
- **影响**：僵尸任务 + 消费循环空转（每 30s 一轮无效重投递）；管理员只能手工 UPDATE 数据库。
- **修复建议**：租约语义——`claimForRun` 追加分支允许认领 `status='RUNNING' AND started_at < now() - stale_threshold`（阈值进 `IngestionProperties`，与 claimIdleMs 呼应）；或定时 reaper 将超时 RUNNING 重置为 PENDING。同时处理中任务的向量写入幂等（见 M-3）是此前提。

### M-2 "先改状态后发消息"两处非原子：publish 失败留下永久滞留态，且重试链断裂

- **位置**：① `IngestionService.java:99-102`（insert 已自动提交后 `publishJob`，Redis 抖动抛异常 → 用户 500，任务停在 PENDING；redis_stream 模式下**没有任何 PENDING 兜底扫描**——`enqueueRetryJobs` 只扫 RETRY（`IngestionWorker.java:101-112`），`pollDatabaseJobs` 仅 db_polling 模式启用）；② `IngestionService.java:268-271`（`requeueRetry` 先把状态置 PENDING **并清空 next_retry_at**，然后才 `publishJob`——publish 失败后 `findReadyRetries`（mapper:90-98 只扫 status='RETRY'）永远不再命中该任务）
- **描述**：两处都是"DB 先行、消息后发"的顺序颠倒：消息层失败后 DB 状态已不可逆地离开可扫描集合。①的用户后果是"上传返回 500 但文件已存、任务无人处理"；②的后果更隐蔽——重试任务从调度视野中彻底消失，仅剩管理员手工 `POST /ingestion/jobs/process` 补救。
- **触发条件**：提交/重试入队瞬间 Redis/RabbitMQ 不可用或超时——正是队列后端最该有韧性的场景。
- **影响**：任务静默丢失（无 FAILED 状态、无 DLQ、无日志关联）。
- **修复建议**：publish 失败 catch 中将状态写回（① PENDING 保留并记录日志；② 写回 RETRY + `next_retry_at=now`，让下轮调度自然重试）；或引入 outbox（与状态同事务落库，由调度器投递）；再加一个 PENDING 超龄兜底扫描作为最后防线。

### M-3 重试重跑无向量幂等保护——部分写入后重试导致 chunk 重复，检索结果被扭曲

- **位置**：`IngestionService.java:291`（`vectorStore.add(chunks)` 前无按 `job_id` 的删除清理）、`:290`（chunk 元数据带 `job_id`/`chunk_index`，具备做幂等键的全部条件）
- **描述**：对 pgvector/Milvus 等真实后端，`add` 分批嵌入时中途失败（嵌入服务超时、预算断言）很常见：前几批已入库，任务进 RETRY，重跑时**全量再次 add**——同一 job 的 chunk 在库中重复。重复文档在检索中多路命中会抬高其排序分（HybridRagAnswerService 的 dedup 按文档内容去重，但同 job 重复 chunk 属于"同内容多份"，recall 统计与 rerank 分布全部失真）。
- **触发条件**：任何一次嵌入批处理中途失败后的重试成功——重试机制越正常工作，重复越必然发生。
- **影响**：向量库慢性污染，无法从 job 状态察觉；清理只能靠人工按 job_id 扫库。
- **修复建议**：`processPdfJob` 在 add 前执行 `vectorStore.delete("job_id == \"…\"")`（过滤表达式复用 `RagAnswerService.escapeFilterValue` 的写法）；或为 chunk 指定确定性 ID（`jobId + "-" + chunk_index`）实现天然幂等。

### M-4 幂等键 IOException 兜底退化为"文件名+大小"——不同文件可被静默去重吞掉

- **位置**：`IngestionService.java:368-374`（读取流计算内容哈希失败时，key 改由 `tenant|chat|文件名|size` 推导，无日志）
- **描述**：`file.getInputStream()` 读流抛 IOException（磁盘/网络抖动、部分上传损坏）时不是拒绝提交，而是降级为一个**与内容无关**的弱键。同租户同会话下，两个同名同大小的**不同文件**第二次上传会命中第一次的 job 直接返回——接口 200 "accepted"，新文件内容从未被摄取，用户以为传上去了。
- **触发条件**：哈希读流失败 + 后续上传恰好同名同大小；或更常见的——同一文件先损坏后完整地传两次，第二次得到坏文件的任务。
- **影响**：知识库内容与用户认知静默背离；弱键路径无任何日志，事后无法归因。
- **修复建议**：IOException 时直接抛 `IllegalStateException("failed to read upload for dedup")`（提交失败可重试，优于错误去重）；至少 `log.warn` 记录降级事实。

---

## 低严重度

### L-1 幂等冲突/插入失败路径遗留孤儿文件，且无任何清理机制

- **位置**：`IngestionService.java:82`（`persistFile` 先于 insert 执行）、`:109-115`（DuplicateKeyException 后返回已有 job，**刚落盘的文件不删除**）；其他 insert 异常路径同理
- **触发条件**：并发/重复上传、insert 失败——磁盘慢性泄漏，仓库内无对应的清理任务。修复建议：失败路径 `Files.deleteIfExists(target)`（注意 duplicate 分支删除的确认是**本进程刚写的文件**，不是 existing 的文件）；或定期清理无 job 引用的存储文件。

### L-2 `listBySourceAcrossScopes` 前缀匹配过宽、自身可重复、候选截断无提示

- **位置**：`IngestionService.java:139-140`（`startsWith(sourceName)` 使 "report" 同时命中 `report-2023.pdf`、`reporting.pdf`——引用溯源"展开原文"可能展示错文档）、`:133-134`（chatId == sharedCorpusChatId 时两查询返回同一批行，merged 重复）、`:130-132`（两处 50 条硬上限，shared 语料多时目标文档可能被截在 50 名之外，静默漏配）
- **修复建议**：按 jobId 去重（LinkedHashSet）；过滤下推 SQL 或至少改为精确名匹配优先、前缀匹配兜底并标注来源；候选满 50 时 `log.warn`。

### L-3 SimpleVectorStore 快照：add 在锁外、save 在锁内，save 失败仍标记 SUCCEEDED

- **位置**：`IngestionService.java:291`（`vectorStore.add` 在 `snapshotLock` 之外）vs `:336-345`（锁内 `simpleVectorStore.save` 遍历内存结构——另一 worker 同时 add 可致 ConcurrentModificationException，被 `:346` catch 吞成 warn）；`:346-348`（快照失败仅 warn，任务照常 `updateTerminalState(SUCCEEDED)`——simple 后端重启后内存态丢失，job 状态与实际数据背离）
- **触发条件**：`vectorStore.backend=simple` + 多 worker 并发（workerCount 默认 3）。修复建议：add 与 save 同锁（或快照落盘挪到锁内统一执行）；快照失败时在任务 errorMessage/日志中显式记录，避免"成功"假象。

### L-4 claim 失败时返回状态恒为 RUNNING，语义失真

- **位置**：`IngestionService.java:170-177`（claim==0 时任务可能已 SUCCEEDED/FAILED/不存在，统一返回 `status(RUNNING)`）
- **触发条件**：管理端 `POST /ingestion/jobs/process?jobId=…` 轮询已完成任务 → 响应显示 RUNNING。修复建议：回读一次实际状态返回；不存在时给明确错误。

### L-5 `listByChatId` 服务层无上限，排序键不唯一

- **位置**：`IngestionService.java:123`（仅 `Math.max(limit, 1)`，无上界；controller `:66` 自行 cap 100，服务被其他调用方复用即失去保护）；`IngestionJobMapper.java:83`（`ORDER BY created_at DESC` 无 tiebreaker，同秒并列时截断结果不确定）
- **修复建议**：服务层 `Math.min(limit, 上限)`；SQL 补 `, id DESC`。

### L-6 杂项：错误信息丢根因、指标租户基数

- **位置**：`IngestionService.java:377-382`（`truncateError` 只取 `ex.getMessage()`，root cause 的类名与消息丢失——嵌套包装异常（Spring `IllegalStateException` 包 IO 异常）在 errorMessage 里可能只剩 "Failed to store uploaded PDF"）；`:105`、`:204-208`、`:238-245`（Counter/Timer 直接以 tenantId 为 tag——指标基数随租户数线性增长，可控但应文档化并在租户删除时清理）
- **修复建议**：truncateError 拼接 root cause 简名（`ex.getRootCause()` 循环或 Spring 的 `NestedExceptionUtils.getMostSpecificCause`）；指标 tag 策略写入运维文档。

---

## 修复优先级建议

1. **H-1** 一行修复（`root.toAbsolutePath()`），但默认配置下摄取功能完全不可用，应最优先验证与发布。
2. **M-1**（崩溃恢复租约）与 **M-2**（publish 前后顺序）同属任务生命周期韧性，建议一次改动覆盖：claim 允许认领超时 RUNNING + publish 失败回写状态 + PENDING 兜底扫描。
3. **M-3**（向量幂等）是 M-1 的前置配套——开了"重认领 RUNNING"就必须先保证 add 幂等，否则崩溃重跑变成重复写入。
4. **M-4**（弱幂等键拒绝降级）一行逻辑改动，消除静默吞文件。
5. L 级按顺手处理；L-1（孤儿文件清理）建议随 H-1 一起做（两者都在 persistFile/提交路径）。

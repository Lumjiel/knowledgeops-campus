# 缺陷审查报告：MemoryService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/memory/MemoryService.java`（167 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`MemoryItemMapper`/`MemoryEventMapper`（全文，**全部查询 SQL 均带 `tenant_id = #{tenantId}` 且过滤 `expires_at IS NULL OR expires_at > NOW()`**，`findByTenantAndTaskId` 无 LIMIT 无排序）、`MemoryItemRecord`/`MemoryEventRecord`（全文，`@TableName` 与列对应）、建表 SQL `V12__memory_system_tables.sql:1-33`（`content TEXT`、`source VARCHAR(128)`、`user_id VARCHAR(64) NULL`、`confidence DOUBLE`、`memory_id` 唯一键、事件表**无保留设计**）、`MemoryController.java`（全文，fact 走 `confidence >= 0.8` 人工写入，`resolveUserId` 空身份退化为 `"anonymous"`）、唯一生产调用方 `RagAnswerService.java:300-380`（读写均包在 try/catch 内、long 记忆有合并去重逻辑 `:346-353`、写入失败不阻断主链路）、调度开关 `App.java:9`（`@EnableScheduling` 存在，3am cron 生效）、`AuditRetentionScheduler`（grep 核实**不覆盖** memory_event）；调用方核实：`queryLongMemory/queryFactMemory/saveShortMemory/saveLongMemory/deleteMemory` 各有生产调用，**`saveTaskMemory` 生产与测试均零调用，`queryTaskMemory/getEvents/buildContext` 仅 `MemoryServiceTenantIsolationTest` 引用**
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm/mvn 操作
- **关联报告**：`reports/audit-TenantCostService.java.md`（JVM/DB 时区错位同类发现）、`reports/audit-RagAnswerService.java.md`、`reports/audit-AuthController.java.md`

---

## 概览

该类是记忆系统核心：四类记忆写入（short/long/task/fact）→ 租户内查询 → 上下文快照组装 → 过期清理（3am cron）→ 事件流水。做对的部分：

- **租户隔离做在 SQL 层且可测试**：所有查询/删除定位都经 `findByTenantAndMemoryId` 等带 `tenant_id` 条目的语句，事件查询先验证条目归属再放行（`:157-159`），且有 `MemoryServiceTenantIsolationTest` 固化该行为。
- **过期语义一致**：查询侧 `expires_at > NOW()` 与清理侧 `expires_at IS NOT NULL AND expires_at < NOW()`（mapper `deleteExpired`）边界互补，过期记忆在两次清理之间也不会被查出来；`expires_at` 有独立索引。
- **事件写入失败不阻断主链路**：`emitEvent` 独立 try/catch + `log.error`（`:152-154`），记忆写入不会因审计流水失败而回滚。
- **tenantId 统一经 `TenantContext.normalize`**（`:59`、各查询入口），空白归一为 `public`，与全站约定一致。
- **公共接口薄而清晰**：record 快照（`:162-165`）、类型常量内聚，`buildContext` 的分节拼装有空集合短路。

但存在**fact 记忆无去重无上限、userId 为 null 的 SQL NULL 陷阱、事件流水无保留策略且 DELETE 后不可达**三类问题。以下修复建议均不需变更对外方法签名。

---

## 高严重度

> 本类无安全/可用性意义上的传统高危项：租户隔离有 SQL 层保障并有测试固化，调度已启用，写入失败路径（RagAnswerService 侧）有兜底。以下按"对记忆系统核心目标（数据可信、无界增长受控）的破坏力"将 fact 污染链列为高。

### H-1 fact 记忆无去重、无总量上限：重复人工写入挤占查询窗口并使表无界增长

- **位置**：`MemoryService.java:44-49`（`saveFactMemory` 直接 `save` 落库，无查重）→ mapper `findByTypeAndConfidence`（`ORDER BY created_at DESC LIMIT #{limit}`，生产调用固定 5 条）；对照证据：long 记忆在唯一生产调用方 `RagAnswerService.java:346-353` 有"查最新→解析→合并→覆盖"的显式去重设计，fact 完全没有；`V12__memory_system_tables.sql:15`（唯一键只在 `memory_id`，content 无唯一约束）
- **描述**：fact 的设计定位是"人工写入、防污染"（`MemoryController:26` 注释），但防的只是"自动写入"，没有防"重复写入"。运营者对同一结论多次提交（或前端重复点击）时每次都新增一行：`queryFactMemory(tenantId, 0.8, 5)` 取最新 5 条，重复内容会占满窗口，把其他有效事实挤出 prompt 的【常见问题结论】区（`RagAnswerService.buildKnownInfo:323-329`）——一条被反复提交的错误事实可以独占全部 5 个槽位，等效于记忆污染，恰是该设计要防的东西。总量无上限、无合并、无清理，fact 表随提交次数线性增长。
- **触发条件**：对 `/memory/fact` 重复提交相同/相似 content（人工操作失误、脚本重试、前端无防抖）——常态运维即可发生，无需攻击者。
- **影响**：① prompt 内事实被重复内容挤占，RAG 回答质量劣化且无报错；② 无界增长；③ 错误事实的删除只能逐条 `DELETE /memory/{memoryId}`，运维成本随重复条数放大。
- **修复建议**（不改签名）：`saveFactMemory` 写入前按 `(tenant_id, type='fact')` 查询既有内容做规范化比对（去空白/小写），命中则覆盖 `updated_at`+`confidence` 而非新增；或对同内容重复提交直接返回既有 `memoryId`。同时建议提供按 content 前缀的批量清理入口（新增方法属类内扩展，不改既有签名）。

---

## 中严重度

### M-1 `userId` 为 null 时落库行永远不可见：`user_id = NULL` 在 SQL 中恒为假，记忆静默丢失

- **位置**：`MemoryService.java:57-70`（`save` 对 userId 原样落库，无校验归一）→ mapper `findByUserAndType`（`AND user_id = #{userId}`）；建表证据：`V12__memory_system_tables.sql:5`（`user_id VARCHAR(64) NULL`）
- **描述**：tenantId 经过 `TenantContext.normalize` 归一，userId 却既不校验也不归一。任何调用方以 `userId=null` 调用 `saveShortMemory`/`saveLongMemory` 时，行正常插入（`user_id` 为 NULL），但所有查询 SQL 都是 `user_id = #{userId}`——`NULL = NULL` 在 SQL 里为 UNKNOWN，该行**永远查不到**：写入"成功"、返回了 `memoryId`、事件流水也记了 CREATE，数据却成为幽灵行。当前生产调用方恰好都解析出非空 userId（`RagAnswerService:299-304` 退化为 `"chat:"+chatId`、`MemoryController:86` 退化为 `"anonymous"`），但 `MemoryService` 是公共 Service，契约陷阱对每个未来调用方开放（如内部任务、新 controller 以用户对象未登录时的 null 直接透传）。
- **触发条件**：任何以 null userId 调用 save 系列方法的代码路径（当前无，属高概率的未来引入）。
- **影响**：静默数据丢失，无任何错误信号；幽灵行还占 `idx_memory_item_tenant_user_type` 索引与 `deleteExpired` 之外的存储。
- **修复建议**（不改签名）：`save` 入口对 userId 做与 tenantId 同级的处理——`StringUtils.hasText` 为假时归一为 `"anonymous"`（与 `MemoryController.resolveUserId:86` 的既有约定对齐），或至少抛 `IllegalArgumentException` 使契约显式化；两案都不改签名。

### M-2 memory_event 无限增长且 DELETE 后事件不可达：审计链语义失效

- **位置**：`MemoryService.java:71`/`:136`（每次 save/delete 各写一条事件）；`:157-159`（`getEvents` 先查 item，条目已删则直接返回空列表——**该条目历史事件从此不可达**）；清理证据：`cleanExpiredMemories:124-130` 只删 `memory_item`，`AuditRetentionScheduler` 不覆盖 memory_event（grep 核实），`V12__memory_system_tables.sql:22-33` 无保留设计
- **描述**：两个复合缺陷：① 事件表只增不删——每轮对话至少 2 条 CREATE（short+long，见 `RagAnswerService:353/:356`）+ 摘要压缩期的成批 DELETE（`:373` 循环逐条删除各触发 1 条事件），行数正比对话轮次，永无清理；② `deleteMemory` 删掉条目后，`getEvents` 的归属校验（`:158`）失败，该 memoryId 的全部事件（包括刚写入的 DELETE 事件）对外不可达——审计表里留着数据，审计接口却查不到，审计语义自相矛盾。
- **触发条件**：任何记忆写入/删除流量持续发生（生产常态）。
- **影响**：表无界增长拖慢 `idx_memory_event_memory` 索引；审计/排障时无法回答"这条记忆何时被谁删了"——恰恰是事件表存在的目的。
- **修复建议**（不改签名）：① `cleanExpiredMemories` 顺带清理"对应 item 已不存在且超过保留期（如 90 天）"的孤儿事件（mapper 加一条 SQL，类内调用，不动签名）；② `getEvents` 的归属校验改为"item 存在 → 返回全部事件；item 不存在 → 仍返回该 memoryId 的事件但标记 `deleted=true`"（record 已可扩展字段，或至少对已删条目放行事件查询——memoryId 是 UUID 全局唯一，无越权风险）。

### M-3 `buildContext` 的 contextText 漏掉 facts：与 Javadoc 和快照字段自相矛盾（死代码中的潜伏缺陷）

- **位置**：`MemoryService.java:94-120`（Javadoc 明列 "High-confidence facts"，`:103` 查询了 facts，但 `:105-118` 的 StringBuilder 只拼 longMem 与 shortMem，facts 仅进 `:119` 快照字段）；阈值漂移：`:103` 用 0.7，生产路径 `RagAnswerService:323` 与 `MemoryController:42` 均用 0.8
- **描述**：`buildContext` 若被调用，调用方拿到的 `contextText`（大概率被直接拼进 prompt 的字段）不含 facts，而 `facts` 字段里又有数据——调用方若信任 contextText 就丢失事实，若自行拼 facts 又绕过该方法的存在意义。0.7 与 0.8 的阈值漂移进一步埋雷：将来启用该方法时，同一租户在不同入口看到的事实集不同。当前 `buildContext` 仅被隔离测试引用、无生产调用方（grep 核实），故是潜伏缺陷而非现行故障——但"对外 API 带语义错误"本身值得修正。
- **触发条件**：任何未来调用方使用 `buildContext`（该方法就是为"一站式组装"设计的，被启用是预期路径）。
- **影响**：prompt 缺事实或双口径事实集。
- **修复建议**（不改签名）：`buildContext` 内补 facts 拼装段（与 `RagAnswerService.buildKnownInfo:327-331` 的【常见问题结论】格式对齐），阈值提为常量并对齐 0.8；javadoc 同步。

---

## 低严重度

### L-1 死 API 面：`saveTaskMemory` 零调用方，task 记忆类型有名无实

- **位置**：`MemoryService.java:38-42`（saveTaskMemory，全仓 grep 无任何调用方）；`:85-87`（queryTaskMemory 仅测试引用）；配套：`findByTenantAndTaskId` 无 LIMIT 无 ORDER BY（唯一可能在 task 记忆量大时全量拉取）
- **描述**：task 记忆的写入入口无人调用，查询入口只有测试在用——该类型当前是死功能。若未来启用，`findByTenantAndTaskId` 不设上限会一次拉回该任务全部记忆（30 天过期意味着上限可控，但 LLM 任务循环写 task 记忆的场景无保护）。
- **触发条件**：无（现状）；未来接入 task 流程时触发查询无界问题。
- **影响**：维护面虚胖；未来调用方可能踩查询无界。
- **修复建议**（不改签名）：接入 task 记忆时为 `findByTenantAndTaskId` 补 LIMIT/ORDER BY（mapper 侧改动）；若确认不做 task 记忆，建议标记 `@Deprecated` 并在注释中说明，避免新代码误用。

### L-2 输入校验缺口三连：confidence 无上界、content/source 无长度校验、limit 无下限防御

- **位置**：confidence——`MemoryService.java:44-49`（接受任意 double，controller 仅校验 `>= 0.8` 无上界，`MemoryController:58-61`）；content/source——`:51-70`（`content TEXT` 上限 64KB、`source VARCHAR(128)`，`saveTaskMemory` 的 `"task:" + taskId` 与 RagAnswerService 的 `"dialog:" + chatId` 均可能超 128，插入即 DataIntegrityViolation）；limit——`:77-92`（limit ≤ 0 时 SQL `LIMIT #{limit}` 语法错误抛异常）
- **描述**：三处都是"异常延迟到 DB 层才爆"的模式。fact 超长 content 经 `MemoryController.saveFact` 直达 DB 异常 → `GlobalExceptionHandler` 返回 500（应为 400）；confidence 传 100 或负数（controller 只挡 <0.8）正常落库，`minConfidence` 过滤语义失真；limit 传 0/-1 得到的是 SQL 异常而非空结果。生产主链路（RagAnswerService）因外层 try/catch 免于 500，但错误仍被吞成"记忆静默未写"。
- **触发条件**：超长 fact 提交、非法 confidence、limit 传非正常值（未来调用方）。
- **影响**：错误码失真（500 vs 400）、静默失败、脏数据。
- **修复建议**（不改签名）：`save` 入口统一校验——confidence 夹取到 [0,1] 或越界抛 IAE（走既有 400 通道）；content 超 32KB 抛 IAE；source 超 128 截断或抛 IAE；查询方法对 `limit <= 0` 返回空列表或夹取下限。

### L-3 演示模式下 anonymous 是共享记忆池：同租户所有无身份用户互通记忆

- **位置**：`MemoryService.java` 本身不区分（设计依赖调用方传 userId）；触发证据：`MemoryController.resolveUserId:86` 无身份时返回 `"anonymous"`，`RagAnswerService:304` 退化为 `"chat:"+chatId`
- **描述**：安全关闭的演示部署中，同一租户所有匿名请求共享 `user_id='anonymous'` 的 short/long/fact 记忆：用户 A 对话抽出的槽位（姓名、联系方式等，`SlotExtractor` 抽取字段含 contactInfo）会拼进用户 B 的 prompt（`buildKnownInfo`）。生产开启认证后不受影响，但演示环境是面向真实访客的。
- **触发条件**：安全关闭 + 多个真实访客同租户访问。
- **影响**：演示环境跨访客信息泄漏（隐私级）。
- **修复建议**（不改签名）：演示模式 anonymous 记忆在 `MemoryController`/调用方传入 `chat:` 前缀级 userId（`RagAnswerService` 已是此风格），或在 buildKnownInfo 读取侧过滤 `anonymous` 的 long/fact——均不动本类签名；至少在类 Javadoc 声明"userId 的区分责任在调用方"。

### L-4 JVM `LocalDateTime.now()` 与 DB `NOW()` 时区可能错位，过期窗口漂移

- **位置**：`MemoryService.java:30/:41`（expires_at 由 JVM 时钟写入）→ mapper 各查询 `expires_at > NOW()` 与 `deleteExpired` 的 DB 时钟比较
- **描述**：与 `TenantCostService` 同类问题：应用容器与 DB 时区不一致时，24 小时过期的 short 记忆实际存活时间偏移 ±时区差（如 JVM UTC、DB 东八区 → 多活 8 小时或提前 8 小时消失）。查询与清理都按 DB 时钟，至少自洽；但写入的绝对时刻偏了。
- **触发条件**：应用与 DB 时区配置不一致。
- **影响**：过期时间不准；日志与 DB 数据对不上。
- **修复建议**（不改签名）：部署层统一时区（推荐），或 SQL 内改用 `DATE_ADD(NOW(), INTERVAL 24 HOUR)` 风格让 DB 全权决定；本类签名不变。

### L-5 `deleteMemory` check-then-act 与逐条删除无批量入口

- **位置**：`MemoryService.java:132-138`（先 `findByTenantAndMemoryId` 再 `deleteById`，两步间无原子性）；调用方证据：`RagAnswerService:372-374` 在压缩循环里逐条调用（每条一次 SELECT + 一次 DELETE + 一次事件 INSERT）
- **描述**：竞态无害（双删幂等，且 `deleteById(item.getId())` 按主键），但逐条模式在摘要压缩场景是 N×3 次 DB 往返。另有小不对称：`deleteMemory` 定位用的是 memoryId 再转 id，多一次查询。
- **触发条件**：short 记忆超 6 条的每轮对话。
- **影响**：延迟放大（小）；代码意图可读性。
- **修复建议**（不改签名）：新增 `deleteMemories(String tenantId, List<String> memoryIds)` 批量方法（新增重载不改既有签名），mapper 侧 `DELETE ... WHERE tenant_id=? AND memory_id IN (...)`。

---

## 修复优先级建议

1. **先修 H-1**：fact 去重是记忆质量的核心防线缺口，且当前 fact 流量小、改动窗口干净（写前比对一处逻辑）。
2. **M-1（userId null 陷阱）一行归一即可收敛**，成本最低、防的是未来静默故障，建议与 H-1 同批。
3. **M-2（事件保留）**：孤儿事件清理并进既有 3am cron 是自然落点，随下一次维护窗口处理。
4. M-3 与 L-1（buildContext 语义 + 死 API 面一起盘点）在 task 记忆或 buildContext 接入前完成即可。
5. L-2 参数校验、L-4 时区属部署与健壮性顺手项；L-3/L-5 记录为设计决策待确认项，不阻塞。

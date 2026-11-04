# 缺陷审查报告：AnswerFeedbackService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/service/AnswerFeedbackService.java`（162 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`AnswerFeedbackMapper`（纯 `BaseMapper`，无自定义 SQL）、`AnswerFeedback`（`IdType.AUTO` 主键）、`AnswerFeedbackSubmitVO`（全文）、`FeedbackProperties`（含默认值与注释声明的威胁模型）、`TenantContext.normalize`、建表 SQL `V8__session_feedback_cost_platform.sql:24-39`、`application.yml:163-165`；调用方唯一：`FeedbackController:20-22`；权限门 `SecurityConfiguration:66`；限流 `RateLimitFilter`（全局按 IP）；已 grep 确认数据集轮转文件**无任何清理/保留任务覆盖**（`AuditRetentionScheduler` 仅处理 DB 审计事件）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm/build 操作
- **关联报告**：`reports/audit-HybridRetrievalService.java.md`（同含"注释声称的防护需逐一验证"类发现）、`reports/audit-MemoryService.java.md`

---

## 概览

该类是用户反馈入口：校验入参 → 落库 `answer_feedback` →（开关开启时）把反馈拼成一条 JSONL 追加到评测数据集文件，并按大小轮转。做对的部分：

- **入参校验完整**：null payload、chatId、rating 1–5、answer 逐项校验，异常消息明确（`:34-46`）；
- **comment 截断 1024 与建表列宽 `VARCHAR(1024)` 精确一致**（`:57`，V8:32）——说明列宽有被考虑，只是只覆盖了 comment 一个字段（见 M-2）；
- **租户归一化复用 `TenantContext.normalize`**，空值兜底 `"public"`，不会触碰 `tenant_id NOT NULL` 约束（`:48`，TenantContext.java:14-16）；
- **数据集条目字段完整且有序**（`LinkedHashMap` 保序，`tenant_id`/`chatId`/`created_at` 均在），`ObjectMapper` 走 Spring 注入而非 `new`（`:70-80`）；
- **该端点有权限门**（`PERM_FEEDBACK_WRITE`/`PERM_CHAT_WRITE`/`ROLE_ADMIN`，SecurityConfiguration:66）且有全局按 IP 限流兜底；
- 轮转用 `resolveSibling + 秒级时间戳`，避开了固定 `.bak` 文件互相覆盖的初级错误（但仍有并发缺陷，见 H-2）。

核心问题集中在**数据集文件这一侧**：轮转机制与自己注释声明的威胁模型不符（H-1）、并发轮转直接丢数据（H-2）、中文关键词提取对主要语料系统性失效（M-1）；DB 一侧则是长度校验缺口与无幂等导致的重复写入（M-2、M-3）。所有修复建议均不需变更 `submit(String, AnswerFeedbackSubmitVO)` 的对外签名。

---

## 高严重度

### H-1 轮转机制未实现注释声称的"磁盘封顶"：轮转文件永不清理、无法被识别、注释语义与实现相反

- **位置**：`AnswerFeedbackService.java:103-115`（轮转块）；`FeedbackProperties.java:11-15`（注释："Cap the on-disk dataset so a single tenant … cannot exhaust the disk"）
- **描述**：注释声称轮转是为了防"单租户/泄漏凭证耗尽磁盘"，但实现只在**活跃文件**超过 50 MiB 时把它整体 `move` 成时间戳兄弟文件，之后继续追加。被轮转出的文件（`feedback_dataset-<ts>.jsonl`）：
  1. **没有任何删除/保留逻辑**——全仓 grep 确认唯一的保留任务 `AuditRetentionScheduler` 只清理 DB 审计事件，不碰文件系统；磁盘占用上限实际是 `50 MiB × 无限`，威胁模型原样可达：持续提交反馈（每个持有 `PERM_CHAT_WRITE` 的普通聊天用户都能做，SecurityConfiguration:66）每约 50 MiB 产出一个新轮转文件，直至写满磁盘。
  2. **文件名无固定前缀标记**（时间戳嵌在 stem 中），即便将来写清理任务也难以可靠识别这批文件。
  3. **注释"keeping the most recent data in the active file path"与实现相反**：`Files.move` 把整个活跃文件（含最近数据）搬走，活跃路径反而是空文件，下游若只读活跃路径会丢失全部已轮转数据。
  4. 配置为 0 时（`Math.max(0, …)` 后 `maxBytes > 0` 判断，`:102`、`:107`）轮转被**关闭**而非"不限"——语义上 0 更像"不设限"，实际是"永不轮转、单文件无限增长"，与属性注释的"Cap"承诺直接矛盾。
- **触发条件**：任意持 `PERM_CHAT_WRITE` 账户持续 POST `/ai/feedback`（有全局 IP 限流，但 50 MiB 的量级下限流仅是减速带）；或运维将 `max-dataset-bytes` 设为 0。
- **影响**：磁盘耗尽 → 全服务写入面故障（DB binlog、日志、数据集写入全部失败）；下游训练管道数据不完整。
- **修复建议**：轮转文件统一加可识别后缀（如 `.rotated`）；在 `AnswerFeedbackService` 或独立 scheduler 中按数量/年龄清理轮转文件，使"磁盘占用 ≤ maxBytes × N(可配)"成立；`maxBytes <= 0` 时显式记录"轮转已禁用"日志；修正两处注释使其描述真实行为。全部可在不改对外签名下完成。

### H-2 并发/同秒轮转抛 `FileAlreadyExistsException`：请求 500、数据集条目丢失、DB 行已落但无补偿

- **位置**：`AnswerFeedbackService.java:107-116`（`Files.move(output, rotated)` 无 `REPLACE_EXISTING`；时间戳格式 `yyyyMMdd-HHmmss` 秒级，`:108`）；`:117-119`（IOException 一律包装为 `IllegalStateException`）；`:34-66`（`submit` 无任何并发防护，无 `@Transactional`）
- **描述**：`Files.move` 默认语义下目标已存在即抛 `FileAlreadyExistsException`。时间戳只有秒级精度，同秒内第二次轮转（两个并发请求各自看到文件超限；或多实例部署共享同一 `datasetPath` 时）必然撞名。方法上没有 `synchronized`、没有文件锁、没有重试，异常一路抛到 controller 变成 500。此时 `answerFeedbackMapper.insert(feedback)`（`:62`）**已经提交**（auto-commit，无事务边界），而数据集行丢失——恰好落在"DB 与数据集双写"不一致的最差组合上。多实例共享卷时即使不同秒，`exists→size→move→write` 的检查序列（`:107`）与追加（`:116`）之间也没有原子性，轮转窗口内的写入可能随 `move` 一起被搬走或丢失。
- **触发条件**：同一秒内两个并发反馈请求触发轮转；或双实例同时写同一数据集路径。无需恶意构造——活跃文件贴近 50 MiB 时高并发反馈即可自然触发。
- **影响**：用户可见 500；`answer_feedback` 行已插入但用户重试 → 重复行（表无唯一键，见 M-3）；数据集条目静默缺失，评测数据不完整。
- **修复建议**：轮转改为**先原子改名再写入**的固定套路：`Files.move(output, rotated, StandardCopyOption.ATOMIC_MOVE)` 配合捕获 `FileAlreadyExistsException`/`AtomicMoveNotSupportedException` 后在时间戳上加毫秒/序号重试；或改为**预创建新文件**（`output.<seq>` + 符号链接/指针文件）避免 move 竞争；对整个 appendToDataset 加类内锁或按文件粒度的 `FileChannel.lock`，多实例场景须文档声明路径不得共享或改用带分片的命名。单实例最小修复：秒级时间戳加进程内唯一序号 + `REPLACE_EXISTING` 不加（保持不覆盖），冲突时重试新时间戳。

---

## 中严重度

### M-1 中文关键词提取系统性失效：expected_keywords 退化为整句短语、forbidden_keywords 形同虚设，且高评分 expected 取自答案自身（自证）

- **位置**：`:122-142`（`extractKeywords`：分词正则 `[^\p{IsHan}\p{L}\p{N}]+`、`token.length() < 2` 过滤、6 个上限）、`:82-84`（comment 与 answer 以空格拼接后统一提取）、`:85-93`（三分支用途）
- **描述**：该正则按"非字母/数字/汉字"切分，而汉字本身属于 `\p{L}`（`\p{IsHan}` 子集关系使后者在此正则中冗余），于是**未带标点的中文连续文本不会被切开**——"回答不准确缺少依据"切成一个整句 token。实际后果：
  1. 中文评论/答案的关键词是"标点分隔的短语"而非词：`expected_keywords` 里出现的是几十字的整句，评测匹配几乎必然失败（过严），而低评分分支的 `forbidden_keywords` 是这些整句短语，几乎永远匹配不上（惰性）——两个方向同时失效；
  2. `token.length() < 2` 把所有单字汉字词（"慢""错""贵"——反馈语料中最有信息量的词）全部过滤；
  3. comment 与 answer 拼接后无法区分来源，6 个名额常被答案正文的长短语占满，用户评论（真正的反馈信号）反而进不了关键词；
  4. 语义层面：rating ≥ 4 分支的 `expected_keywords`（`:85-87`）取自**被评答案自身**——用答案自己的词作为"期望出现的关键词"，评测项是自证的，对模型改进几乎没有约束力。
- **触发条件**：默认开启（`app.feedback.enabled=true`，application.yml:164）下任何中文反馈——即本系统的主语料。
- **影响**：数据集条目的 keywords 三字段（`expected_keywords`/`forbidden_keywords`）对中文输入产出的基本都是噪声，下游评测集质量与该功能的目的不符；低评分数据的 forbidden 约束等于没写。
- **修复建议**：接入项目内已有的中文分词能力（或最小化地按常见标点 + 单字白名单切分后再过滤）；`length()` 判据改为按 code point 并对单字汉字词放行；comment 与 answer 分开提取、comment 优先占用名额；高评分分支的 expected 关键词改从 `question`/参考答案侧提取而非被评答案自身。若短期不能改分词，至少把 rating ≤ 2 分支的 forbidden 改为固定短词表，避免整句短语进 forbidden。

### M-2 除 comment 外全部字段不校验长度：`chatId > 128` 等直接 SQL 异常 500，`question/answer` 超 TEXT 上限整条失败

- **位置**：`:38-46`（仅 hasText 校验）；`:52-59`（sessionId/branchId/messageId/question/answer 原样入库）；建表 `V8__session_feedback_cost_platform.sql:24-36`（`chat_id VARCHAR(128) NOT NULL`、`session_id/branch_id/message_id VARCHAR(128)`、`question_text/answer_text TEXT`）；对照 `:57`（comment 被截到 1024）
- **描述**：comment 有 `trimToLength(…, 1024)` 而其余字段没有任何长度处理。strict mode 下 `chatId` 超过 128 字符（客户端可自由拼长串）、sessionId 等超过 128、answer 超过 64 KB（answer 是**必填**项且无长度上限，前端粘贴长文即可触发）都会抛 data-too-long 异常 → `FeedbackController` 返回 500。注意 `TEXT` 上限按字节计，中文 UTF-8 下 64 KB 只能放约 2.1 万字。这些失败发生在**校验层之后、insert 时**，错误信息是原始 SQL 异常而非业务提示。
- **触发条件**：任一持权限用户提交超长 chatId 或长答案。
- **影响**：可控的 500 与无效请求到达 DB 层；与"comment 已精确截断"形成明显不对称，说明是遗漏而非设计。
- **修复建议**：在 `submit` 内对 chatId/sessionId/branchId/messageId 复用 `trimToLength`（或超长抛 `IllegalArgumentException`，与现有校验风格一致）；answer/question 落库前截断到安全字节数（同时注意 `trimToLength` 的代理对问题，见 L-3）。不需改 VO/方法签名。

### M-3 先插库后写数据集、无事务无补偿、表无唯一键：失败即双写不一致，重试即重复堆积

- **位置**：`:62`（insert）、`:64-66`（数据集写入在 insert 之后）、`:117-119`（数据集失败抛 `IllegalStateException`）、`V8__…sql:24-36`（`answer_feedback` 无 UNIQUE KEY）与无幂等校验（`submit` 不查重）
- **描述**：两个问题叠加：
  1. **双写不一致**：insert 成功后数据集写入失败（H-2 的撞名、磁盘满、路径权限等都会触发），请求以 500 结束但 DB 行已持久化，无任何补偿/重试/标记。运维侧看不出这条反馈"半成功"。
  2. **无幂等**：同一 chatId+messageId 重复提交（用户双击、失败后重试、恶意刷）每次都插入新行并追加新数据集条目——表上没有唯一键，代码里也没有查重，重复反馈会无限堆积并污染评测集（同一问题多条同向样本）。
- **触发条件**：数据集写入失败后的用户重试；或任何形式的重复提交。
- **影响**：DB 与评测集数据漂移；重复行拉低后续按 (chatId, messageId) 统计反馈的正确性。
- **修复建议**：给 `answer_feedback` 加 `(tenant_id, chat_id, message_id, rating)` 或 `(tenant_id, chat_id, message_id)` 唯一键（需新迁移，属建表变更不改本类 API），insert 改为 `INSERT IGNORE`/捕获 duplicate key 后静默成功；数据集写入失败时至少记录 WARN 日志并携带 feedback id，避免静默半成功；如不能加键，则在 `submit` 前做一次按 (tenantId, chatId, messageId) 的存在性查询（注意此查询要有租户过滤）。

### M-4 全租户反馈明文混写单个数据集文件，且路径相对 CWD

- **位置**：`:69-80`（条目含 question/answer/comment 明文）、`:96`（`Path.of(feedbackProperties.getDatasetPath())`）、`FeedbackProperties.java:10`（默认 `evaluation/feedback_dataset.jsonl` 相对路径）、application.yml:165（默认值同）
- **描述**：所有租户的反馈（含完整问答与评论原文）追加进**同一个**文件，租户隔离只靠条目内的 `tenant_id` 字段——任何能读到该文件的角色/下游管道一次性获得全部租户数据，与代码库中 DB 侧按 tenant_id 逐行过滤的惯例（参见 MemoryService 审查结论）不对等。轮转出的历史文件同样是全租户混写且永不清理（见 H-1），旧租户数据在磁盘上无限期留存。默认路径是相对路径，实际落点取决于服务启动目录，多实例下可能各写各的文件，运维对"数据集到底在哪"不可见。
- **触发条件**：功能默认开启即为常态。
- **影响**：跨租户数据聚合泄露面；相对路径导致环境间行为不一致。
- **修复建议**：`dataset-path` 默认值改为绝对路径或强制配置化并校验 `isAbsolute()`；如需租户隔离，按租户分文件（`feedback_dataset_<tenant>.jsonl`）或在文档中明确该文件为全租户明文、需纳入与 DB 备份同级的访问控制；为轮转文件定义保留期（与 H-1 修复合并）。

---

## 低严重度

### L-1 feedbackId 兜底 `System.nanoTime()` 可碰撞，dataset id 无全局唯一性保证

- **位置**：`:71-72`
- **描述**：`IdType.AUTO` 下 insert 后 id 正常回填，兜底分支几乎不会走；但一旦走到（异常配置/测试桩），`nanoTime()` 跨实例不保证唯一，且不同请求可能得到相同值。`id = "feedback_" + 日期 + feedbackId` 也没有任何去重语义（与 M-3 叠加）。
- **修复建议**：兜底改 `UUID.randomUUID()`；数据集条目 id 用 DB id 回填前判断为空即跳过数据集写入并告警，而不是造伪 id。

### L-2 `isStopWord` 每次调用分配新 `Set.of`，且停用词表与失效分词叠加后形同虚设

- **位置**：`:144-146`
- **描述**：每次调用新建 9 元素 `Set.of`（应提为 `static final`）；更重要的是分词产出的是整句短语（M-1），9 个两字停用词基本永远匹配不上——这张表目前是死代码级别的防护。
- **修复建议**：提为常量；随 M-1 修复分词后同步扩充停用词表。

### L-3 `trimToLength` 按 UTF-16 code unit 截断，可切断代理对（emoji 等）

- **位置**：`:148-157`
- **描述**：`substring(0, maxLength)` 可落在代理对中间，产生孤立代理项；Jackson 序列化时会替换为 U+FFFD，数据集与 DB 中出现乱码字符。对以中文为主的语料影响小，但 answer 允许任意用户内容（含 emoji 表情）。
- **修复建议**：截断后检查 `Character.isHighSurrogate(lastChar)` 则回退一位；或按 code point 截断。

### L-4 每请求 `createDirectories`；append 与轮转之间 `Files.size` 存在 TOCTOU；写文件无 fsync

- **位置**：`:98-100`、`:107`、`:116`
- **描述**：目录创建可移到启动时一次性校验；`exists→size→move` 在并发下是两次独立检查（与 H-2 同源，此处单列为轻微超出 cap 的尺寸误差）；`Files.writeString` 追加无 `force`，进程崩溃时最后若干条反馈数据集条目丢失（DB 行仍在，回到 M-3 的双写漂移）。
- **修复建议**：`@PostConstruct` 校验/预创建目录；H-2 的加锁方案同时消解 TOCTOU；如需崩溃安全，追加以 `FileChannel` 打开并在写后 `force(false)`（按吞吐权衡）。

### L-5 运维可见性：`max-dataset-bytes` 未在 application.yml 暴露；`answer_feedback` 表无保留策略

- **位置**：application.yml:163-165（仅 `enabled`/`dataset-path`）；`FeedbackProperties.java:15`（默认 50 MiB 仅在代码注释中可见）；V8:24-36（表无保留清理，`AuditRetentionScheduler` 不覆盖）
- **描述**：轮转阈值只能靠改配置文件或 relaxed binding 环境变量（`APP_FEEDBACK_MAXDATASETBYTES`）设置，运维在 application.yml 里看不到这个关键容量参数；DB 侧反馈表则完全没有保留/归档策略，随时间无限增长。
- **修复建议**：application.yml 增加 `max-dataset-bytes: ${APP_FEEDBACK_MAX_DATASET_BYTES:52428800}` 一行使其可见可配；为 `answer_feedback` 增加基于 `created_at` 的保留任务（可并入现有 scheduler 体系）。

---

## 修复优先级建议

1. **先做 H-2 + M-3（数据完整性）**：append/轮转加锁与冲突重试 + 表加唯一键防重复——两者共同决定"DB 与评测集双写"的一致性，是用户可见错误（500）与静默丢数据的直接来源，且修复都不需要动对外签名。
2. **再做 H-1（资源安全）**：轮转文件标记化 + 保留清理 + 修正注释/0 值语义；与 M-4 的路径/保留期决策一起做，避免两次改同一段代码。
3. **M-1（功能有效性）**：中文分词修复决定 keywords 三字段的实际价值，依赖项目内分词设施选型，可单独立项。
4. **M-2 与低严重度项**：长度校验、常量化、代理对、配置可见性均为小改动，可随上述任意一批顺带完成。

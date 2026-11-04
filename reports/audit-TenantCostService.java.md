# 缺陷审查报告：TenantCostService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/service/TenantCostService.java`（175 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`TenantBudgetMapper`（全文，`findByTenantId` 为无条件 `SELECT * ... LIMIT 1`，无锁）、`TenantUsageDailyMapper`（全文，`addUsage` 依赖 `ON DUPLICATE KEY UPDATE`，四条 SUM 均 `COALESCE(..., 0)`）、建表 SQL `V8__session_feedback_cost_platform.sql:41-62`（`tenant_budget` 有 `uk_tenant_budget_tenant` 唯一键；`tenant_usage_daily` 有 `uk_tenant_usage_daily_tenant_date` 唯一键，`total_cost_usd DECIMAL(14,6)`；`monthly_budget_usd DECIMAL(12,4)`）、`CostGovernanceProperties`（全文，默认 `enabled=true`、预算 25.0000、硬限默认关闭、divisor=4、三档单价在构造器内置）、`application.yml:185-193`（三档单价与 divisor 均可环境变量覆盖）、`TenantContext`（normalize 空白回退 `"public"`）、`GlobalExceptionHandler.java:15-18`（`IllegalArgumentException` → 400）、`SecurityConfiguration.java:70-71`（`/cost/summary` 需 `PERM_COST_READ`/ADMIN/OPS，`/cost/budget` 需 `PERM_COST_WRITE`/ADMIN）；调用方 grep 核实共 8 类 11 处调用点：`ChatController:120-133`、`CustomerServiceController:44-54`、`RagAnswerService:127-138`、`HybridRagAnswerService:82-95`、`ReactAgentService:413-447`（含流式 `:446-447`）、`WorkflowReactAgentService:313-339`（含流式 `:338-339`）、`ResearchPlannerAgent:34-46`、`ReportWriterAgent:27-39`、`CostGovernanceController:22/:30`（`updateBudget` 的 tenantId 由服务端强制覆盖为当前身份租户，`request.getTenantId()` 不被信任）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm/mvn 操作
- **关联报告**：`reports/audit-WorkflowReactAgentService.java.md`、`reports/audit-ReactAgentService.java.md`、`reports/audit-AuthController.java.md`

---

## 概览

该类是成本治理核心：预算硬限断言（`assertBudget`）→ 用量记账（`recordUsage`）→ 费用汇总（`summary`）→ 预算修改（`updateBudget`）→ token 估算（`estimateTokens`）。做对的部分：

- 记账 upsert 依赖的 `(tenant_id, usage_date)` 唯一键真实存在（`V8__session_feedback_cost_platform.sql:61`），`ON DUPLICATE KEY UPDATE` 的幂等累加语义成立，不会重复建行。
- 防御性取值到位：负 token 用 `Math.max(0, ...)` 归零（`:53-54`）；`divisor` 用 `Math.max(1, ...)` 防除零（`:126`）；全部 SQL 聚合结果经 `safeLong`/`defaultDecimal` 解 null（`:164-170`），配合 mapper 侧 `COALESCE` 双保险，汇总路径无 NPE。
- 单价查找有三级兜底：tier 归一化（trim+lowercase，`:154`）→ 未知 tier 回退 `medium` → 再回退硬编码 `0.0030`（`:157`），配置缺失不会让计费抛异常。
- 除法显式指定 scale 6 + `HALF_UP`（`:161`），不会触发 `ArithmeticException`（non-terminating decimal）。
- 对外入口的租户归属可控：`CostGovernanceController:28` 用 `TenantContext.currentTenantId()` 强制覆盖请求体中的 tenantId，`updateBudget` 无法被用来改别人的预算；`/cost` 两端点在 `SecurityConfiguration:70-71` 有权限守卫。
- 租户归因链路基本可信：认证过滤器以身份租户覆盖请求头（`ApiKeyOrJwtAuthFilter.java:66-68`），`RequestContextFilter` 的 X-Tenant-Id 回退仅在无认证属性时生效。

但存在**并发正确性（预算约束可被击穿）、记账完整性（逃逸请求不入账）、成本估算对 CJK 系统性低估**三类问题。以下所有修复建议均不需变更对外方法签名。

---

## 高严重度

### H-1 `ensureBudget` 先查后插存在竞态，并发首次调用触发 DuplicateKeyException（业务请求直接 500）

- **位置**：`TenantCostService.java:131-146`；唯一键证据：`V8__session_feedback_cost_platform.sql:48`（`UNIQUE KEY uk_tenant_budget_tenant (tenant_id)`）；异常落点：`GlobalExceptionHandler.java:20-25`（未匹配 IllegalArgumentException → 500 "internal server error"）
- **描述**：`ensureBudget` 的流程是 `findByTenantId` → 为 null 则 `insert` → 再 `findByTenantId`。`assertBudget`、`recordUsage`、`summary`、`updateBudget` 全部经由此方法，即**每个租户的首次请求**都会走到这条插入路径。两个并发请求（如同一租户同时发起 chat 与 rag）同时读到 null、同时 `insert`，后者撞唯一键抛 `DuplicateKeyException`，该业务请求以 500 失败。注意这与 `tenant_usage_daily` 不同——那张表的 `addUsage` 用 `ON DUPLICATE KEY UPDATE` 天然幂等，而 `tenant_budget` 的插入路径没有等价处理。异常发生在请求主链路上：租户的首个请求（最常见的是新租户第一次调用 `/ai/chat`）可能随机失败。
- **触发条件**：`tenant_budget` 中尚无该租户行（新租户、或 `V8` 迁移只 seed 了 `'public'`）+ 同租户两个请求并发到达 `ensureBudget`；单机并发或应用多实例下均会发生。
- **影响**：新租户接入期出现偶发 500，且失败的是首个请求，观感是"服务不可用"；多实例部署下概率随实例数放大。
- **修复建议**（不改签名）：捕获 `org.springframework.dao.DuplicateKeyException`（或 `DataIntegrityViolationException`）后重读 `findByTenantId` 返回既有行；或把插入改为 mapper 侧 `INSERT ... ON DUPLICATE KEY UPDATE tenant_id = tenant_id` 风格的幂等语句。两者均在方法内部实现，对外 API 不变。

### H-2 `assertBudget` 为 check-then-act，硬限预算可被并发请求击穿

- **位置**：`TenantCostService.java:28-42`（读 `monthCost` → 比较 → 抛异常，全程无原子累加/预占）；`recordUsage` 的入账发生在业务完成之后的 `:56-63`
- **描述**：硬限判断读的是"当前月已入账成本"，而入账发生在请求完成后的 `recordUsage`。检查与入账之间没有预留：N 个并发请求在同一时刻各读到一个未超限的 `monthCost`，全部通过校验，随后各自入账——实际月成本 = `monthCost + Σ(各请求成本)`，可显著越过 `monthlyBudgetUsd`。对成本治理而言，`hard_limit_enabled=1` 承诺的是"超限请求被阻断"（异常消息即 "tenant budget exceeded, request blocked"，`:40`），但该承诺只在**串行流量**下成立。调用方证据：所有 11 处调用点（ChatController:121、RagAnswerService:128 等）都在请求主链路上调用，无任何串行化措施。
- **触发条件**：租户开启硬限（`hard_limit_enabled=1` 且预算行存在）+ 同租户并发请求 + 月成本接近上限。默认配置下硬限关闭（`defaultHardLimitEnabled=false`），故默认流量不触发；一旦运营侧给某租户打开硬限即暴露。
- **影响**：硬限失去约束力，超支幅度正比于并发窗口内的请求量；对"预算封顶"有合规/商务承诺的场景是实质违约。
- **修复建议**（不改签名）：在 `assertBudget` 内做原子预占——例如在 mapper 新增一条 `UPDATE tenant_usage_daily SET ... WHERE tenant_id=? AND usage_date=? AND (预算余量足够)` 的条件累加，或维护一张预占表；即便简化为"校验后立即以预估值先行入账、完成后冲正差额"，也优于现状的纯后置入账。最小可行替代：将比较与累加改为单条条件 SQL（`UPDATE ... WHERE month_cost + ? <= budget`）以行锁串行化。

---

## 中严重度

### M-1 请求逃逸不入账：`recordUsage` 不在 finally 中，异常/断连的 token 消耗静默丢失，`monthCost` 系统性偏低并反哺 H-2

- **位置**：`TenantCostService.java:56-63`（入账点）；调用方证据：流式路径 `ReactAgentService.java:446-447`、`WorkflowReactAgentService.java:338-339` 中 `recordUsage` 位于流式消费回调内，上游流异常或客户端断连时该行不执行；非流式路径（如 `ChatController:132-133`）若模型调用抛异常同样跳过 `:132` 之后的入账
- **描述**：`assertBudget` 保证了"请求开始前预算够"，但已消耗 token 的入账完全依赖业务代码正常走到 `recordUsage`。LLM 调用恰恰是失败高发点（超时、上游 5xx、SSE 中断、客户端断开）。每次逃逸的消耗都不计入 `monthCost`，而 `assertBudget` 下次判断又以这个偏低的 `monthCost` 为基准——逃逸越多，预算判断越宽松。本类自身无法强制调用方用 finally，但"提供断言、却不提供对账手段"是该设计下的完整性缺口。
- **触发条件**：任何一次 LLM 调用在 `assertBudget` 之后、`recordUsage` 之前失败（生产环境必然周期性发生）。
- **影响**：月成本低估、硬限进一步失守（与 H-2 叠加）；财务对账与租户账单口径偏差。
- **修复建议**（不改签名）：① 在本类新增一个幂等的 `recordUsage(...)` 语义说明并推动调用方将入账移入 `finally`（属调用方改动，超出本报告约束，此处仅建议）；② 更稳妥的是把 `assertBudget` 改为 H-2 建议的"预占先行"，使入账与校验合一时不再依赖调用方行为。

### M-2 token 估算按 codePoint/4，对 CJK 文本系统性低估约 3-8 倍，硬限与账单在中文场景下双双失真

- **位置**：`TenantCostService.java:127-128`（`codePointCount / divisor`，divisor 默认 4）；`application.yml:189`（`token-estimate-divisor` 默认 4）
- **描述**：`divisor=4` 源自英文文本的经验值（约 4 字符 ≈ 1 token）。本项目是中文知识库系统（语料、提问、回答以中文为主），中文约 1-1.5 字符 ≈ 1 token。用 codePoint/4 估算中文 prompt，输入 token 被低估约 4-6 倍；回答同理。由此 `calculateCost`（`:34`、`:55`）算出的估算成本同比例低估，`assertBudget` 的预算判断与 `recordUsage` 的账单都建立在低估基数上。注意 `estimateTokens` 同时被 assert 与 record 使用，两侧口径一致（不会造成 assert/record 漂移），但整体口径对中文失真。
- **触发条件**：任何包含中文的请求——即本系统的主流量，默认配置下即生效。
- **影响**：租户实际 API 开销可达到账面成本的数倍；`monthlyBudgetUsd=25` 的硬限在实际开销达到 $25 时账面可能仅显示 $5 上下，预算治理名存实亡。
- **修复建议**（不改签名）：按文本构成分档估算——对 CJK 码点按 1:1~1:1.5 计、ASCII 部分维持 /4，纯代码点统计在 `estimateTokens` 内即可完成；或为中文场景提供独立 divisor 配置项（`CostGovernanceProperties` 加字段属于配置面扩展，不触碰 `TenantCostService` 对外签名）。至少应在文档/配置注释中说明 divisor=4 仅适用于英文。

### M-3 跨月/跨日边界取时多次调用 `now()`，汇总窗口可能自相矛盾

- **位置**：`TenantCostService.java:69-72`（`YearMonth.now()`、`LocalDate.now()` 分两处取）；`:35`（`assertBudget` 单独再取 `YearMonth.now()`）；`:59`（`recordUsage` 取 `LocalDate.now()`）
- **描述**：`summary` 在 `:69` 取 `YearMonth` 得到 `monthEnd`，在 `:72` 取 `today`。跨月午夜（如 11-30 23:59:59.9 发起、跨到 12-01 执行到 `:72`）时 `today > monthEnd`：`:79-80` 的"今日"查询窗口落在下月（可能查到下月已写入的行——`recordUsage` 在 `:59` 用自己的 `LocalDate.now()`，日期归下月），而 `:74-77` 的月汇总仍停在上月，同一响应里 `todayCostUsd` 不属于 `month` 所示月份，`budgetRemainingUsd` 也不含今日消耗。`assertBudget`（`:35`）与 `recordUsage`（`:59`）各取各的 `now()`，跨月瞬间的判断基准与入账日期同样可能分属两月。
- **触发条件**：恰好跨月/跨日零点的请求（低频但必然发生，且一旦发生数据口径错乱）。
- **影响**：汇总数据自相矛盾，账单口径受污染；跨月瞬间的预算判断用旧月成本（通常远低）作基准，虽然影响只有一瞬，但与 M-1 同向。
- **修复建议**（不改签名）：`summary` 与 `assertBudget` 各自在入口取一次 `LocalDate now = LocalDate.now()`（`recordUsage` 已是单次），由它派生 `YearMonth`/`today`/区间，保证同一次调用内时间基准一致。

### M-4 `updateBudget` 读-改-写整行回写，并发更新互相覆盖，且金额无上限校验

- **位置**：`TenantCostService.java:107-118`（`ensureBudget` 读整行 → setter → `updateById` 整行写）；`:108-111`（仅校验非负，无上限）
- **描述**：两个并发 `updateBudget` 各自基于读到的旧实体做 setter，`updateById` 按 id 全字段 UPDATE（MyBatis-Plus 默认非空字段更新，但本实体所有业务字段均非空），后写者覆盖前写者——例如一个请求把预算调到 50、另一个同时调到 10，最终值取决于线程调度且无任何提示。实体无 `@Version` 乐观锁（`TenantBudget.java` 全文无版本字段）。此外 `monthlyBudgetUsd` 只校验非负：DB 列为 `DECIMAL(12,4)`（上限 99999999.9999），提交更大值时 MySQL 严格模式报错 → `DataIntegrityViolationException` → 500，而非 400 参数错误。
- **触发条件**：同一租户管理员并发修改预算（`/cost/budget` 已有 `PERM_COST_WRITE` 守卫，攻击面小，但正常运营操作即可触发）；或提交超范围金额。
- **影响**：预算被静默回退到旧值（资金风险）；超限金额导致 500 而非可读的 400。
- **修复建议**（不改签名）：① setter 只改目标字段的前提下，改用 mapper 侧按列的定向 UPDATE（只 SET 请求中出现的列）；或接受 last-write-wins 但在响应中回显最终生效值（`summary` 已回显，建议文档化）。② 增加 `> 0` 且 ≤ `DECIMAL(12,4)` 上限的校验，超界抛 `IllegalArgumentException` 走既有 400 通道（`GlobalExceptionHandler:15-18`）。

---

## 低严重度

### L-1 未知 tier 双重回退到 `medium`/硬编码单价，配置笔误静默计费

- **位置**：`TenantCostService.java:154-158`
- **描述**：tier 拼写错误（如配置了 `"hight"` 或调用方传 `"HIGH "` 以外的变体）不报错、不告警，静默按 medium 单价计费；若 `usdPer1kTokens` 整个被置空，最终回退到硬编码 `0.0030`——与 `CostGovernanceProperties` 中的默认值巧合一致，但两处独立维护，未来漂移时排查困难。
- **触发条件**：配置项拼写错误或新 tier 未同步配置。
- **影响**：计费静默偏低/偏高，无任何日志痕迹。
- **修复建议**（不改签名）：未知 tier 时记 WARN 日志（含 tier 与实际采用的单价）；硬编码兜底值改为引用 `CostGovernanceProperties` 的默认常量，消除双源。

### L-2 超预算以 `IllegalArgumentException` 表达，与参数校验异常共用 400 通道，且语义应为 402/429

- **位置**：`TenantCostService.java:40`；映射证据：`GlobalExceptionHandler.java:15-18`
- **描述**："tenant budget exceeded, request blocked" 不是请求参数错误。客户端无法在程序上区分"预算超限"（应停用或升级套餐）与"请求本身非法"（应改参数重试）；异常消息也直接暴露给响应体，属内部策略措辞泄漏。
- **触发条件**：硬限开启 + 超限请求。
- **影响**：客户端重试策略无法正确编写；语义误导。
- **修复建议**（不改签名）：新增 `BudgetExceededException extends IllegalArgumentException` 并在 `GlobalExceptionHandler` 增加对应 handler（402/429 + 专属错误码）——子类化保持既有 catch 兼容，所有调用方代码零改动。

### L-3 应用时区与 DB 会话时区可能不一致，跨零点记录的 `usage_date` 与 `updated_at` 错位

- **位置**：`TenantCostService.java:59`（`LocalDate.now()` 取 JVM 默认时区）；`TenantUsageDailyMapper.java:33-34`（`NOW()` 取 DB 会话时区）
- **描述**：`usage_date` 由 JVM 决定、`created_at/updated_at` 由 DB 决定。两者时区不同时（如容器 UTC、DB 东八区），每天 00:00-08:00（UTC 视角）写入的行 `usage_date` 是"昨天"而 `updated_at` 是"今天"，按 `updated_at` 排查数据时会看到日期错位。
- **触发条件**：应用与 DB 时区配置不一致 + 零点后的请求。
- **影响**：对账/排查时的日期混淆；`usage_date` 本身的汇总口径不受影响（始终一致地按 JVM 时区）。
- **修复建议**（不改签名）：统一两侧时区（部署约定），或在 SQL 中显式 `CONVERT_TZ`/由应用传入 `LocalDateTime`。

### L-4 `assertBudget` 每请求固定 2 次 DB 往返，`summary` 7 次，无任何缓存

- **位置**：`TenantCostService.java:33`（ensureBudget 的 SELECT）+ `:35`（sumCostUsd）；`summary` 共 6 次聚合 + `ensureBudget` 1 次（`:68`）；`updateBudget` 末尾再调 `summary`（`:119`）重复 `ensureBudget`
- **描述**：每个 LLM 请求在主链路上都付出 2 次串行 DB 往返的延迟；`updateBudget` 里 `summary(tenant)` 再次走 `ensureBudget`，多一次冗余 SELECT。量级不大，但 `assertBudget` 位于所有 11 个调用点的关键路径上。
- **触发条件**：所有 LLM 请求（常驻路径）。
- **影响**：尾延迟叠加；DB 连接占用随 QPS 线性增长。
- **修复建议**（不改签名）：预算行（每租户单行、变更低频）可加短 TTL 本地缓存或 Caffeine，`updateBudget` 时失效；`updateBudget` 直接复用已查出的 budget 计算汇总所需字段，免二次查询。

### L-5 `summary` 未校验调用方传入 tenantId 的归属，服务方法完全信任参数

- **位置**：`TenantCostService.java:66-67`
- **描述**：`summary(tenantId)` 对任意 tenantId 均返回该租户的预算与用量。当前唯一入口 `CostGovernanceController:22` 传 `TenantContext.currentTenantId()`（来自认证身份，不可伪造），故现网不可利用；但任何未来调用方（新 controller、内部任务）传错参数即构成跨租户信息泄露，该类自身无防线。
- **触发条件**：未来新增调用方未按约定传当前身份租户。
- **影响**：潜在跨租户成本数据泄露（纵深防御缺口，非现行漏洞）。
- **修复建议**（不改签名）：在类 Javadoc 中显式声明"tenantId 参数必须来自服务端可信身份"；或新增 `assertTenantSelf(tenantId)` 风格的内部校验（对比 `TenantContext.currentTenantId()`）供新调用方使用。

---

## 修复优先级建议

1. **先修 H-1**（一行 catch + 重读即可收敛，风险最低收益最高——消除新租户首请求随机 500）。
2. **H-2 与 M-1 是同一问题的两面**（校验与入账不原子、且入账可逃逸）：建议一并按"预占先行"方案设计，一次改造同时解决击穿与低估入账。
3. **M-2（CJK 低估）影响所有默认配置流量的账单准确性**，且改动局限在 `estimateTokens` 内，建议紧跟其后。
4. M-3（时间基准统一）是局部小改，可与 M-2 同批处理；M-4 的金额上限校验属参数校验补强，顺手完成。
5. 低严重度按 L-1（可观测性）→ L-2（异常语义）→ L-4（性能）→ L-3/L-5（部署约定与文档化）顺序处理，均不阻塞上线。

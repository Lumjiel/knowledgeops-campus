# 缺陷审查报告：ModelRouter.java

- **审查对象**：`src/main/java/com/enterprise/iqk/llm/ModelRouter.java`（234 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`ModelRouterProperties`（全文，含默认值：`enabled=true`、实验 `enabled=true`/trigger `ab_auto`/50%）、`ModelAbExposure`（`@TableId` 存在）与 `ModelAbExposureMapper`（BaseMapper.insert）、建表 SQL `V8__session_feedback_cost_platform.sql:67-80`（`subject_key VARCHAR(128)`、**无唯一约束**）、`AuditRetentionScheduler`（grep 核实不清理 `model_ab_exposure`）、`application.yml:125-150`（economy/balanced/quality 三 profile 已配置）；调用方核实全部 9 处：`ChatController:119`、`CustomerServiceController:39`、`RagAnswerService:126`、`HybridRagAnswerService:80`、`ReactAgentService:52/:122`、`WorkflowReactAgentService:308`（subjectKey 均为 chatId）、`ResearchPlannerAgent:33` 与 `ReportWriterAgent:26`（subjectKey 为**用户自由文本 topic**）；二参重载 `resolve(String, String)` 经 grep 核实无生产与测试调用方
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm/mvn 操作
- **关联报告**：`reports/audit-WorkflowReactAgentService.java.md`、`reports/audit-DeepResearchService.java.md`（如已产出）、`reports/audit-AgentWorkflowEngine.java.md`、`reports/audit-AuthController.java.md`

---

## 概览

该类是所有 LLM 调用的模型选型入口：profile 归一化 → 手动覆盖/AB 实验分桶 → 禁用/启用两条解析路径 → fallback 链 → 曝光记录。做对的部分：

- 兜底链健壮且防环：`resolveEnabled` 用 `visited` 集合防 fallback 环（`:142-165`），链断后还有 `first_enabled_fallback`（`:166-178`）与最终 `DEFAULT_MODEL`（`:179`）两级兜底，任何配置错误都不会导致路由失败。
- 曝光写入失败不阻断路由（`:86-88`），注释明确了"exposure logging should never break routing path"的设计意图。
- `Math.floorMod` 处理负 hashCode 正确（`:114`）；profile 统一小写归一化（`:201-206`）；决策携带 `reason`/`fallbackApplied`/实验三元组，可观测性设计好。
- 配置面完整：endpoint 默认 profile（chat/service/rag）在 yml 显式可配。

但存在**实验数据完整性与曝光表治理**两类问题：分桶 subject 完全由客户端控制、曝光表按调用无去重写入且无任何保留策略、实验逻辑不受路由总开关门控。以下发现均不需变更任何对外方法签名即可修复。

---

## 高严重度

> 本类无传统意义上的安全/可用性高危项；以下两条按"对核心业务目标（实验结论可信、成本核算准确）的破坏力"列为高。

### H-1 AB 分桶 subject 由客户端完全控制，实验分配可被任意操纵

- **位置**：`ModelRouter.java:112-115`（`normalizedSubject = subjectKey.trim()` 后 `(tenantId + "|" + endpoint + "|" + subject).hashCode()` 分桶）；调用方证据：subjectKey 全部来自请求参数——`ChatController.java:119`、`CustomerServiceController.java:39`、`RagAnswerService.java:126`、`ReactAgentService.java:52` 均传 `chatId`，`ResearchPlannerAgent.java:33`、`ReportWriterAgent.java:26` 传**自由文本 topic**
- **描述**：`chatId` 是客户端随请求提交的字段：用户只要换一个 chatId 就能重掷自己的实验分桶（对 100 个候选值试一遍即可找到 quality 桶），也可以全程固定使用落在 cost 桶的 chatId——前者让 quality 变体的成本样本被低质量用户污染，后者让规避者永远用便宜模型。`subject_key` 又无服务端归属校验（不验证 chatId 真属于该用户/会话）。research 链路更直接：topic 是任意文本，同一用户每个 topic 分桶都不同，实验单元从"会话"退化为"单次请求"。
- **触发条件**：`profile=ab_auto` 的请求（实验默认开启，trigger 即 `ab_auto`）。
- **影响**：quality vs cost 的实验结论不可信——按变体聚合的满意度/成本差可能全部来自用户自选择偏差；成本治理数据失真。
- **修复建议**：分桶 subject 改为服务端可信标识（如按 tenant+principal 级别的稳定哈希，或服务端会话表中的 chatId 归属校验后使用）；至少对 research 链路改传 `conversationId`（稳定、服务端已知）而非 topic。对外签名不变（参数含义收紧）。

### H-2 曝光记录按调用无去重写入，无采样、无保留策略——表无限增长且分析前必须先去重

- **位置**：`ModelRouter.java:67`（每次 resolve 都调 `saveExposure`）、`:71-89`（无条件 insert，无 (experiment_key, subject_key) 去重）；建表证据：`V8__session_feedback_cost_platform.sql:67-80` 无唯一键；保留策略证据：`AuditRetentionScheduler` 不覆盖 `model_ab_exposure`
- **描述**：每次命中实验或手动覆盖都同步插入一行曝光记录：同一次 DeepResearch 请求至少写 2 行（planner + writer 各一次，`:33`/`:26`）；同一 chatId 的多轮对话每轮追加；手动 `quality_first`/`cost_first`（`:96-101`，experimentKey 恒非空）**每次请求必写一行**——即使实验从未配置。行数正比于请求量，表无唯一约束、无分区、无清理任务。`created_at` 仅有 `(tenant_id, created_at)` 索引，按 experiment_key 的分析查询还会全表扫。
- **触发条件**：使用 `ab_auto` 或手动 quality/cost profile 的流量持续到来（生产默认配置下即生效）。
- **影响**：表膨胀拖慢 insert 与分析查询；同一 subject 的大量重复行让实验分析必须先去重且无法直接算"曝光用户数"；在 LLM 调用的关键热路径上为每次请求增加一次同步 DB 写（延迟直接叠加在 chat/rag 响应上）。
- **修复建议**：曝光表加 `UNIQUE KEY (experiment_key, subject_key)` + `INSERT IGNORE`/幂等 upsert（首次曝光语义，重复调用零成本）；或改为内存采样后异步批量写（`@Async`/队列），把 DB 写移出请求路径。均不改 `resolve` 对外签名。

---

## 中严重度

### M-1 实验与手动覆盖不受 `model-router.enabled` 总开关门控

- **位置**：`ModelRouter.java:47`（`applyExperiment` 在 `:51` 的 `isEnabled()` 判断**之前**执行）、`:96-101`（手动覆盖同样在前）
- **描述**：`app.model-router.enabled=false` 的语义是"关闭路由器"，但 `applyExperiment` 照常执行：`ab_auto` 请求仍被分桶、`saveExposure` 仍写曝光行（决策里的 experimentKey 非空），initialProfile 被改成 `quality`/`economy` 后进入 `resolveDisabled`——yml 里这两个 profile 已定义时（当前默认如此），**关闭的路由器仍在按实验结果改写模型选型**。手动覆盖可能是有意保留的逃生门，但实验分桶在路由关闭时继续运行并产生曝光数据，明显违背开关语义。
- **触发条件**：`APP_MODEL_ROUTER_ENABLED=false` + 实验保持默认开启（`APP_MODEL_ROUTER_AB_ENABLED` 默认 true）+ 客户端发送 `profile=ab_auto`。
- **影响**：运维关闭路由器后实验流量与曝光数据继续产生，排查"为什么关了还在分桶"无从下手（无日志）；两套开关（router.enabled / experiment.enabled）语义边界不清。
- **修复建议**：`resolve` 中当 `!modelRouterProperties.isEnabled()` 时跳过 `applyExperiment`（手动覆盖是否豁免需产品决策，至少实验部分应门控）；或在 yml 注释/文档中明确两级开关的关系。

### M-2 `hasRoute` 忽略 `enabled` 标志：路由禁用模式下 `enabled=false` 的 profile 仍然直连

- **位置**：`ModelRouter.java:195-199`（`hasRoute` 只查 containsKey + model 非空，不查 `route.isEnabled()`）；对照 `:147`（启用路径要求 `route.isEnabled()`）
- **描述**：运维用 `enabled=false` 下线某个 profile（例如 economy 模型要下架）时，启用路径会正确跳过它走 fallback；但只要 `model-router.enabled=false`，`resolveDisabled` 经 `hasRoute` 仍把请求直连到该已下线 profile 的 model。同一个 `enabled` 标志在两条解析路径下语义不同。
- **触发条件**：`APP_MODEL_ROUTER_ENABLED=false` + 任一 profile 配置 `enabled: false` + 客户端点名该 profile。
- **影响**：已下线模型继续被调用（可能已停计费/停服），行为不一致且难以复现。
- **修复建议**：`hasRoute` 补 `&& modelRouterProperties.getProfiles().get(profile).isEnabled()`，与 `resolveEnabled:147` 对齐。

### M-3 分桶哈希无实验隔离与租户归一，跨实验/大小写变体会产生相关性偏斜

- **位置**：`ModelRouter.java:113`（哈希输入不含 experimentKey；`tenantId` 来自 `normalizeTenant`，只 trim 不小写，`:212-217`）
- **描述**：哈希输入为 `tenantId|endpoint|subject`，不含 `experimentKey`：未来在同一 subject 上开第二个实验，其分桶与 `quality_vs_cost` 完全相关（同 hash 同桶）——两个实验的变体组合不再独立，交叉分析失真。另外租户 ID 未做大小写归一（`TenantContext.normalize` 也只 trim），`Public` 与 `public` 会落不同桶，若上游租户来源大小写不严会引入系统性的分桶漂移。
- **触发条件**：第二个实验上线复用同一分桶逻辑；或租户标识大小写不一致的调用方。
- **影响**：实验间污染；分桶不可复现。
- **修复建议**：哈希输入加入 `experimentKey`（`experimentKey + "|" + tenant + ...`）；`normalizeTenant` 统一大小写（与全项目租户归一策略对齐，可复用 `TenantContext.normalize` 后再 lower）。

---

## 低严重度

### L-1 二参 `resolve` 重载无任何调用方，且预设值会制造脏曝光数据

- **位置**：`ModelRouter.java:38-40`（硬编码 `tenantId="public"`、`subjectKey=""`）
- **描述**：grep 核实生产与测试代码均无调用。若未来被使用：所有调用共享同一哈希输入（tenant=public, subject=na）→ **全部落同一桶**，实验完全倾斜；曝光记录租户一律记成 public，`subjectKey="na"` 无法归因。
- **修复建议**：删除该重载（公开 API 收缩不影响现有调用方），或至少标注 `@Deprecated` 并在 javadoc 写明风险。

### L-2 曝光链路全程静默：mapper 缺失、DB 故障、字段超长全部无声

- **位置**：`ModelRouter.java:29-36`（`initMapper` 吞异常返回 null，无日志）、`:86-88`（`saveExposure` catch-all 且无日志）
- **描述**：`subject_key VARCHAR(128)`（V8:71）而 research 链路传的 topic 常超 128 字符 → insert 必然失败 → 被 `:86` 静默吞掉：research 流量的曝光数据在无人知晓的情况下整体缺失。mapper 为 null（`:32-35`）同样无任何日志。排障时"为什么没有曝光数据"没有第一条线索。
- **修复建议**：catch 块加 `log.debug/warn`（低频、带 endpoint/tenant）；subject 超长先截断到 128 再写；mapper 缺失时启动期 log.info 一次。
- **补充**：研究链路传 topic 还带来每 topic 一桶（见 H-1），截断不能解决分配问题，只是保住数据能落库。

### L-3 `normalizeTenant` 与 `TenantContext.normalize` 重复实现

- **位置**：`ModelRouter.java:212-217`；对照 `TenantContext.java:14-16`（同为 trim + 默认 "public"）
- **描述**：行为目前一致，但两份实现会漂移（TenantContext 若调整默认租户或归一规则，这里不会跟）。项目其他处（AuthController、engine 等）均直接复用 TenantContext。
- **修复建议**：改调 `TenantContext.normalize`；若刻意避免依赖 security 包，注释说明原因。

### L-4 哨兵值不统一：`"na"`、`"unknown"`、`-1` 三套并存

- **位置**：`ModelRouter.java:79`（subject 空→`"na"`）、`:80`（endpoint 空→`"unknown"`）、`:81`（bucket null→`-1`）、`:82`（variant 空→`"unknown"`）
- **描述**：分析侧需要同时知道三种哨兵约定才能过滤脏数据；`bucket=-1`（int）与 `null`（未实验）语义不同但落库后难以区分——实际 `saveExposure` 仅在 experimentKey 非空时写入，`-1` 只出现在手动覆盖路径（bucket 恒为 100/0），真正的 `-1` 几乎不可达，属僵尸分支。
- **修复建议**：统一哨兵约定并写在表注释/迁移脚本里；手动覆盖路径可显式不写 bucket 或用独立列。

### L-5 `first_enabled_fallback` 依赖配置 Map 的绑定顺序

- **位置**：`ModelRouter.java:166-178`（遍历 `profiles` LinkedHashMap，取第一个可用项）
- **描述**：Spring 把 yml 中的 map 绑定为 LinkedHashMap，"第一个可用 profile"实为 yml 书写顺序：economy 在前，配置重排或换环境变量注入方式（如 Kubernetes ConfigMap 展平 key）后顺序可能变化，兜底目标在不同环境间不可复现。
- **修复建议**：兜底目标显式化——用 `defaultProfile` 作为最终 fallback（而非"任意第一个"），或在 yml 加 `fallback-order` 列表。

---

## 修复优先级建议

1. **P0**：H-1（分桶 subject 改服务端可信标识——决定实验结论是否可信）；H-2（曝光表幂等/异步化——热路径写与表膨胀，`UNIQUE KEY` 一条迁移即可显著缓解）。
2. **P1**：M-1（实验受总开关门控）；M-2（hasRoute 对齐 enabled 语义）；M-3（哈希加 experimentKey + 租户归一）。
3. **P2**：L-1~L-5 随下次触碰相关代码时清理（L-1 可直接删重载）。

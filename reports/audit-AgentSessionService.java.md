# 缺陷审查报告：AgentSessionService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/service/AgentSessionService.java`（454 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`AgentSessionStateMapper`（全文，含全部 SQL）、`TenantContext`、`SqlLikeUtils`、`AgentSessionStateVO` / `AgentSessionBranchVO` / `AgentSessionMessageVO` / `AgentSessionStateRecord` / `PagedResult`、唯一调用方 `AgentSessionController`（全文）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件
- **关联报告**：`reports/audit-WorkspaceRuntime.java.md`、`reports/audit-WorkflowReactAgentService.java.md`、`reports/audit-ReactAgentService.java.md`

---

## 概览

该类是 agent 会话状态（分页列表 / 详情 / upsert / 置顶 / 归档 / 分支 compare 与 merge / 工作流快照挂接）的读写服务。做得好的部分值得先说明：全部 SQL 走参数化绑定且统一带 `tenant_id` 过滤（无越租户面）；关键字经 `SqlLikeUtils.escapeForLike` 转义后再进 `LIKE`（防通配符放大）；upsert 用 `lock_version` 乐观锁循环并对 insert 撞唯一键做了显式重试（`AgentSessionService.java:82-101`）。**未发现 SQL 注入、跨租户越权或反序列化类的高危漏洞**，故本报告无 H 级发现；但并发一致性（pin/archive 旁路乐观锁导致的静默丢更新）与 merge 的指纹去重、null 消息 NPE 等中低缺陷合计 6 + 8 项，其中 M-1/M-2 会真实丢用户数据。所有修复均不需变更对外 API 签名。

---

## 中严重度

### M-1 pin/archive 直更列不递增 `lock_version`，并发的全量 upsert 会静默覆盖置顶/归档状态（丢更新）

- **位置**：`AgentSessionService.java:138-147`（setPinned）、`:149-158`（setArchived）；`AgentSessionStateMapper.java:73-81`（updatePinned 只 `SET pinned, updated_at`）、`:83-91`（updateArchived 同理，均 **不动 lock_version**）；`AgentSessionStateMapper.java:99-100`（`updateSessionStateIfUnchanged` 会用 payload 值覆写 `pinned`、`archived` 两列）
- **描述**：`pinned`/`archived` 有两条写路径——直接列更新（不递增 `lock_version`）和全量 payload upsert（递增）。两者写同一组列但互不可见：upsert 的乐观锁只能保护"它自己读→写窗口"内的一致性，而 pin 的直更发生在该窗口之外且不留版本痕迹。
- **触发条件**：① 客户端 A 发起 upsert（payload 携带 `pinned=false`，读到 `lock_version=N`）；② 客户端 B 调 `POST /pin?value=true`，`updatePinned` 置 `pinned=1`，`lock_version` 仍为 N；③ A 的 CAS 更新（`WHERE lock_version=N`）命中成功，把 `pinned` 写回 0。B 的置顶**无任何冲突报错地丢失**。归档同理（更危险：已归档会话被并发 upsert 复活为未归档，`list` 默认过滤 `archived=0`，它会重新出现在列表里）。
- **影响**：用户可感知的状态回退，且完全静默；乐观锁机制的存在让人误以为这类竞争已被覆盖。
- **修复建议**：`updatePinned`/`updateArchived` 增加 `lock_version = lock_version + 1`（与列更新同语句内完成）；或让 `updateSessionStateIfUnchanged` 不再从 payload 覆写 `pinned`/`archived` 两列（状态开关只走专用端点）。

### M-2 setPinned/setArchived 是"直更 → 重读 → 全量 upsert"的非原子组合写，且首写完全冗余

- **位置**：`AgentSessionService.java:140-146`（先 `updatePinned`，再 `get()`，再 `upsert()`）、`:151-157`（同构）
- **描述**：整个方法等价于"把会话快照读出来、改一个布尔、整体重写"，其中第一次直更唯一的作用是存在性检查（`updated<=0` 抛 not found）。组合窗口内丢更新的具体链：`get()` 读到快照 S → 并发 upsert 写入新 payload → 本方法 `upsert(S')` 内部重读后**用 S' 的旧字段值覆盖新 payload 的 title/branches 等**——`updateSessionStateIfUnchanged` 的乐观锁只覆盖 upsert 自己的读窗口，覆盖不了 get() 到 upsert() 之间的外部写入。另外每次置顶/归档要 4 次往返（update、select、循环内 select、update、再 select），并对整份会话 JSON 做无谓重写。
- **触发条件**：pin/archive 与任意并发 upsert（或 merge）交叠；单用户双开标签页即可复现。
- **影响**：并发下丢会话内容（分支、消息），与 M-1 叠加后 pin/archive 是最容易踩中的入口。
- **修复建议**：砍掉组合写——保留直更作存在性检查（修完 M-1 后它已递增版本），随后直接 `return get(tenant, sessionId)`，不再走全量 upsert；`state.setPinned(...)`/`upsert` 两行删除即可，行为不变、竞态消失。**注意与 M-1 的修复绑定**：若直更不递增版本，仅做本条修复仍残留 M-1 的覆盖链。

### M-3 mergeBranches 按 `role::规范化content` 指纹去重，合法的重复消息被静默丢弃

- **位置**：`AgentSessionService.java:203-213`（合并循环：指纹命中即 `continue`）、`:349-357`（`fingerprint` = role + `"::"` + 空白归一化 content）
- **描述**：指纹只含角色和正文，不含消息 id 与时间戳。两类误杀：① source 分支里同一条消息出现两次（聊天里用户重发同一问题极常见），第一份合并后指纹入集，第二份在 `:207` 被判为重复**静默跳过**——连计数都不体现（`mergedMessageCount` 只报合并后的总数）；② source 与 target 中"同角色+同文案"的两条**不同消息**（如两条"好的，继续"）被视为同一条。相反方向的漏洞也存在：`"a::b"` 这样的正文与 role 拼接理论上可拼出歧义指纹，实际风险低。
- **触发条件**：对包含重复文案消息的分支执行 `POST /branches/merge`；无需任何并发。
- **影响**：合并结果丢失用户消息，且调用方从返回值看不出有任何删减。
- **修复建议**：指纹加入 `id`（有 id 时按 id 去重，无 id 才退化为内容指纹）；或在结果 VO 中增加 `skippedDuplicateCount`，让丢弃可见。

### M-4 `copyMessages` 对 null 元素直接 NPE，一条脏数据永久打断该会话的合并

- **位置**：`AgentSessionService.java:364-366`（`item.getId()` 无判空）；对比 `:301-303`（`toMessageFingerprintSet` 明确容忍 null 消息）——同类逻辑防护不一致
- **描述**：`session_payload` 是客户端可控 JSON；`messages` 数组中的字面 `null` 元素经 Jackson 反序列化后就是 List 里的 null 引用。`copyMessages` 直接解引用。`compareBranches` 不经过 `copyMessages` 所以能跑通，`mergeBranches` 则必炸。
- **触发条件**：payload 中 `"messages": [null, {...}]`（手写 API 调用或上游写入缺陷均可造成），随后调用 merge——每次都 500，且因 payload 不会被自动修复而**持续复现**。
- **影响**：单会话 merge 功能永久不可用；500 无业务语义。
- **修复建议**：`copyMessages` 循环首行 `if (item == null) continue;`，与 `toMessageFingerprintSet` 的容错对齐；`ensureUniqueMessageId` 已有判空（`:379`），说明作者意识到 null 可能出现但漏了这一处。

### M-5 `list` 的 pageSize 无上限，且每行携带整份会话 JSON（SELECT *），可单请求打爆内存

- **位置**：`AgentSessionService.java:46`（`safePageSize = Math.max(1, pageSize)`，无上界）；`AgentSessionStateMapper.java:48`（`SELECT *` 含 `session_payload` 列）、`:62-63`（`LIKE CONCAT('%',…,'%')` 前置通配、无法走索引）
- **描述**：`GET /ai/sessions?pageSize=100000` 会把该租户全部会话（每行含完整对话 JSON，含 memorySnapshot/workflowState 等大对象）一次性载入内存并序列化返回。`session_payload` 随对话增长无上限（upsert 对 payload 大小也无校验），列表页实际只需要 title/workspace/时间等元数据。
- **触发条件**：任一已认证租户用户，一个请求即可；配合并发更是放大。
- **影响**：堆压力 / Full GC / OOM，波及全服务；也是响应体积攻击面。
- **修复建议**：service 层给 `safePageSize` 设上限（如 `Math.min(pageSize, 100)`）；列表 SQL 改为显式列（不含 `session_payload`）；controller 层可再加请求参数上限校验兜底。

### M-6 分页排序键不唯一，翻页可重复/漏行

- **位置**：`AgentSessionStateMapper.java:62`（`ORDER BY pinned DESC, updated_at DESC`，无 tiebreaker）
- **描述**：`updated_at` 精度为秒级（`LocalDateTime.now()`/DB `NOW()`），同一秒内的批量写入（如批量导入、并发 upsert）产生大量并列值；LIMIT/OFFSET 分页在不同页的两次查询间对并列行的顺序无保证，同一行可能在第 1、2 页都出现，另一行被跳过。
- **触发条件**：同一秒内多条会话 `updated_at` 相同 + 翻页；数据量越大越常见。
- **影响**：前端列表丢项/重复项，用户以为会话"消失"。
- **修复建议**：`ORDER BY pinned DESC, updated_at DESC, session_id` 补唯一 tiebreaker；长期可改 keyset 分页（同时缓解 M-5 的深翻页 offset 扫描）。

---

## 低严重度

### L-1 `readJson` 解析到 JSON 字面量 `null` 时返回 null，`toSessionState` 随即 NPE

- **位置**：`AgentSessionService.java:250-251`（`payload.setId(...)` 无判空）、`:410-419`（readJson 对合法 JSON `"null"` 返回 null 对象）
- **触发条件**：`session_payload` 列被写入了字符串 `null`（合法 JSON）。修复建议：`readJson` 结果为 null 时回退 `new AgentSessionStateVO()`。

### L-2 `ensureUniqueMessageId` 只重命名一次，新 id 仍可能撞车

- **位置**：`AgentSessionService.java:387-390`（`id + "-m" + existingIds.size()` 不查重直接采用）；`:384`（生成 id 用 `fingerprint.hashCode()`，有碰撞可能，靠时间戳部分缓解）
- **触发条件**：source 中已存在恰好叫 `<id>-m<N>` 的消息 id，或两次合并发生在同毫秒且指纹 hashCode 相同。影响：合并后两条消息共用 id，前端按 id 定位消息时行为错乱。修复建议：`while (existingIds.contains(id))` 循环加后缀，或改用 `ThreadLocalRandom`/UUID。

### L-3 compare/merge 不拒绝 `sourceBranchId == targetBranchId`

- **位置**：`AgentSessionService.java:160-163`、`:197-198`（findBranch 后无同分支校验）
- **触发条件**：对同一分支自合并：全部指纹命中 → 新分支携带 target 消息的完整副本插入 `branches[0]` 并被设为 active（`:224-227`），纯冗余克隆；且每次 merge 分支数只增不减、整个 payload 每次全量重写（重复 merge 呈 O(n²) 存储增长）。修复建议：入口处 `Objects.equals(source, target)` 抛 IllegalArgumentException；可考虑给分支数设上限。

### L-4 `attachWorkflowSnapshot` 找不到消息时仍做全量 upsert，且成败无区分

- **位置**：`AgentSessionService.java:430-444`（branch 找到但 messageId 未命中时，循环空转后照样 `upsert` 重写整份 payload、递增 `lock_version`、刷新 `updated_at`）；`:431`（branches 为 null 时静默原样返回，与上一分支行为不一致）
- **触发条件**：上游传错 messageId——一次 no-op 写放大，还可能与并发写撞乐观锁抛 500；调用方无法从返回值判断挂接是否真的发生。修复建议：命中标志位，未命中抛 IllegalArgumentException 或返回标记；无变更时跳过 upsert。

### L-5 `writeJson`/`readJson` 吞掉异常原因且不打日志

- **位置**：`AgentSessionService.java:405-407`、`:416-418`（`catch (Exception ex)` 后仅抛固定文案）
- **触发条件**：payload 含不支持的结构/超长内容，或库中存量数据损坏——线上只有一句 "failed to parse session payload"，无 cause 无堆栈，排障只能靠猜。修复建议：`new IllegalArgumentException(msg, ex)` 保留 cause，并 `log.warn` 记录 sessionId。

### L-6 sessionId trim 语义在各端点不一致

- **位置**：`get`/`upsert` 走 `findRecord`/显式 `trim()`（`AgentSessionService.java:75`、`:242`）；`setPinned`/`setArchived` 把**原始** sessionId 直接传 mapper（`:140`、`:151`）；`compareBranches`/`mergeBranches` 经 `get`（trim）但 upsert 用的是未 trim 的 `sessionId`（`:230`、`:444`）
- **触发条件**：sessionId 带首尾空白（URL path 变量一般不会，但服务也可能被内部代码复用调用）：`get(" abc ")` 成功而 `setPinned(" abc ")` 抛 not found；merge 内部 `get` 命中 trim 后的会话、`upsert` 却可能对未 trim 的 id 走 insert 撞唯一键。修复建议：在每个 public 方法入口统一 `trim()` 一次。

### L-7 时间源三处混用，跨 JVM/DB 时区时 `updated_at` 漂移

- **位置**：`AgentSessionService.java:107`、`:135`（JVM 时区的 `LocalDateTime.now()`）、`AgentSessionStateMapper.java:75`、`:85`（DB 的 `NOW()`）、`AgentSessionService.java:259`（回读时按 `ZoneId.systemDefault()` 折算 epoch）
- **触发条件**：应用服务器时区 ≠ DB 会话时区（容器化部署常见 UTC vs CST）：pin 走 DB `NOW()`、upsert 走 JVM now，两者写出的 `updated_at` 差一个时区偏移，列表排序（M-6 的排序键）随之抖动，VO 的 `updatedAt` 也失真。修复建议：统一时钟源——全部用 DB `NOW()`，或全部用 Java 侧 UTC（`Instant.now()` + 数据库 TIMESTAMP）。

### L-8 写后 `get()` 重读非原子，返回值可能是别人的写入

- **位置**：`AgentSessionService.java:87`、`:97`（update/insert 成功后再 `get(tenant, ...)`）
- **触发条件**：写提交与重读之间另一写者提交——调用方收到的是**别人的**状态（自己刚写的值没体现在响应里）；极端情况下并发删除会让刚写成功的请求抛 "session not found"。影响低（数据本身没丢），但响应语义有误导。修复建议：由本次写入的记录直接组装返回 VO（字段都在手上），或接受现状并在注释中声明语义。

---

## 修复优先级建议

1. **先做 M-1 + M-2**（绑定修复：pin/archive 直更递增版本号 + 去掉组合写）——这是唯一会静默丢用户数据的路径，改动小、风险低。
2. **M-4 + M-3**（copyMessages 判空 + 去重指纹加 id）：都是 merge 路径的单点修复，M-4 一行即可。
3. **M-5 + M-6**（pageSize 上限 + 排序 tiebreaker）：列表面 DoS 与翻页正确性，改 service 一行 + mapper ORDER BY 一处。
4. L 级按顺手程度处理，L-5（保留异常 cause）建议随任意一次相关改动带上。

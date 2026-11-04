# 架构分析报告：AgentSessionService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/service/AgentSessionService.java`（454 行）
- **核查范围**：该类全部出边（mapper 六段注解 SQL 逐段下钻、util/security 叶子）与全部入边（grep 全库含 test）；`AgentSessionController` 七个端点的租户来源；建表 DDL（`V8__session_feedback_cost_platform.sql` 的 `agent_session_state` 与 `V16__agent_session_state_lock_version.sql`）与 mapper SQL 的对应关系；`SqlLikeUtils` 实现
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-AgentSessionService.java.md`（同文件缺陷审查，并发/竞态类发现以该报告为准）、`arch-ReactAgentService.java.md`（MDC 租户取用的对照）、`audit-MemoryService.java.md`（死 API 同型发现）

## 1. 模块定位

该类是 agent 会话状态的持久化与用例服务（service 包），管理 `agent_session_state` 单表的完整生命周期：分页查询（`list` :38）、读（`get` :62）、全量 upsert（:67，带乐观锁重试环 :82-102）、钉选/归档（:138/:149）、分支对比与合并（:160/:190）、workflow 快照挂接（`attachWorkflowSnapshot` :425）。

它本质上是一个**JSON 文档存储的门面**：分支/消息/轨迹全部序列化进 `session_payload LONGTEXT`（`V8:12`），表上的标量列（title/workspace_id/model_profile/streaming/pinned/archived/active_branch_id）是 payload 的**反规范化投影**，存在的理由是索引与列表查询（`V8:18-22` 两个索引均建在这些列上）。理解这一定位是评估其边界的关键——见 4.1。

唯一调用方是 `AgentSessionController`（`/ai/sessions`，7 个端点，`AgentSessionController.java:25`）。

## 2. 依赖全景

### 2.1 出边（注入的 2 个协作者 + 静态工具）

| 依赖 | 所在包 | 该协作者自身的依赖（已下钻核实） | 方向评价 |
| --- | --- | --- | --- |
| `AgentSessionStateMapper` | mapper | MyBatis-Plus `BaseMapper` + 6 段注解 SQL；SQL 全部带 `tenant_id = #{tenantId}` 过滤 | ✅ 干净的下游；SQL 已逐段核实（见 2.1.1） |
| `ObjectMapper` | 基础设施 | — | ✅ |
| `TenantContext` | security | 仅 spring util，纯静态叶子 | ✅ |
| `SqlLikeUtils` | util | 零依赖纯静态类 | ✅ |
| `AgentSessionStateRecord` + 6 个 VO | domain | 数据类 | ✅ |

**2.1.1 mapper SQL 下钻结论**（与 DDL 互相印证）：

- `findByTenantAndSessionId`/`countByTenant`/`findByTenant`/`updatePinned`/`updateArchived`/`updateSessionStateIfUnchanged` 六段 SQL **全部** `WHERE tenant_id = #{tenantId}`——租户过滤无遗漏；
- `updateSessionStateIfUnchanged` 的 CAS（`AND lock_version = #{expectedLockVersion}`，`lock_version = lock_version + 1`）与 `V16` 迁移注释声明一致，"乐观锁"声明属实（对照 audit-AgentWorkflowEngine 的教训：凡声称 CAS 的必须下钻 SQL——本处核实通过）；
- `DuplicateKeyException` 重试路径（:88-90）成立的前提 `uk_agent_session_tenant_session (tenant_id, session_id)` 在 `V8:15` 落实；
- 关键字 LIKE（`LOWER(...) LIKE CONCAT('%', LOWER(#{keyword}), '%')`）依赖 service 层 :49 预先经 `SqlLikeUtils.escapeForLike` 转义，MySQL 默认反斜杠转义符下语义正确——但这是一条**跨层口头契约**，见 4.3。

### 2.2 入边

- `AgentSessionController.java:25` —— main 源码唯一调用方，7 端点覆盖除 `attachWorkflowSnapshot` 外的全部公开方法。
- `attachWorkflowSnapshot`（:425-445）**全库零调用**（grep main+test 证实）——死 API，见 4.2。
- 无任何 agent.* / workflow 包反向依赖本类；本类也不 import 任何 agent.* 类。

### 2.3 横切依赖

- `TenantContext.normalize`：本类**不直读 MDC**，tenantId 全部由调用方显式传参、本类入口统一 `normalize`（:44/:74/:139/:150/:242）。与 `AgentSessionController` 每端点 `TenantContext.currentTenantId()`（请求线程内同步读取）配对，是全库租户上下文传递的**正确样板**——与 `ReactAgentService.stream()` 在 `Flux.defer` 内读 MDC（arch-ReactAgentService 4.1）形成对照。
- 无 Meter、无事件、无 `MDC` 直读。

## 3. 循环依赖评估

**结论：不可能成环，全库最简单的一类依赖图。**

```
AgentSessionController ─→ AgentSessionService ─→ AgentSessionStateMapper ─→ (MyBatis/JDBC)
                                              ─→ ObjectMapper / TenantContext / SqlLikeUtils / domain VOs
```

- 出边全部向下（mapper/util/security/domain），入边仅 controller——线性链，类级无环；
- 包级贡献：service → mapper、service → security、service → util，全为向下边，不参与本库已知的任何包级环（service→harness→workflow→service 三角环与本类无关）；
- Bean 级：controller→service→mapper 单向，构造器注入无循环风险；
- 无静态初始化顺序问题（无自管理线程、无 static 可变状态；`UPSERT_MAX_ATTEMPTS` 为常量）。

## 4. 越层调用与边界问题

### 4.1 标量列与 `session_payload` JSON 构成双重真相同步体系——中（本报告最重要的结构性发现）

分支/消息/轨迹只存在于 `session_payload LONGTEXT`，标量列是它的投影。当前所有写路径都收敛到 `upsert` 的全量覆写（`updateSessionStateIfUnchanged` 同时重写全部列 + payload），列与 payload 不会漂移。但体系内有**一条旁路写**：`updatePinned`/`updateArchived`（:140/:151，mapper 列级 UPDATE）绕过 payload 直接改列。`setPinned`/`setArchived` 的实际流程是：列级 UPDATE（→ 其实只当存在性探测用，`updated <= 0` 即"session not found"）→ `get()` 重读 → `upsert()` 全量覆写收敛。这带来三个结构后果：

1. **存在性检查被实现为一次部分写**：探测"会话是否存在"付出了两次真实写 + 一次全量读 + 一次全量 CAS 写的代价（一次 pin 操作 = 3 次写路径 SQL）；`findByTenantAndSessionId` 一个 SELECT 即可完成同样判定。
2. **旁路写是列/payload 漂移的既有缺口**：今天没有第三个调用方用 `updatePinned`，一旦未来有人直接调 mapper（或新代码复制这个"先列更新再 upsert"模式）而漏掉收敛步骤，索引列与 JSON 内容即分叉——列表页（读列）与会话详情（读 payload）将各自为政。mapper 是包内公开接口，这层约束只存在于本类的用法惯例里。
3. **CAS 保护的是行版本而非 payload 语义**：乐观锁判定冲突后，重试环用**最初读到的 `state`** 重新覆写（:93 `applyUpsertFields(existing, state, serialized)`），并发写者的 payload 变更仍被本次覆写吞掉——锁把"静默丢失"升级成了"检测到冲突后合法地丢失"。竞态细节归 `audit-AgentSessionService.java.md`，此处只记录架构含义：**payload 级合并语义（尤其 branches 数组）在存储层没有保护，全靠调用时序运气**。

**修复方向**（均不改对外签名）：`setPinned`/`setArchived` 改为"SELECT 存在性 → 读改写合并到 upsert"单路径；中期把 pinned/archived 这类单标志从全量 upsert 中分离为 mapper 级专用 CAS UPDATE（`AND lock_version = expected` 同样自增版本），消除"标志写=全量 payload 覆写"的放大效应。

### 4.2 `attachWorkflowSnapshot` 死 API + 接口面谎言——低

全库（main+test）零调用，javadoc（:421-424）声称"links the conversational message to its agent execution trace"——与 `audit-MemoryService.java.md` 记录的 `saveTaskMemory` 死 API 同型，且语义漂移方向相同：方法存在暗示"会话⇄workflow 执行轨迹"的集成已经接线，实际从未接通。连带效应：`AgentSessionMessageVO` 的 `taskId/traceId/memorySnapshot/workflowState` 四个字段的**唯一写入者**就是这段死代码——生产数据里它们恒为 null，任何基于这些字段的查询/展示都是空头支票。它同时是本类唯一带 agent/workflow 域语义的公开方法（接口面的越域暗示），但实际上本类与 agent.* 无任何代码依赖。

**修复方向**（不删方法、不改签名）：若集成在计划中，接线点应在 `AgentWorkflowEngine` 任务完成事件处调用本方法并配套集成测试；若已放弃，加 `@Deprecated` + javadoc 注明现状（注解不属签名变更），并同步在 VO 字段上加注释声明"当前无写入方"。

### 4.3 LIKE 转义是跨层口头契约——低

转义发生在 service（:49，`SqlLikeUtils.escapeForLike`），消费发生在 mapper 的 LIKE 模板里——mapper 方法签名上 `keyword` 参数名并未说明"须已转义"。SQL 正确性依赖"调用方永远记得先转义"这一纪律：本类记得了，未来第二个调用 `countByTenant`/`findByTenant` 的 service 未必。契约应有类型级或命名级强制（如参数改名 `escapedKeyword`，或把转义下沉进 mapper 的 `<bind>`）。属防回归记录项，当前实现语义正确（MySQL 默认反斜杠转义符下 `\%`/`\_`/`\\` 行为正确，`LOWER` 不影响转义字符）。

### 4.4 读路径不对称：list 排除归档，get 不排除——低（记录性）

`countByTenant`/`findByTenant` 按 `includeArchived` 过滤 `archived = 0`，而 `findByTenantAndSessionId`（`get`/`upsert` 回读）无归档过滤——归档会话在列表中隐身但可直接读写、可再 pin。与 4.1 的"列是投影"一致（归档态在列上，payload 里也有一份 `archived`，两处都改）。是否属缺陷取决于产品语义，归 `audit-AgentSessionService.java.md` 域，此处仅记录读写契约不对称。

### 4.5 无越层调用

本类不触及 controller/传输层状态、不直接拼 SQL、不依赖 agent.*/workflow 层——无越层调用。service → mapper 一层到位，是 service 包内依赖最规矩的类之一。

## 5. 同层平行实现与隐性耦合

### 5.1 默认值规则在类内四处平行编码——低~中

"新会话 / default workspace / balanced profile" 这组默认值规则出现在四个独立位置：写侧 `buildInsertRecord`（:111-113）、`normalizeSessionPayload`（:267-273）、更新路径 `applyUpsertFields`（:127-129），以及读侧 `toSessionState`（:252-254，列 NULL 时回落 payload 值）。新增一个需要默认值的字段时必须同步四处，漏一处即出现"插入有默认、更新无默认"之类的分叉——与 arch-WorkflowReactAgentService 5.3 的平行实现同型，只是规模缩到类内。**修复方向**：抽私有 `applyDefaults(AgentSessionStateVO)` 单点（类内重构，签名不变）。

### 5.2 `upsert` 的返回值经 `get()` 三跳重读——低（成本结构）

每次 upsert 成功路径都是 `findByTenantAndSessionId`（锁探测）→ 写 → `get(tenant, sessionId)` 再查一次（:87/:97）→（若由 setPinned 进入还要再加一轮）。功能正确但"以读养写"的重读是给前端回显用的，属可接受的用例层设计；仅提示 `get()` 内部又走一次 `toSessionState` 的 JSON 反序列化，payload 大时会话分支合并/快照类操作的 CPU 成本随 payload 尺寸线性放大（branches 全量序列化/反序列化），这是 LONGTEXT 文档存储方案的固有代价，记录性。

## 6. 结论与建议优先级

**总评**：依赖图线性干净（controller→service→mapper 全向下、租户显式传参是全库样板、mapper SQL 租户过滤与 CAS 声明经 DDL 印证属实）。结构性问题不在依赖方向，而在**数据架构**：标量列是 LONGTEXT payload 的投影，双重真相靠全量 upsert 维持一致，已有一条旁路写和一条死 API（连同 VO 四个恒空字段）构成未来漂移的缺口。

| 优先级 | 事项 | 章节 | 改动范围 |
| --- | --- | --- | --- |
| 1 | `setPinned`/`setArchived` 去旁路写：存在性用 SELECT，标志更新并入 upsert 单路径（或专用 CAS UPDATE） | 4.1 | 仅本类 + mapper 可选 |
| 2 | `attachWorkflowSnapshot` 处置：接线或 `@Deprecated` + VO 字段现状标注 | 4.2 | 本类 + VO 注释（不删不改签名） |
| 3 | 默认值规则收敛为单点 `applyDefaults` | 5.1 | 仅本类内部 |
| 4 | LIKE 转义契约命名级强化（`escapedKeyword` 或 mapper `<bind>` 下沉） | 4.3 | 本类或 mapper 二选一 |
| 5 | 读写归档不对称、upsert 重读成本——记录性 | 4.4/5.2 | 搁置 |

以上各项均不改变任何对外 API 签名；第 1 项改变 `setPinned`/`setArchived` 的内部实现与 SQL 次数，对外行为（返回值、异常语义）保持不变，建议配合并发测试验证（对照 `audit-AgentSessionService.java.md` 的竞态用例）。

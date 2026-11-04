# 架构分析报告：MemoryService 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/memory/MemoryService.java`（166 行）
- **核查范围**：memory 包全部 6 个文件（MemoryService / MemoryItemMapper / MemoryEventMapper / MemoryItemRecord / MemoryEventRecord / SlotExtractor）逐个通读；2 个注入协作者 + 1 个死注入（`ObjectMapper`）+ 1 个静态出边（`TenantContext`）下钻；全部 2 个 main 调用方（`MemoryController` / `RagAnswerService`，grep 全库证实）逐调用点核对；`V12__memory_system_tables.sql` 建表契约比对；平行记忆通道 `config.MysqlChatMemory` 全文比对；测试侧 `MemoryServiceTenantIsolationTest` / `SlotExtractorTest` 引用面核对
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`arch-RagAnswerService.java.md`（其 2.3 双记忆写入路径、4.3 身份键 ThreadLocal 假设/L-4、5.5 记忆压缩下沉等发现，本报告从 memory 侧承接并交叉引用）、`arch-AuthController.java.md`（AuthIdentity 身份链路上游）。memory 包当前无同对象 audit 报告。

## 1. 模块定位

memory 包全库共 6 个文件，本类是包内**唯一的服务类**，对 `memory_item` / `memory_event` 两张表（`V12__memory_system_tables.sql`）做 CRUD 门面，职责分四块：

1. **Save**（:27-73）：`saveShortMemory`/`saveLongMemory`/`saveTaskMemory`/`saveFactMemory` 四个语义入口收敛到私有 `save()`（:51-73），类型/置信度/过期时间在入口处硬编码；
2. **Query**（:77-120）：四类查询 + 聚合入口 `buildContext()`（:100-120）；
3. **Maintenance**（:124-138）：`@Scheduled` 每日 3 点清理过期项 + 手动删除；
4. **Event**（:142-160）：`emitEvent` 同步落 `memory_event`（吞异常，best-effort）+ 唯一读取口 `getEvents`。

**main 源码调用方共 2 个**（grep `MemoryService` 全库证实）：

| 调用方 | 所在包 | 调用什么 | 上下文 |
| --- | --- | --- | --- |
| `MemoryController` | controller | `queryLongMemory`(:40)、`queryFactMemory`(:42)、`deleteMemory`(:52)、`saveFactMemory`(:68) | HTTP `/memory` 管理接口（spec-C5），fact 人工写入闸门 |
| `RagAnswerService` | rag | `queryLongMemory`(:315/:346/:366)、`queryFactMemory`(:323)、`saveLongMemory`(:353)、`saveShortMemory`(:356/:375)、`queryShortMemory`(:358)、`deleteMemory`(:373) | 三层记忆读写接线（spec-C2/C3/C4），读走 `buildKnownInfo`、写走 `persistConversationMemory` |

测试侧 `MemoryServiceTenantIsolationTest` 直接 `new MemoryService(...)` 构造（:26），是唯一构造点；`SlotExtractorTest` 只测同包 `SlotExtractor`，与本类无耦合。

**死 API 群**（main 全库无调用方，详见 5.4）：`buildContext`/`MemoryContextSnapshot`（:100-120/:162-165）、`queryTaskMemory`（:85）、`saveTaskMemory`（:38）、`getEvents`（:157）。

## 2. 依赖全景

### 2.1 出边（2 个注入协作者 + 1 个死注入 + 1 个静态依赖）

| 依赖 | 所在包 | 使用点 | 方向评价 |
| --- | --- | --- | --- |
| `MemoryItemMapper` | memory（同包） | insert(:70)、findByUserAndType(:78/:82)、findByTenantAndTaskId(:86)、findByTypeAndConfidence(:90)、findByTenantAndMemoryId(:133/:158)、deleteById(:135)、deleteExpired(:126) | 存储边界，MyBatis-Plus `BaseMapper`，二跳只依赖记录类 |
| `MemoryEventMapper` | memory（同包） | insert(:151)、findByMemoryId(:159) | 同上 |
| `ObjectMapper` | Jackson | **注入后全类零使用**（:4 import、:23 字段，正文无引用） | 死注入。槽位 JSON 序列化职责在同包 `SlotExtractor.toJson/parse`（SlotExtractor.java:82/:91）——注入痕迹是「序列化职责曾属本类、后外迁 SlotExtractor」的化石 |
| `TenantContext`（静态） | security | `normalize()` 7 处：:59/:78/:82/:86/:91/:133/:158 | 每个 public 入口统一归一租户；纯静态工具，无 Spring 装配 |

二跳结构：两个 Mapper 只 import MyBatis/MyBatis-Plus 与同包 Record；`MemoryItemRecord`/`MemoryEventRecord` 只 import MyBatis-Plus 注解 + Lombok。memory 包对 `com.enterprise.iqk.*` 的全部出边**只有 MemoryService → security.TenantContext 一条**（grep 包内 6 文件 import 证实）。

### 2.2 入边

- main 2 个直接调用方（第 1 节表格）：`controller → memory`、`rag → memory`，均单向。
- 本类产出的对外类型 `MemoryContextSnapshot` 仅被测试消费（MemoryServiceTenantIsolationTest:43）；`MemoryItemRecord` 被 2 个调用方消费。
- 无任何包反向依赖 memory 的出边——memory 不 import controller/rag/service/agent 任何业务包，是干净的下游叶子。

### 2.3 横切依赖

- **`@Scheduled(cron = "0 0 3 * * ?")`**（:124）：`cleanExpiredMemories` 的 `deleteExpired`（MemoryItemMapper.java:73-74）**不带 tenant 条件**，是跨租户全局守护删除。幂等无害，但意味着 memory_item 的租户隔离模型是「读时强制、清时全局」（见 4.4）。
- **`TenantContext` 的 MDC 本质**：security 包的 `currentTenantId()` 读 MDC（TenantContext.java:18-20）。本类只调 `normalize(显式参数)`，不读 MDC——租户来源完全交给调用方（对照 4.3）。
- **双记忆体系**（详见 5.1）：本类（memory_item 三层记忆）与 `config.MysqlChatMemory`（conversation 表、Spring AI `ChatMemory` SPI）是两条互不感知的持久记忆通道。

## 3. 循环依赖评估

**Bean 级与包级均无环，本类是全库依赖方向最干净的类别之一。**

- Bean 图：2 个 Mapper 是接口、无回指；`SlotExtractor` 同包但与本类**互不引用**（本类不注入它，它不注入本类）→ 无 Bean 环，连潜在环都没有。
- 包图：入边 `controller → memory`、`rag → memory` 两条全部单向；memory 唯一出边 `memory → security`（静态工具类，security 包不 import memory）也单向。三个节点不成环。
- 与 arch-RagAnswerService 记录的 agent⇄workflow 包级环**零参与**。

**两条值得预埋记录的潜在环风险**（当前均不成立）：

1. 若把 5.2 的「读最新 long → parse 槽位 → merge → 写回」编排从 rag 下沉进 memory 包，`SlotExtractor` 将被 memory 服务引用——仍无环，但 memory 包将从「纯 CRUD」变为「含领域语义」，对 security（AuthIdentity 解析若一并下沉）的依赖面会扩大，需保持只依赖静态工具。
2. `getEvents` 若复活并被 rag/agent 消费，事件读取会拉出 `memory → 消费方` 的语义回拉；当前读者死了，环不存在。

## 4. 越层评估

### 4.1 记忆领域策略不在 memory 包：服务是「无领域知识的 CRUD 门面」（核心越层项）

本类对 memory_item 的内容**零语义**：不解析 content、不知道「long 类型的 content 是槽位 JSON」这一隐式契约。结果是该契约散落在两个调用方：

| 隐式契约/策略 | 实现位置（复制处） |
| --- | --- |
| 「最新 long 记忆 = SlotExtractor 槽位 JSON」 | `MemoryController:40-41`、`RagAnswerService:315-318`、`RagAnswerService:346-350`、`RagAnswerService:365-369` —— 4 处独立 `parse(getContent())` |
| 抽槽 → merge → 写回的合并编排 | `RagAnswerService.persistConversationMemory`（:342-380）整段长在 RAG 门面里 |
| short 压缩策略（>6 条压摘要、删旧） | `RagAnswerService:358-376`，逐条 `deleteMemory` + 重写 |
| fact 写入闸门（confidence ≥ 0.8） | `MemoryController:59`——**写侧策略在 controller 层**，`saveFactMemory`(:44-49) 本身接受任意 confidence |
| 读 fact 的置信阈值 | `MemoryController:42` 与 `RagAnswerService:315/:323` 各写 `0.8`，死代码 `buildContext` 里另有 `0.7`(:103) |

评价：这不是调用方越权调用（方向都对），而是**领域逻辑留在了消费方、存储层只留了裸 CRUD**——与本库「外迁件已存在但编排不接」（arch-RagAnswerService 5.3）相反的病型：*该外迁的没外迁*。记忆生命周期策略（合并、压缩、过期、阈值）是 memory 域知识，目前唯一的落点是 rag 层私有方法，MemoryController 无法复用、只能各自再写一遍。

### 4.2 身份键解析样板两处复制，且降级键不一致

`MemoryController.resolveUserId`（:77-87）与 `RagAnswerService.currentUserId`（:299-306）是同一段 `AuthIdentity` principal 提取逻辑的两份手抄，但兜底键不同：controller 降级为 `"anonymous"`，rag 降级为 `"chat:{chatId}"`。同一匿名用户经两条链路产生两个身份键，**记忆互不可见**（manage 端看 anonymous 的记忆，问答端写 chat:{id} 的记忆）。这是 arch-RagAnswerService 4.3（L-4）在 memory 侧的另一面：不止 rag 一处，是「身份键知识无单点」。

### 4.3 租户信任模型：本类无条件信任调用方，但当前两条入边都干净

本类对每个入口 `normalize(传入 tenantId)`，不校验「调用者身份 == tenantId」——租户作用域完全由调用方决定。核对两条入边：`MemoryController` 统一取 `TenantContext.currentTenantId()`（MDC 权威，:38/:52/:69）；`RagAnswerService` 显式传参但来源是请求链路（arch-RagAnswerService 4.1 已核）。**memory 没有客户端可控租户的入口**（对照 rag 侧 `/ai/retrieval/search` 的 audit H-1），这一点是正面的；风险仅在未来第三个调用方出现时，「normalize ≠ 校验」的纵深缺口会重演。

### 4.4 `@Scheduled` 全局删除与「读时强制、清时全局」的隔离模型

`deleteExpired`（Mapper :73-74）`DELETE ... WHERE expires_at IS NOT NULL AND expires_at < NOW()` 无租户条件。设计上合理（过期数据本就无主），且只有 `short`/`task` 两类有 expiresAt（:30/:41），`long`/`fact` 永不过期。但注意：**这正是 5.2 槽位行累积无治理的兜底缺失**——long 行 expiresAt=null，过期清理永远扫不到它们。多实例部署下该任务并发执行是幂等的，无正确性风险。

## 5. 平行实现（本报告核心发现）

### 5.1 双持久记忆通道并存，故障语义与租户口径各自为政

| 维度 | 本类（memory_item 三层记忆） | `MysqlChatMemory`（conversation 表） |
| --- | --- | --- |
| 定位 | 用户画像/事实/要点（spec-C2/C3/C4） | Spring AI `ChatMemory` SPI 的对话历史 |
| 租户获取 | 显式参数 + `normalize` 兜底 "public" | 直接 `MDC` 取 `currentTenantId()`（MysqlChatMemory:44），无显式参数通道 |
| 调用线程要求 | 任意（参数传递） | 依赖 MDC 在位（ThreadLocal，同 arch-RagAnswerService 4.3 的线程模型风险） |
| 写入路径 | rag 门面 `persistConversationMemory` 手工编排 | `MessageChatMemoryAdvisor` 框架自动挂账 |
| 失败语义 | 全吞（persist 侧 catch RuntimeException，RagAnswerService:377；emitEvent catch Exception :152） | 抛错上抛 |

同一请求两条独立记忆写入路径互不感知——arch-RagAnswerService 2.3 已从 rag 视角记录，本报告从 memory 视角确认：**两边连「租户从哪来」的基本口径都不一致**，是平行的两套会话记忆哲学，无成文的边界文档。

### 5.2 「最新 long = 槽位 JSON」契约 + insert-only 累积：单行覆盖语义用追加实现

`saveLongMemory` 每轮 merge 后**永远 insert 新行**（:33-36 → :70），没有 update-in-place；消费方靠 `ORDER BY created_at DESC LIMIT 1`（Mapper :20-21）取最新来模拟「单例覆盖」。后果：

1. 旧槽位 JSON 行永久累积：`expiresAt=null`（:35），`deleteExpired` 扫不到，`deleteMemory` 只在用户手动/short 压缩时触发——long 类型无任何清理路径；
2. 「LIMIT 1 = 当前槽位」是查询侧约定，本类无知识；若任何调用方用 `limit > 1` 读 long（controller/rag 目前都是 1），语义立刻含混；
3. 并发写无保护：read-merge-write 编排（RagAnswerService:346-353）无事务无锁，两轮并发问答会丢一侧的槽位合并（后写覆盖）。

修复方向（不改签名）：编排下沉 memory 包为单点组件（如 `MemorySlotStore`），内部维持「读最新→merge→写新行→清理旧行」，把 4.1 的 4 处复制与累积问题一并收敛。

### 5.3 同包「服务」与「领域逻辑」割裂：SlotExtractor 与 MemoryService 零协作

`SlotExtractor` 与本类同包（memory），但两者互不引用：本类注入的 `ObjectMapper` 是死的（2.1），序列化实际发生在 SlotExtractor；槽位与记忆的连接点（「long.content 是 Slots JSON」）不在包内任何一处，而在 2 个外部调用方的 4 个复制点（4.1）。memory 包名义上有服务+组件两个成员，实际是「一个 CRUD 门面 + 一个被外部直接消费的工具类」的松散拼盘——包边界没有兑现聚合职责。

### 5.4 死 API 群：4 个 public 方法 + 1 个 Mapper 方法 + 1 个注入字段无生产调用方

| 死项 | 位置 | 说明 |
| --- | --- | --- |
| `buildContext` + `MemoryContextSnapshot` | :100-120/:162-165 | 唯一消费者是测试（TenantIsolationTest:43）。且**半成品痕迹**：facts 查询了（:103）并放进快照 record，却**没拼进 `contextText`**（:105-118 只拼 long/short）——快照文本与结构化字段不一致 |
| `queryTaskMemory` / `saveTaskMemory` | :38-42/:85-87 | `task` 类型全链路**无写入方也无读取方**（source `task:` 前缀只在这对方法里出现）——幽灵类型；配套 `findByTenantAndTaskId`（Mapper :40-47）同样死 |
| `getEvents` | :157-160 | memory_event 的**唯一生产读取口**死了 → 事件表生产环境只写不读（`emitEvent` :142-155 持续落库），是无人消费的审计流 |
| `findByUser` | MemoryItemMapper.java:36-38 | 无调用方 |
| `ObjectMapper` 注入 | :23 | 全类零使用（2.1） |

评价：这批死 API 共同指向一个未落地的设计——`buildContext`（聚合读）与 `saveTaskMemory`/`getEvents`（任务记忆+事件追溯）像是为 agent/工作流消费预埋的接口，实际消费方从未接上。按本库惯例（spec 注释驱动），建议要么接线要么显式标注 `@Deprecated` 成文，避免下一个调用方误以为聚合读入口是活路径。

### 5.5 事件模型最小化带来的记录空洞（轻）

`emitEvent` 只记 CREATE（:71）与 DELETE（:136）两种 action，reason 是自由文本；类内没有 UPDATE 路径所以无缺口，但 5.2 若引入 merge 语义，事件流需要第三种 action——当前事件 schema（`memory_event.action VARCHAR(32)`）已预留，仅提示演进时要同步。

## 6. 修复优先级建议（均不改对外 API 签名）

1. **P1** 「读最新 long → parse → merge → 写回」编排下沉为 memory 包单点（新增 `MemorySlotStore` 类组件或本类新方法），收敛 MemoryController/RagAnswerService 4 处 `parse(getContent())` 复制（4.1/5.2/5.3 三案同修）；下沉时顺带补「写新行后清理旧 long 行」的累积治理。
2. **P1** 身份键解析样板收敛为单点（security 包工具或 memory 包组件），统一 anonymous/chat:{id} 两条降级键的口径（4.2，与 audit L-4 同根）。
3. **P2** fact 阈值 0.8 双处字面量 + `saveFactMemory` 无闸门：阈值下沉为本类常量或入口校验，controller 闸门保留为 UX 层（4.1 末行）。
4. **P2** 死 API 群处置成文：`buildContext`/`saveTaskMemory`/`queryTaskMemory`/`getEvents`/`findByUser`/死 `ObjectMapper` 注入，标注 `@Deprecated` 或补设计说明（5.4）；`buildContext` 的 facts-未拼入文本不一致若决定保留需先修。
5. **P2 记录项**：双记忆通道（本类 vs MysqlChatMemory）的边界与租户口径差异成文（5.1）；并发 read-merge-write 的丢合并风险在下沉单点时加乐观锁或按 user 串行（5.2-3）。

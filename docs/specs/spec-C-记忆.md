# 规格 C · 三层记忆接线

> 给执行者读。前置：A、B 都已通过验收。
> 目标：把已经写好但没人调用的 `MemoryService` 接进问答链路，并让用户看得见。

---

## C0 · 硬约束

- `memory/MemoryService.java` 已有完整能力，**不要重写它**，只在必要时小幅扩展
- 不改 `MemoryItemRecord` / `MemoryEventRecord` 的字段
- 不新增依赖、不改 `pom.xml`
- 不改已有测试
- **不允许自动把用户反馈写进长期记忆**（防污染，见 C5）

---

## C1 · 现状（已核实）

`memory/MemoryService.java`（166 行）已有：

```java
saveShortMemory / saveLongMemory / saveTaskMemory / saveFactMemory
queryShortMemory / queryLongMemory / queryTaskMemory / queryFactMemory
buildContext(tenantId, userId) → MemoryContextSnapshot(contextText, ...)
cleanExpiredMemories() / deleteMemory() / getEvents()
```

`MemoryItemRecord` 字段：`memoryId / tenantId / userId / type(short|long|task|fact) / content / source / confidence / metadataJson / expiresAt`

**但 `grep "memoryService\."` 除自己外无调用——它是死代码。**

---

## C2 · 短期记忆（会话内）

现状：`ChatController` 用 Spring AI 的 ChatMemory（`CONVERSATION_ID` 参数）。

要做：
- 保留 ChatMemory，但在 `RagAnswerService.answer()` 拼 prompt 时，**只取最近 6 轮原文**
- 超过 6 轮的，压成一句摘要写进 `short` 记忆（`saveShortMemory`），摘要规则：
  `"用户已确认：{槽位}；此前问过：{前 3 个问题标题}"`
- 摘要**不用 LLM**，用模板拼装（可测、零成本）

---

## C3 · 会话记忆（跨会话，结构化槽位）

### 核心设计：存槽位，不存原文

```
❌ {"content": "学生说他是计算机学院2024级的，问过处分的事"}
✅ {"content": "{\"college\":\"计算机\",\"grade\":2024,\"major\":null,\"topics\":[\"处分\",\"入党\"]}"}
```

### 槽位抽取：规则优先

新建 `memory/SlotExtractor.java`：

| 槽位 | 规则 | 例子 |
|---|---|---|
| `grade` | 正则 `(\d{4})\s*级` 或 `(\d{4})\s*年?入学` | 「2024级」→ 2024 |
| `college` | 词表匹配：`计算机 / 软件 / 电子 / 机械 / 材料 / 经管 / 外语 / 艺术 / 数理 / 马克思` + 「学院」 | 「计算机学院」→ 计算机 |
| `major` | 词表匹配：`软件工程 / 计算机科学与技术 / 网络工程 / 人工智能 / 数据科学` | |
| `topics` | 关键词命中：`处分 / 入党 / 缓考 / 转专业 / 奖学金 / 保研 / 宿舍 / 实习 / 毕业` | 命中的全部收集 |

**不许用 LLM 抽槽位**（慢、贵、不可测）。规则抽不到就留 null。

### 写入时机

每轮问答结束后：抽取槽位 → 与已有 `long` 记忆**合并**（新值覆盖旧值，`topics` 取并集）→ `saveLongMemory`

### 读取时机

`answer()` 开头调 `buildContext(tenantId, userId)`，把 `contextText` 拼进 system prompt 末尾，格式：

```
【已知信息】学院=计算机，年级=2024，此前关注：处分、入党
```

**空槽位不要拼**（避免污染 prompt）。

---

## C4 · 长期记忆（fact）

第一期**只做接口和管理端，不做自动写入**：

- `queryFactMemory(tenantId, minConfidence=0.8, limit=5)` 在 `answer()` 里查询，命中则拼进 prompt 作为「常见问题结论」
- 写入只能通过新接口 `POST /api/memory/fact`（带 `confidence` 参数，人工调用）
- **不从对话自动写入**

理由：一条错答案写进 fact 会污染所有后续回答。这是 2026 面试考点「记忆污染防控」。

---

## C5 · 接口与前端对接

新建 `controller/MemoryController.java`：

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/memory?userId={id}` | 返回当前用户槽位 + 最近 5 条 fact |
| DELETE | `/api/memory/{memoryId}` | 删除一条记忆（前端「清除」按钮用） |
| POST | `/api/memory/fact` | 人工写入 fact（需 `confidence` ≥ 0.8） |

前端（规格 A 的 A6 记忆条）改为调 `GET /api/memory`，有数据才显示。

**注意**：`tenantId` 从现有 `TenantContext` 取，`userId` 从 JWT 取，不要新造一套。

---

## C6 · 验收

### 机器可判

| # | 命令 | 期望 |
|---|---|---|
| 1 | `mvn -q compile` | 退出码 0 |
| 2 | `mvn -q test` | BUILD SUCCESS，Failures 0，skipped 0 |
| 3 | `grep -rn "memoryService\." src/main/java --include="*.java" \| grep -v memory/` | ≥ 1 行（说明接线了） |
| 4 | `mvn -q test -Dtest=SlotExtractorTest` | BUILD SUCCESS |
| 5 | `grep -c "@GetMapping\|@DeleteMapping\|@PostMapping" .../MemoryController.java` | 3 |
| 6 | `cd frontend && npm run build` | 退出码 0 |
| 7 | `git diff --stat pom.xml` | 空 |

### `SlotExtractorTest` 必须覆盖

```java
// 1. "我是计算机学院2024级的" → college=计算机, grade=2024
// 2. "我2023年入学的，软件工程专业" → grade=2023, major=软件工程
// 3. "旷课怎么处分" → topics 含 "处分"，college/grade 为 null
// 4. "我大二的" → 全部 null（"大二"不抽，避免猜错年级）
// 5. 空字符串 → 不抛异常，全部 null
```

### 人工抽查（你早上跑）

```bash
# 连续问两轮，看第二轮是否复用槽位
curl -s localhost:8080/api/react/chat -d '{"message":"我是计算机学院2024级的，旷课怎么处分"}' ...
curl -s localhost:8080/api/react/chat -d '{"message":"那还能入党吗"}' ...
# 第二个请求的 prompt 里应该带【已知信息】
```

---

## C7 · 禁止

- ❌ 用 LLM 抽槽位
- ❌ 从对话自动写 `fact`（防污染）
- ❌ 重写 `MemoryService`
- ❌ 改 `MemoryItemRecord` 字段
- ❌ 猜年级（「大二」→ 2023 级是猜的，不许）
- ❌ 前端写死假记忆数据

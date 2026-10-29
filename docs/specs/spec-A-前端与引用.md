# 规格 A · 前端校园化 + 条款级引用

> 给执行者读。总纲任务书里只写路径，细节看这里。
> 目标：把「知识运营团队工作台」改成「校园规章问答助手」，并让引用可溯源到页码与原文。

---

## A0 · 硬约束（违反算失败）

- 前端只有一个文件：`frontend/src/App.vue`（3855 行，Vue 3 + Element Plus + TypeScript）
- **不许拆文件、不许改构建配置、不许升级依赖、不许改 `vite.config.ts`**
- **不许改整体布局结构**（`app-shell` / `sidebar` / 主区三段），只改内容层与新增区块
- 不许删任何现有功能（会话列表、评测面板、工作区筛选）
- 改动后 `npm run build` 必须通过（`vue-tsc --noEmit` 也在里面）

---

## A1 · 后端：新增 `citationCards` 字段（不改旧字段）

### 为什么不能直接改 `citations`

`citations` 是 `string[]`，被 `EvaluationScorer.hitRate()` 用于关键词匹配。改成对象数组会**破坏评测模块**。

**做法：新增字段，旧字段原样保留。**

### 要改的地方

**1. `rag/RagAnswerService.java` 的 `citationText()`**

现在返回：`"source=njit_student_handbook_2023.pdf, chunk=37"`（第 191-195 行）
没有页码、没有条款。

改为保留原格式不变，**另建一个方法** `citationCard(Document d, int index)` 返回结构化对象：

```java
public record CitationCard(
    int index,        // 1-based，与 [1][2] 对应
    String file,      // 制度名：file_name 去掉 .pdf 后缀
    Integer page,     // metadata 里的 page_number，没有就 null
    int chunkIndex,   // metadata 里的 chunk_index
    String snippet    // 前 180 字，空白归一化
) {}
```

`page_number` 来自 `PagePdfDocumentReader` 写入的 metadata，`splitDocuments` 复制 metadata 时会保留。**如果实测取不到，就返回 null，不要自己编页码。**

**2. `RagAnswerService.RagResult`** 增加 `List<CitationCard> citationCards` 字段，`answer()` 里一并返回。

**3. `agent/harness/BuiltinToolRuntime.executeRagSearch()`** 的 payload 增加 `citationCards`。

**4. `controller/ReactController` / `ChatController` 的响应** 透传该字段（看实际哪个 controller 返回给前端，只改那一个）。

**不要改**：`citations` 字段、`evidence` 字段、`EvaluationScorer`。

---

## A2 · 前端：品牌与文案

| 位置 | 现在 | 改成 |
|---|---|---|
| `App.vue:5` `.eyebrow` | KnowledgeOps Agent | 校园规章问答 |
| `App.vue:7` `.brand-sub` | 面向知识运营团队的 ReAct 工作台 | 南京工程学院 · 学生手册 2023 版 |
| 导航第 2 项 | RAG Evaluation | 检索评测 |
| 评测面板表头 | Dataset / Baseline / Current / Citation / Latency 等英文 | 数据集 / 基线 / 当前 / 引用覆盖 / 延迟 |

**要求**：主界面（chat 视图）不出现英文标签。评测面板的技术名词（Recall@3 / NDCG@6）可以保留英文。

---

## A3 · 前端：空状态引导

`activeView === 'chat'` 且当前会话无消息时，主区显示引导区：

```
校园规章问答助手
问一句，给你条款依据

[ 旷课 50 学时会被怎么处分 ]  [ 缓考怎么办 ]
[ 转专业需要什么条件 ]        [ 被记过了还能入党吗 ]
```

- 4 个 chip，点击后直接填入输入框并发送
- 用 `el-button` 或现有卡片样式，不要引入新组件库
- 引导区只在空会话显示，有消息就隐藏

---

## A4 · 前端：条款级引用卡片（核心）

### 现在长什么样

`App.vue:334-346`：`citation-panel` → `citation-list` → `citation-chip`，内容是纯字符串 `[1] source=xxx.pdf, chunk=37`，点击调 `openCitation(citation)`。

### 改成卡片

```
┌─ [1] 违纪处分办法 · P20 ────────────────┐
│ 一学期旷课累计达到五十学时的，给予留校  │
│ 察看处分……                              │
│                              [展开原文] │
└─────────────────────────────────────────┘
```

- 标题行：序号 + 制度名 + 页码（有才显示，没有就只显示制度名）
- 正文：`snippet`，最多 3 行，超出省略
- 「展开原文」：点击后展开完整 chunk 文本（若 `citationCards` 里只有 snippet，则展开时调现有 `openCitation`）
- **优先用 `citationCards` 渲染**；字段不存在时**回退到现有 `citations` 字符串**（保证不崩）

### 高亮

若 `snippet` 里能匹配到用户问题中的关键词（≥2 字的中文词），用 `<mark>` 高亮。匹配不到就不高亮，不要报错。

---

## A5 · 前端：检索轨迹面板（面试演示重点）

助手回答下方一行可点击的小字：

```
🔍 召回 50 · 粗排 20 · 精排 6 · 重排 312ms ▾
```

点开显示：

```
阶段        输入    输出    耗时
向量召回    —       50      41ms
关键词召回  —       37      28ms
融合去重    —       62      —
BM25 粗排   62      20      9ms
精排        20      6       312ms
```

**数据来源**：后端目前没有这个字段。第一期做法：
- 若响应里有 `retrievalTrace` 字段就渲染真实数据
- **没有就渲染占位并标注「轨迹未接入」**——不要伪造数字

> 这一块允许第一期只做 UI 壳。**伪造耗时数字 = 作弊**，明确禁止。

---

## A6 · 前端：记忆提示条

会话顶部一条浅色小字：

```
我记得你是 计算机学院 · 2024 级  [清除]
```

- 数据来源：`memory` 接口（第一期若未接线，就**不显示这条**，不要写死假数据）
- 有「清除」按钮，调 `DELETE /api/memory/{id}`（接口不存在就隐藏按钮）

---

## A7 · 前端：兜底提示

当回答的 `citationCards` 和 `citations` 都为空时，回答下方显示：

```
⚠ 未在学生手册中找到直接依据，建议咨询学生处（025-xxxxxxx）或辅导员
```

黄色警示条，不要用红色（红色留给错误）。

---

## A8 · 验收

### 机器可判（必须全过）

| # | 命令 | 期望 |
|---|---|---|
| 1 | `cd frontend && npm run build` | 退出码 0 |
| 2 | `grep -c "校园规章问答" frontend/src/App.vue` | ≥ 1 |
| 3 | `grep -c "旷课 50 学时会被怎么处分" frontend/src/App.vue` | ≥ 1 |
| 4 | `grep -c "citationCards" frontend/src/App.vue` | ≥ 2 |
| 5 | `grep -c "检索评测" frontend/src/App.vue` | ≥ 1 |
| 6 | `grep -c "KnowledgeOps Agent" frontend/src/App.vue` | **0** |
| 7 | `mvn -q compile` | 退出码 0 |
| 8 | `mvn -q test` | BUILD SUCCESS，Failures 0 |
| 9 | `git diff --stat frontend/src/App.vue` | 行数变化 < 800（大改说明重写了，不合格） |

### 人工抽查（你早上看，不能机器判）

- 截图 1：空会话引导区（4 个 chip）
- 截图 2：一条带引用卡片的回答
- 截图 3：展开的检索轨迹面板

**截图放 `docs/screenshots/` 并提交。视觉规范的机器判据见 A9。**

> 前端是体验类目标，**只能半托**：机器判「build 过 + 文案对 + 结构在」，好看与否要人看。

---

## A9 · 视觉规范（「好看」的定义，不许自由发挥）

前端是门面。**不加这一节，会做出功能对但难看的界面。**

### 设计令牌（写成 CSS 变量放 `:root`）

```css
:root {
  --color-primary: #1e3a8a;      /* 深学院蓝 */
  --color-accent: #2563eb;       /* 强调 */
  --color-bg: #f8fafc;           /* 页面底 */
  --color-surface: #ffffff;      /* 卡片 */
  --color-text: #0f172a;         /* 主文字 */
  --color-text-sub: #64748b;     /* 次文字 */
  --color-border: #e2e8f0;
  --color-quote-bg: #f1f5f9;     /* 引用卡片底 */
  --radius-card: 10px;
  --radius-chip: 8px;
  --radius-input: 12px;
  --shadow-card: 0 1px 3px rgba(15,23,42,.06);
  --shadow-hover: 0 4px 12px rgba(15,23,42,.10);
}
```

**必须用变量，不许在样式里写死颜色。** 已有样式逐步替换，不要求一次全换。

### 排版

| 项 | 规定 |
|---|---|
| 中文字体 | `-apple-system, "PingFang SC", "Microsoft YaHei", sans-serif` |
| 正文 | 14px / 行高 1.7 |
| 卡片标题 | 13px / 600 字重 |
| 引用正文 | 13px / 行高 1.6 |
| 间距 | **只用 8 的倍数**（8/16/24/32），出现 13px 这类间距视为不合格 |

### 组件规格

**引用卡片**：左侧 3px 竖线用 `--color-primary`，底色 `--color-quote-bg`，圆角 `--radius-chip`，内边距 12px 16px。标题行：序号（等宽数字）+ 制度名 + 页码，右端一个 `▾` 展开图标。

**空状态 chip**：白底、细边框、圆角 `--radius-chip`、内边距 16px 20px、hover 时 `transform: translateY(-1px)` + `--shadow-hover`，过渡 `150ms ease`。

**消息气泡**：用户右侧、`--color-primary` 底 + 白字；助手左侧、白底 + 1px `--color-border` 边框。两者最大宽度 78%。

**检索轨迹行**：13px、`--color-text-sub`、等宽字体显示数字（`font-variant-numeric: tabular-nums`）。

**兜底提示条**：底色 `#fffbeb`、边框 `#fde68a`、文字 `#92400e`、左侧 3px 竖线。**不要用红色**（红色留给错误）。

### 明确禁止

- ❌ 渐变背景、玻璃拟态（毛玻璃）、发光阴影
- ❌ 满屏 emoji（只用 🔍 📎 ⚠ 💡 四个功能性图标）
- ❌ 超过两种强调色
- ❌ 纯黑 `#000` 或纯灰 `#ccc`
- ❌ 引入新的 UI 库或图标库（用 Element Plus 自带的）

### 验收

| 类型 | 判据 |
|---|---|
| 机器判 | `grep -c "1e3a8a" frontend/src/App.vue` ≥ 1；`grep -c "PingFang SC" frontend/src/App.vue` ≥ 1；`grep -c "tabular-nums" frontend/src/App.vue` ≥ 1 |
| 人判（你早上看） | 3 张截图，看三件事：**层次清不清楚 / 留白够不够 / 引用卡片一眼能不能看出是引用** |

---

## A10 · 禁止

- ❌ 伪造检索耗时 / 记忆内容 / 页码
- ❌ 重写整个 `App.vue`（diff > 800 行即视为重写）
- ❌ 引入新的 UI 库、图标库、状态管理
- ❌ 改 `citations` / `evidence` 字段类型
- ❌ 改 `EvaluationScorer`
- ❌ 为了让 build 过而注释掉类型检查（`// @ts-ignore` 也算）

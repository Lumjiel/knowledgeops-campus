# 架构分析报告：MessageList.vue 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；前端环境无 node_modules，不运行任何 install/build/lint，全部结论来自静态交叉审查；每条结论附源码位置证据）
- **分析对象**：`frontend/src/components/MessageList.vue`（1713 行 = template 377 + script ~470 + style ~865，全组件最大文件）
- **核查范围**：唯一调用方 `App.vue` 的绑定面逐 prop/逐 event 核对（:93-121 + :274-283 + :307-351）；类型契约 `types/chat.ts`（ChatMessage/ChatCitationCard/RetrievalTrace）与 `types/react.ts`（ReactTraceStep）全文；事件下游 `composables/useChatEngine.ts` 的 `parseCitation`/`openCitation`（:147-166）；跨栈协议生产端 `RagAnswerService.java:228`；「我是有章」全仓生产端核查（backend java/resources/seed/eval/docs 五处 grep，含 demo-data）；markdown/v-html/DOMPurify 全前端唯一站点核查；`composables/` 四件套与 `api/`/`utils/` 的零依赖核查；同库文件写入面排除性核查
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（props/emits/defineExpose 三通道均只记录不动）
- **关联报告**：`arch-HybridRetrievalService.java.md`（其 5.2 默认权重三处复制的发现，本报告补出前端第 4 份复制）、`arch-RagAnswerService.java.md`（其 5.5 `.pdf` 后缀单边剥离的发现，本报告补出前端无条件回拼的一侧）、`audit-EvaluationPanel.vue.md`（同层前端审查先例）

## 1. 模块定位

本组件是**聊天消息列表的纯展示层**：17 个 props 进、9 个事件出（+1 个 `v-model:editing-message-draft`）+ `defineExpose` 命令式 API（`scrollToBottom`/`resetHeights`，:797-800，App.vue:279/:283 经 ref 调用）——**三通道契约**。职责四块：

1. **消息渲染**（template :23-368）：角色气泡、pending/streaming/done 三态分支、条款引用卡片（spec-A4/A9）、来源引用 chips、证据片段、检索轨迹表（spec-A5）、Agent 执行轨迹时间线、无引用兜底条（spec-A7）；
2. **Markdown 渲染子系统**（script :419-519）：marked + highlight.js（10 语言，模块加载时注册 :408-417）+ DOMPurify（含 reverse-tabnabbing 全局 hook :478-486）+ base64 代码复制协议——**全前端唯一的 v-html 站点**；
3. **自研虚拟滚动**（:621-755）：估算行高 + ResizeObserver 实测 + 二分查找可视区 + 上下 spacer；
4. **测量与滚动生命周期**（:757-847）：滚动跟随、会话切换丢弃测量、window resize 监听。

**调用方唯一**：`App.vue`。props 注释（:521-539）明确记录了状态所有权设计——展开态/编辑态/反馈 map 由父组件持有，因为「切换到评测页会卸载本组件」（App.vue:95 `v-if="activeView === 'chat'"`，对照 EvaluationPanel 用 `v-show` 常驻），这是组件边界上少见的成文契约，属正面样板。

## 2. 依赖全景

### 2.1 出边

| 依赖 | 层级 | 使用点 | 方向评价 |
| --- | --- | --- | --- |
| `types/chat.ts` / `types/react.ts` | 同 src 类型契约 | `ChatMessage`/`ChatCitationCard`（:396）、`ReactTraceStep`（:395） | 叶子类型文件，注释自证它是 App↔MessageList 的共同契约点（chat.ts:1-3） |
| `marked` + `highlight.js` | npm | :381-417/:443-470 | hljs 按需 core+10 语言注册在**模块加载期**执行——任何导入本组件的测试/视图都付出注册成本；只被本组件消费，无冲突 |
| `dompurify` | npm | :478-486/:498-518 | 全局 hook 注册一次（注释 :472-477 已自证与重复渲染的兼容性）；安全配置面见 4.1 |
| `element-plus` | npm | `ElMessage`（:392，:612-619/:776-795 两处）、`el-input`/`el-button`（:306-322） | 叶子组件直调全局 toast 服务——展示层内部耦合 UI 框架全局副作用，可接受但记入边界 |
| `vue` 运行时 | 框架 | :394 | 标准三件套 |
| 浏览器 API | 平台 | `navigator.clipboard`（:614/:790）、`ResizeObserver`（:821-832）、`btoa/atob/TextEncoder/TextDecoder`（:428-441）、`window.resize`（:834/:838） | 均有守卫或 try/catch |

**关键零依赖**：不 import `api/`、`composables/`、`utils/`、`App.vue` 的任何东西——业务逻辑、请求编排、状态持有全部在父侧（useChatEngine 等 composables 经 App.vue 解构后以 props 下发，App.vue:338-351）。这是严格的 props-down/events-up 单向数据流，组件本身是全前端依赖方向最干净的展示件。

### 2.2 入边

- main 源码 1 个调用方（App.vue），消费面 = 17 props + 9 events + 1 v-model + 2 个 expose 方法。
- `types/chat.ts` 头注释把本组件点名为契约共同方（:2-3），属文档性引用非代码依赖。
- 无测试引用（前端无组件测试设施，见关联约束）。

### 2.3 样式横切依赖（隐式契约）

style 块注释（:851-854）声明：设计令牌 `--ui-*` / `--color-*` 由 **App.vue 的 `.app-shell`** 提供。这是 CSS 自定义属性的运行时继承——不是 import，编译期不可见，App.vue 改 shell 类名或删令牌会让本组件**静默失样式**。记入 4.7。

## 3. 循环依赖评估

**组件级与文件级均无环。**

- import 图：`App.vue → MessageList.vue → {types, npm}`，全部单向；本组件不回指 App、不 import 任何 composable/api。
- 事件流闭环（子 emit → App 转发 → useChatEngine → 新 props 下发）是 Vue 标准数据环，非依赖环；且 App.vue:338-351 证实所有 handler 都是 composable 解构的直通委托，App 侧无业务逻辑沉积。
- CSS 令牌继承方向（App shell → 本组件）与 import 方向一致，无样式环。

**两条潜在环风险**（当前不成立，预埋记录）：

1. 若未来把 Markdown 渲染子系统外迁为共享组件（4.1），而 EvaluationPanel 也要渲染 markdown，新组件必须继续落在 `components/` 或 `utils/` 且**不得**反 import MessageList——否则 `MessageList → MarkdownMessage → MessageList` 环。当前无此边。
2. `defineExpose` 的命令式通道（scrollToBottom/resetHeights）是子→父的反向调用面：若未来父组件把回调经 props 传回子组件（callback-in-props 模式），即形成双向纠缠。当前只有 2 个方法、语义清晰（滚动/测量重置），是可接受的反向面最小实例。

## 4. 越层评估

### 4.1 Markdown 渲染子系统内嵌：约 200 行安全关键面长在列表组件里（核心越层项）

`escapeHtml`/`toBase64`/`fromBase64`/renderer 定制/全局 hook/`renderMarkdown`（:419-519）是一个自洽的**渲染-安全子系统**：先转义再高亮（防注入）、DOMPurify 白名单属性 + FORBID 表（:498-518）、`data-code` base64 通道规避属性注入（:461-463）、reverse-tabnabbing hook（:478-486，注释含威胁模型）。安全姿态是全前端最好的，但它：

1. 与「消息列表」无必然绑定——任何要渲染 LLM markdown 的视图（评测报告、Agent 观察）都需要同一套安全管线，当前无法复用；
2. 安全关键逻辑（DOMPurify 配置、hook）随组件消亡而失效：切到评测页时组件卸载，**全局 hook 却留在 DOMPurify 上**（模块级注册，注释 :477 自证）——hook 生命周期 > 组件生命周期，是子系统未独立的有味信号；
3. 无测试锚点：安全配置变更（如 ALLOWED_ATTR 增删）没有单测可守。

评价：外迁为 `MarkdownMessage` 组件或 `utils/markdown.ts` 是边界归位（同时消除 3.1 的潜在环温床），不改本组件任何 props/emits 签名。

### 4.2 引用跳转协议的生成端长在叶子组件里：`source=X.pdf, chunk=N` 三点跨栈、知识无单点

`citationTextOf`（:567-569）把卡片字段拼回协议串 `` `source=${card.file}.pdf, chunk=${card.chunkIndex}` ``，该串随后被 `useChatEngine.parseCitation`（:151）正则解析、`openCitation`（:156-160）转成 `/ai/pdf/file` 查询参数，对端是后端 `PdfController`。协议串的另一生产端是后端 `RagAnswerService:228`（`formatCitationFooter`）。即：**协议知识散在三点**——后端拼装、前端组件拼装、前端 composable 解析，且无类型/常量单点。后果已经显形：本组件无条件回拼 `.pdf`（:568），而后端卡片侧的 file 是**已剥离后缀**的（arch-RagAnswerService 5.5：`.pdf` 剥离只存在于 citationCard 一侧）——若某文件非 .pdf 扩展名，`citationTextOf` 产出错误 source，跳转 404。同一组件内 `prettyFileName`（:584-592）负责剥后缀做展示、`citationTextOf` 负责拼后缀做协议——**同一字段两种处理在同一文件里并存**，是协议知识无单点的直接病灶。修复位：`citationTextOf` 下沉 useChatEngine（与 parseCitation 成对），本组件只 emit 卡片对象——需改 emit 载荷类型，属本组件+直接相关文件内动作。

### 4.3 「我是有章」子串判据：无生产端的跨栈契约（悬空契约）

无引用兜底条的门控（:124-133）含 `!entry.item.content.includes('我是有章')`——依赖回答正文包含人格问候语子串来抑制兜底。**全仓 grep 证实「我是有章」（乃至「有章」）在后端 java、resources、seed 数据、eval 脚本、demo-data 中零出现**：该字符串没有任何代码生产端，只可能来自 LLM 按提示词自由发挥的输出或 DB 中的种子数据。契约形态（跨栈字符串子串匹配）+ 契约无生产端 = 三重脆弱：换模型、改提示词、改人格名都会让兜底条误显/误隐，且无任何编译期或测试信号。修复方向：判据改基于结构化字段（`ChatMessage` 加显式 flag，如 `grounded`/`isGreeting`），由 useChatEngine 在收到回答时判定——判据知识归状态层，不归渲染层。

### 4.4 检索四路召回条：假数据 + 后端权重的第 4 份跨栈复制

Agent 轨迹面板的「检索四路召回」条（:280-296）是**写死的静态 HTML**：`style="width: 40%"` Vector / 25% Keyword / 20% Graph / 15% Web，不读 `traceSteps` 任何字段。两组问题：

1. **假数据冒充实况**：面板标题暗示这是本次执行的召回分布，实际与任何请求无关（对照同面板 :154-156 对占位轨迹的诚实标注「以下为占位行，非真实耗时」——同面板内两段占位 UI，一段声明、一段不声明）；
2. **跨栈常量复制链再+1**：40/25/20/15 正是后端 `HybridWeights.DEFAULT`（arch-HybridRetrievalService 5.2 已记录其在本体的三处复制：DEFAULT、死常量、javadoc）——前端这里是**第 4 份**。后端调权，前端静默过期。
3. 修复：数据驱动（traceSteps 扩展字段或删条）或标注「权重配比示意」。均不改签名。

### 4.5 `ChatMessage.state` 状态机的半边消费

类型契约定义五态 `pending|streaming|done|error|stopped`（chat.ts:23），本组件只特判前三态（:29/:36-37/:43/:51/:135）；`error`/`stopped` 落入默认分支按 done 渲染 markdown——错误态消息与正常消息视觉无差。状态机生产端在 useChatEngine、消费端在渲染层，**半边契约**：生产端新加状态（或改语义）渲染层静默失配。与 4.3 同根：契约该收口在类型层（穷举分支 + never 检查）而非模板条件散写。

### 4.6 命令式 API 与状态所有权：边界文档化的正面样板（无修复项）

props 注释逐条记录「由父组件持有并原地变更」「切换到评测页会卸载本组件」（:521-538），expose 仅 2 个方法且与父侧调用点一一对应（App.vue:279/:283），`resetHeights` 的语义（会话/分支切换后按估算行高重布局）在 :771 有注释锚定。**本节无缺口**，作为「三通道契约如何成文」的正面引用。

### 4.7 样式令牌的运行时继承（2.3 的架构面）

`--ui-*`/`--color-*` 令牌契约的提供方是 App.vue 的 `.app-shell`（:851-854 注释自证），运行时继承、编译期不可见。与 memory 中「styles.css 不在改动白名单」的现状叠加：令牌真实定义在 App.vue style 块还是全局 styles.css，本组件无法自证——组件对样式基座有**不可自我验证的假设**。记录项：在组件头注释补令牌清单，或后移令牌到独立 token 文件（涉及 App.vue，属直接相关文件内动作）。

## 5. 平行实现（本报告核心发现）

### 5.1 同文件两代样式并存，靠级联顺序覆盖（「原样迁移」的未了成本）

style 块内**同一选择器至少 6 组重复定义**，第一代（:856-1529，自 App.vue 原样迁移、硬编码色值）+ 第二代（:1590-1628 起的令牌化覆盖层），后者靠源码顺序在级联中获胜：

| 选择器 | 第一代 | 覆盖层 |
| --- | --- | --- |
| `.avatar` | :933（渐变蓝底） | :1590（`--ui-accent-soft`） |
| `.bubble` | :974 | :1604 |
| `.message-actions button` | :904 | :1630 |
| `.citation-card` | :1075（含 box-shadow: var(--shadow-card)） | :1652（box-shadow: none） |
| `.retrieval-trace-row`/`.retrieval-trace-toggle` | :1167/:1171 | :1665/:1671 |
| `.no-citation-fallback` | :1155（黄底 spec-A7） | :1686（`--ui-danger` 红系边） |

每组的被覆盖属性都是**死声明**——改第一代不生效，是维护陷阱；且 `.no-citation-fallback` 两代语义冲突（注释 :1154 声明 spec-A7「黄底，不用红」，覆盖层 :1687-1688 用的恰是 danger 红系——**spec 决议与生效样式相反**，需按 spec 复核哪代是真意图）。这不是缺陷爆炸点（级联行为确定），但「迁移原样保留 + 追加覆盖」的手术做了一半：核验后删第一代死属性、或给覆盖层加注释声明胜出关系，均在直接相关文件内。

### 5.2 Markdown 渲染唯一站点（排除性结论，正面）

全前端 grep 证实 v-html/DOMPurify/marked 仅本组件一处：EvaluationPanel（评测工作台）不渲染 markdown、ChatInput/Sidebar 等均纯文本。**不存在平行 markdown 管线**——4.1 的外迁建议因此无收敛风险（单点迁出即终态）。同法核查剪贴板：`copyMessage`（:612-619）与代码块复制（:776-795）是同组件内两段近似复制通道（try/clipboard/ElMessage 三连），属类内小平行，外迁时随 4.1 顺带收敛为 `copyToClipboard(text, okMsg)` 单点。

### 5.3 虚拟滚动自研件：唯一实现、无平行（排除性结论）

自研虚拟滚动（估算高 + ResizeObserver 实测 + 二分 :652-674 + spacer :21/:370）在 src 内无第二实现、无第三方虚拟滚动库引入。作为唯一实现记录两点边界事实：测量键是 `message.id`（:723-737，消息 id 稳定是隐含前提，依赖 useChatEngine 的 id 生成契约）；会话切换全量弃测（resetHeights :772-774）。**无收敛动作**。

### 5.4 时间/文件名展示格式：单点、无平行（排除性结论）

`formatTime`（:560-565）、`prettyFileName`（:584-592）、`questionKeywords`/`renderSnippet` 高亮（:576-610）均为组件内单点且带防注入注释。唯一跨栈呼应是 `.pdf` 回拼（已归 4.2）。**无其他站点**。

## 6. 修复优先级建议（均不改对外 API 签名；改动仅限本组件+直接相关文件）

1. **P1** `citationTextOf` 下沉 useChatEngine 与 `parseCitation` 成对单点（4.2），emit 载荷改传卡片对象；`.pdf` 后缀策略与 `prettyFileName` 统一（消除同字段双处理）。
2. **P1** 兜底判据去子串化（4.3）：「我是有章」判据改 `ChatMessage` 结构化字段，判定位移至 useChatEngine；悬空契约与后端 persona 决议（SystemConstants/提示词）对表后成文。
3. **P2** 检索召回条去假数据（4.4）：数据驱动或显式标注「示意」；后端权重复制链（现 4 份）收敛时前端此份一并清理。
4. **P2** Markdown 子系统外迁 `utils/markdown.ts` + 展示件（4.1，随 5.2 剪贴板收敛），DOMPurify hook 生命周期随迁移单点化。
5. **P2** 两代样式清理（5.1）：按 spec 复核 `.no-citation-fallback` 黄/红冲突后，删除被覆盖的死声明并给覆盖层注释锚定；样式令牌依赖清单补进组件头注释（4.7）。
6. **P2 记录项**：`state` 五态的穷举消费（4.5）——模板条件收口或类型层 never 检查，防 useChatEngine 扩态时静默失配。

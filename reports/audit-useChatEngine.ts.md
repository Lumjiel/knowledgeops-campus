# 审查报告：frontend/src/composables/useChatEngine.ts

- **日期**：2026-11-04
- **审查方式**：只读通读 + 静态交叉审查（frontend 无 node_modules，不执行 install/build/类型检查；结论均以下列协作者源码为证）
- **被审对象**：`useChatEngine.ts`（569 行）：SSE/JSON 问答状态机、编辑重发、重新生成、回答评分反馈、引用跳转
- **约束遵守**：未改任何源码；仅新增本报告文件；无 git commit/push；无删除文件；无 npm install / build
- **关联报告**：`reports/audit-client.ts.md`、`reports/audit-MessageList.vue.md`、`reports/audit-EvaluationPanel.vue.md`（本报告与其发现互有印证，冲突处以本次核实为准）

## 协作者清单（均已通读/核实）

| 协作者 | 用途 | 关键证据 |
| --- | --- | --- |
| `api/client.ts` | `reactChat` / `streamReactChat` / `submitAnswerFeedback` | client.ts:171-334, 489-506；`normalizeFetchFailure` 对 AbortError 原样重抛（errors.ts:57-64） |
| `utils/errors.ts` | `toUserMessage` / `errorStatus` | errors.ts:67-109 |
| `utils/session.ts` | `createMessage` / `deriveTitle` | session.ts:23-63 |
| `composables/useSessionStore.ts` | 会话镜像状态与 fork/loadSession | useSessionStore.ts:189-252, 344-371 |
| `composables/useAuth.ts` | `ensureCredential` / `guestMode` / `canUseRemoteSync` | useAuth.ts:33-92 |
| `composables/useGuestRestriction.ts` | 游客 403 引导 | useGuestRestriction.ts:37-47 |
| `App.vue` | 装配与全部对外消费点 | App.vue:330-353（装配）、97-127/169（消费） |
| `components/MessageList.vue` | 事件参数纲 | messageMetrics 基于 `props.messages.map((item, index))`，`entry.index` 是**全量数组下标**（MessageList.vue:628-639） |
| 后端 `PdfController` / `SecurityConfiguration` / `ApiKeyOrJwtAuthFilter` | 引用跳转端点与鉴权 | SecurityConfiguration.java:67；ApiKeyOrJwtAuthFilter.java:25,80-81；PdfController.java:73-92 |
| 后端 `RagAnswerService` | citation 字符串格式 | RagAnswerService.java:228 `"source=" + file + ", chunk=" + chunk` |

---

## 概述：先说做对的部分

1. **`ask` 的并发守卫与发送前规范化**（useChatEngine.ts:247-252）：`sending` 阻断并发提问，`sanitizeMessageStates` 在新一轮提问前把遗留的 pending/streaming 收敛为 done。
2. **401 自动重试严格限一次**（useChatEngine.ts:393-403）：`retriedAuth` 防止 `ensureCredential` 成功后的递归无限循环；重试前先 `splice` 掉失败占位消息并以 `appendUser=false` 复用已有用户消息，索引语义正确。
3. **Abort 分支独立处理且保留部分输出**（useChatEngine.ts:382-389）：停止后已生成的 token 不被丢弃，state 置 `stopped`；配合 client.ts 的 `normalizeFetchFailure` 对 AbortError 原样重抛（errors.ts:58-60），取消语义链路完整。
4. **事件下标纲一致**：`rateAnswer(index, …)` / `regenerateFrom(assistantIndex)` / `submitEditAndResend(messageIndex, …)` 消费的 `entry.index` 经核实来自 `messageMetrics = props.messages.map((item, index) => …)`（MessageList.vue:628-639），是全量数组下标，与本文件内 `messages.value[i]` 的纲一致（MessageList 侧曾报告的混纲比较 `entry.index === virtualMessages.length - 1` 是该文件自身的显示问题，不影响本文件）。
5. **游客 403 统一走引导卡片**（useChatEngine.ts:218-224, 406-411），与 `useGuestRestriction` 的模块级单例契约吻合；评分按钮在 MessageList 侧已有 `answerFeedbackLoading`/已评分 disabled 兜底（MessageList.vue:338-357）。
6. **`streamResetTimer` 在 `onBeforeUnmount` 清理**（useChatEngine.ts:531-536），拆分后未丢定时器。
7. **引用跳转 URL 的动态段全部 `encodeURIComponent`**（useChatEngine.ts:164-167），`window.open` 带 `noopener,noreferrer`。

---

## 高严重度

### H-1 发送期间会话镜像可被整体替换，`messages.value[assistantIndex]` 索引定位全面失效

**位置**：useChatEngine.ts:267（`const assistantIndex = messages.value.length - 1`）、304-305、320-333、384-413（全部以 `messages.value[assistantIndex]` 回访）；配合 useSessionStore.ts:216-252（`loadSession` 整体替换 `messages.value`）。

**描述**：`ask` 在拿到下标后 `await` 数秒至分钟级。期间下列入口都会把 `messages.value` **整体替换或清空**，且没有任何一处检查 `sending`：

- `switchSession` / `switchBranch` / `createAndSwitchSession` / `removeSession`（useSessionStore.ts:254-291, 344-354；Sidebar 按钮未按 sending 禁用）；
- `clearConversation`（useChatEngine.ts:518-528，App.vue:169 直接挂按钮，无守卫）；
- `regenerateFrom`（useChatEngine.ts:444-468，**引擎自身无 sending 守卫**，且 MessageList 的「重试分支」按钮未 disabled——MessageList.vue:332-336 的重试按钮没有 `:disabled="sending"`，而同组评分按钮有）。

**触发条件与后果链**（三档）：

1. **错写他人消息**：流式期间切到消息数更多的新会话，`messages.value[assistantIndex]` 命中新会话的无关消息，token 持续追加、done 覆盖 content/citations，并随后续 `syncCurrentSessionBranch`（useChatEngine.ts:425）+ `persistState()` 写入**新会话的分支**并持久化——静默数据损坏。
2. **TypeError 从 catch 内逃逸**：切换后数组短于 `assistantIndex+1`（regenerateFrom 场景必然如此：fork 的 `baseMessages = messages.slice(0, i)` 长度必小于原 `assistantIndex`），首个 token 回调 `messages.value[assistantIndex].state` 抛 TypeError → 沿 `streamReactChat` 的 `onEvent` 传播进 `ask` 的 catch（useChatEngine.ts:413 再次索引，**catch 块内二次抛出**）→ 未处理 rejection；SSE reader 未 cancel，连接持续下载；`streamPhase` 滞留 `streaming`，状态标签卡死「输出中」。
3. **regenerateFrom 特有的静默空分支**：即使请求恰好已结束，`regenerateFrom` 在调用 `ask` **之前**就完成了 fork + `loadSession` + `persistState`（useChatEngine.ts:448-461），随后 `ask` 因 `sending` 守卫直接 return（useChatEngine.ts:248）——结果是一个标题带「· retry」、只有半截消息、没有任何回答的分支被创建并持久化。

另注意 `clearConversation` 在发送中触发时还会把 `streamPhase` 置 `idle`，随后 token 回调又改回 `streaming`，状态机自相矛盾。

**修复建议**（不改对外 API 签名）：

- 根治：`ask` 内不要用下标回访。创建 `assistantMsg` 后**持有对象引用**，回调/catch 全部改写 `assistantMsg.content/state/...`。切换会话时 `syncCurrentSessionBranch` 已把该对象浅拷贝进旧分支，写引用只会更新旧分支数据（老会话收到剩余 token 属可接受行为），彻底消除越界与错写；401 重试路径的 `splice(assistantIndex, 1)`（useChatEngine.ts:397）改为按 `assistantMsg.id` `findIndex` 后删除。
- 兜底：`regenerateFrom` 与 `clearConversation` 开头加与 `submitEditAndResend`（useChatEngine.ts:490-493）一致的 `sending` 守卫；MessageList「重试分支」按钮补 `:disabled="sending"`；catch 块内回访消息前先判空。

### H-2 `openCitation` 新标签直接 GET，必然 401——引用跳转功能整体不可用

**位置**：useChatEngine.ts:161-170。

**描述**：`window.open(url, '_blank')` 发起的导航请求**不带任何 `Authorization: Bearer` / `X-API-Key` 头**。而后端鉴权只认这两种 header（ApiKeyOrJwtAuthFilter.java:80-81 从 header 取 Bearer；该过滤器无 cookie 凭据通道——refresh token cookie 仅用于 `/auth/refresh` 换发，client.ts:156-169），且 `GET /ai/pdf/file/**` 被显式要求 `PERM_RAG_READ` 或 `ROLE_ADMIN`（SecurityConfiguration.java:67）。因此登录用户点「查看原文」打开的新标签拿到的永远是 401/403 JSON，而非 PDF。

**触发条件**：任何用户点击引用跳转（含游客，游客同样无 header）。100% 复现。

**影响**：引用溯源这条功能链路（后端 `PdfController.download` 明确为此而建，PdfController.java:73-92）从前端入口处即断裂。

**修复建议**（签名不变）：
- 前端：`openCitation` 改为带 `authContext()` header 的 `fetch` → `blob()` → `URL.createObjectURL` 后 `window.open(blobUrl)`（或触发下载）；
- 或后端为该 GET 端点提供短时效签名 URL（一次性 query token），`openCitation` 向后端换取后再开新标签。
- 顺带修正：后端只读 `source` 参数（PdfController.java:74），`chunk` 参数被静默忽略（见 L-2），「定位到具体 chunk」目前名不副实，跳转粒度契约需前后端对齐。

---

## 中严重度

### M-1 `retriedAuth` 在流式成功路径永不重置，一次 401 恢复后自动重试能力永久失效

**位置**：useChatEngine.ts:56（定义）、379（唯一重置点，位于**非流式**分支末尾）、393-394（置位点）。

**描述**：默认会话 `streaming: true`（session.ts:148），绝大多数请求走流式分支；该分支从请求发起到 `done` 结束全程没有任何 `retriedAuth.value = false`。一旦某次 401 触发过自动恢复，`retriedAuth` 恒为 true，之后**所有**凭证过期都直接走错误分支展示「登录状态已失效」类文案，注释承诺的「对用户无感」（useChatEngine.ts:392）只在第一次成立。

**触发条件**：流式会话中发生一次 401 → `ensureCredential` 成功重试后，后续任何一次凭证过期。

**修复建议**：把重置移到收敛点——`done` 回调（useChatEngine.ts:333 附近）与非流式成功末尾各置一次，或在 `finally` 中当 `streamPhase.value === 'done'` 时重置。不得放在 `ask` 开头（否则递归重试会重新放行，破坏「只重试一次」约束）。

### M-2 `scheduleStreamReset` 的旧定时器不被新一轮 `ask` 取消，状态标签被回写为「空闲」

**位置**：useChatEngine.ts:115-124（定时器只在自身重入时清除）、273（`ask` 置 `thinking` 时不清理定时器）。

**描述**：一次响应完成后 `finally` 调 `scheduleStreamReset`（useChatEngine.ts:434）设定 1.8s 后 `streamPhase = 'idle'`。若用户在 1.8s 内再次发送，`ask` 置 `phase='thinking'`，但旧定时器照常到点，把正在进行的请求状态改写为「空闲」且清空 `streamStatusDetail`——header 标签（App.vue:57）与 MessageList 思考条（MessageList.vue:372-373）都显示「空闲 · 处理中...」这类矛盾状态，直到本次流式产生首个 token 才被拉回。快速连续提问是高频操作。

**修复建议**：`ask` 内置 `streamPhase='thinking'` 之前先 `if (streamResetTimer) { window.clearTimeout(streamResetTimer); streamResetTimer = null; }`。顺带把 `scheduleStreamReset` 的真值判断 `if (streamResetTimer)` 改为 `!== null`（`setTimeout` 返回 0 的浏览器上会漏清，虽属理论边界，但修复成本为零）。

### M-3 流式失败时已生成内容被整体覆盖，error/done 事件次序未设防

**位置**：useChatEngine.ts:339-342（error 事件只记 `streamError`，不改状态）、347-349（循环结束后统一 throw）、413-417（catch 用错误文案**整体覆盖** `content`）。

**描述**：
1. error 事件到达时不更新 `streamPhase`/`streamStatusDetail`，UI 滞留「输出中」直到流关闭；
2. 流中途失败（后端发 error 后关闭流）时，用户已看到的 token 全部被丢弃，`content` 被替换为一句错误文案，与 abort 分支「保留部分输出」（useChatEngine.ts:384-387）行为不一致；
3. 若后端在 done 之后仍追加 error 事件（`ReactStreamEvent` 类型上完全允许），已写完的 `done.answer` 同样会被 catch 覆盖为错误文案。

**触发条件**：上游模型/工具链中途报错——长回答场景下丢掉的可能就是最值钱的那部分内容。

**修复建议**：catch 的非 abort 分支在 `content` 为空或仅含部分 token 时，改为保留原内容、把错误文案放进 `streamStatusDetail` 或独立字段（`state='error'` 已有，UI 已按 state 渲染）；error 事件回调内即时置 `streamPhase='error'`、`streamStatusDetail=err.message`，给用户即时反馈而非等流关闭。

---

## 低严重度

### L-1 `traceDurationMs` 用 `步骤数 × 2000` 伪造耗时并展示给用户

**位置**：useChatEngine.ts:109-113；消费点 MessageList.vue:230（`{{ traceSteps.length }} 步 · {{ traceDurationMs }}ms`）。

**描述**：注释自认「rough timing」，但它在界面上与真实毫秒数同格式呈现。一个 3 步 trace 恒显示 6000ms，无论实际 800ms 还是 30s。这是把假数据当遥测数据展示，损害状态区可信度（trace 里没有耗时字段是根因，`ReactTraceStep` 无时间属性，types/react.ts:17-23）。

**修复建议**：后端 `done` 事件/`ReactChatResponse` 增加总耗时字段是正解；在此之前建议 MessageList 只显示「N 步」，去掉伪造的 ms。引擎侧至少先把注释与常量的魔法性质显式化，避免后续被当真值引用。

### L-2 `parseCitation` 对含逗号的文件名解析错误；`chunk` 参数后端根本不读

**位置**：useChatEngine.ts:150-159（`source=([^,]+),\s*chunk=(.+)$`）、164-167；后端 PdfController.java:74 只声明 `source` 参数。

**描述**：citation 格式由 RagAnswerService.java:228 生成（`"source=" + file + ", chunk=" + chunk`），`[^,]+` 在文件名含逗号时于第一个逗号处截断，`?source=` 携带错误来源名 → 404「file not found」。另外 `chunk` 拼进 query 但后端 `download` 方法签名里没有该参数，纯冗余。

**修复建议**：`source` 改为非贪婪 `(.+?),\s*chunk=` 或按 `, chunk=` 字面量切分（生成端格式固定，反向解析可以严格化）；`chunk` 参数待后端支持锚点定位前先移除，避免暗示不存在的定位能力。

### L-3 评分与展开状态跨会话/分支不重置，且条目只增不清

**位置**：useChatEngine.ts:58-62（`expandedCitationCards` / `answerFeedbackMap` / `answerFeedbackLoading`）；唯一清理点是 `clearConversation`（useChatEngine.ts:521-522），切换会话/分支（`loadSession`）不清理。

**描述**：跨会话累积：`answerFeedbackLoading` 每条消息成功后都留一个 `false` 条目，长会话历史下 map 持续膨胀；`expandedCitationCards` 键为 `${message.id}-${card.index}`，旧会话键永不释放。功能性影响小（id 含时间戳+随机，跨会话不撞），但 fork 分支共享消息对象（useSessionStore.ts:368 浅拷贝数组），同 id 消息在兄弟分支里评分状态互通——语义上可辩解为「同一条回答」，建议至少在报告里固化这是有意行为。

**修复建议**：在 `loadSession` 后（或 watch `activeSession`）重置三个状态；或对 map 做简单 LRU 上限。

### L-4 `citationCards` 过滤的校验强度低于 `normalizeMessage`，且过滤后 index 出现空洞

**位置**：useChatEngine.ts:326-328、369-371（`filter((card) => card && card.file && card.snippet)`）对照 session.ts:44-48（`typeof card.file === 'string' && typeof card.snippet === 'string'`）。

**描述**：后端 `citationCards` 是 `List<Map<String, Object>>`（ReactChatResponseVO.java:17），字段经 trace observation 转换而来（ReactResponseFormatter.java:90-91），无强类型保证。引擎侧只做真值过滤：`file`/`snippet` 若是非字符串真值（数字、对象）会原样通过并在渲染层出错。且过滤丢弃不合格卡片后 `card.index` 序号出现空洞，与 `expandedCitationCards` 的 `${id}-${index}` 键、MessageList 的角标显示基于原始 index，虽不致错乱但与 `normalizeMessage` 双重标准。

**修复建议**：抽一个共享的类型守卫（与 session.ts 的判定一致）两处复用。

### L-5 `onBeforeUnmount` 清理了定时器但不 abort 进行中的请求

**位置**：useChatEngine.ts:531-536；对照 useChatEngine.ts:55（`currentAbortController`）。

**描述**：App.vue 是根组件，正常路由下不卸载；但开发态 HMR 重挂载、或未来被嵌入可卸载容器时，卸载后 SSE 回调仍会写已失效的 ref 并保持连接。定时器都记得清，abort 更重要却漏了。

**修复建议**：`onBeforeUnmount` 中补 `currentAbortController.value?.abort()`。

### L-6 `rateAnswer` 引擎侧无 loading 防抖，仅靠 UI disabled 兜底

**位置**：useChatEngine.ts:190-197（只挡「已评分」，不挡「提交中」）。

**描述**：MessageList 评分按钮已用 `answerFeedbackLoading` disabled（MessageList.vue:338-341），当前无实际双发路径；但引擎是公共 composable，`ChatEngine` 类型导出后任何新调用点绕过 UI 即可双发。

**修复建议**：函数开头补 `if (answerFeedbackLoading.value[message.id]) return;`，一行成本。

---

## 修复优先级建议

1. **先修 H-1**：持有 `assistantMsg` 引用替代下标回访 + `regenerateFrom`/`clearConversation` 补 `sending` 守卫 + MessageList 重试按钮 disabled——三个动作都很小，却同时消掉「错写他人会话」「catch 内 TypeError 逃逸」「静默空分支」三种后果。
2. **H-2** 与后端约定签发短时效 URL 或前端 blob 中转二选一，否则引用溯源功能对外不可用。
3. **M-1 / M-2** 均为各一行的收敛点修复（`done` 时重置 `retriedAuth`；`ask` 起始清定时器），建议同一批提交。
4. **M-3** 与产品确认「失败时保留部分输出」的预期后实施。
5. 低严重度按顺手程度合并到相邻需求处理，其中 L-2 需与后端对齐 citation 解析/锚点契约后再动。

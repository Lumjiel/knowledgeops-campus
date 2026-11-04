# 架构分析报告：useChatEngine.ts 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；前端环境无 node_modules，不运行任何 install/build/lint，全部结论来自静态交叉审查；每条结论附源码位置证据）
- **分析对象**：`frontend/src/composables/useChatEngine.ts`（569 行，单工厂函数 :33-566 + 类型导出 :568）
- **核查范围**：唯一调用方 `App.vue:329-353` 的解构面逐成员核对 + 返回成员全前端消费 grep（含 `streamPhase` 死活）；注入契约 `AuthStore`（useAuth.ts:181-193，`ensureCredential` :67-89 全文）与 `SessionStore`（useSessionStore.ts:562）逐成员核实；直接协作者 `api/client.ts`（reactChat/streamReactChat/submitAnswerFeedback）、`utils/session.ts`（createMessage :23-31 含 state 默认值）、`utils/errors.ts`（errorStatus/toUserMessage）、`./useGuestRestriction`（FEATURE_DETAILS 键清点 :26-32）；跨栈对表——401 恢复三层（client.request :40-41 / 本文件 :393-404 / App.vue configureAuthRecovery :356-376）逐层读实现；`retriedAuth` 全部读写点清点；`ChatMessage.state` 五态生产端（chat.ts:23）与本文件 StreamPhase 七态对表
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（options 入参、23 个返回成员、`ChatEngine` 类型均只记录不动）
- **关联报告**：`audit-useChatEngine.ts.md`（同文件缺陷审查：await 窗口下标错位、window.open 401、retriedAuth 单路径重置等——本报告从边界角度归位并补架构定性）；`arch-client.ts.md`（4.2 导航旁路、5.1 API_BASE 副本、5.3 双刷新实现——本报告是其消费侧印证）；`arch-MessageList.vue.md`（4.2 citationTextOf/parseCitation 成对协议、4.4 假数据发现——本报告补 `traceDurationMs` 生产端）；`arch-EvaluationPanel.vue.md`（同层 composable 消费先例）

## 1. 模块定位

本文件是**前端业务状态机核心**：聊天全流程的引擎层——流式/非流式问答（:284-380）、七态流式相位机（:22，StreamPhase）、消息生命周期（pending→streaming→done/error/stopped）、编辑重发与重新生成分支（:444-515）、回答评分反馈（:182-231）、引用与轨迹交互态（:136-170）。

**组装模式是「注入式 composable」**：`UseChatEngineOptions`（:24-31）注入 `persistState`（持久化回调）、`auth: AuthStore`、`store: SessionStore`（两个类型-only import，:19-20）、`ui.scrollToBottom`（DOM 副作用回调）——引擎对 store/auth 只有类型依赖、实例全部注入，自身零创建状态源。返回 23 成员（8 状态 ref + 4 computed + 11 交互函数）。

**调用方唯一**：`App.vue:353`。返回成员消费面逐成员核实：**22/23 已消费**（App.vue:329-353 全量解构后经 props/events 下发 MessageList/ChatInput：:99-127/:169）；**死返回 1 项**：`streamPhase`（:542）——全前端零消费（grep 证实），App 只消费其派生的 `streamStatusLabel`/`streamStatusTagType`/`streamStatusDetail`。删返回成员属对外签名变更，记录为未来项（6）。

与姊妹 composable 的分工清晰：store 管会话持久状态、auth 管凭证、engine 管易失交互状态（streaming 相位、abort controller、反馈 map）——**易失状态归引擎**的边界与 App.vue 的 v-if/v-show 分工（arch-EvaluationPanel 1）一脉相承。

## 2. 依赖全景

### 2.1 出边

| 依赖 | 层级 | 使用点 | 方向评价 |
| --- | --- | --- | --- |
| `api/client.ts` | 数据层 | reactChat/streamReactChat/submitAnswerFeedback（:8 → :199/:286/:351） | 引擎是 client 的直接消费方，方向单向向下 |
| `types/chat.ts` / `types/react.ts` | 类型契约 | ChatMessage/ChatCitationCard/RetrievalTrace（:9）、ReactChatResponse/ReactErrorEvent/ReactTokenEvent/ReactTraceStep（:10-15） | 叶子；`done` payload 的 citationCards/retrievalTrace 经**交叉扩展**断言读取（:313-316/:362-365，类型层缺口见 4.6） |
| `utils/session.ts` | 同 src 工具 | createMessage/deriveTitle（:16 → :255/:264/:453/:501/:519） | 叶子 |
| `utils/errors.ts` | 同 src 工具 | errorStatus/toUserMessage（:17 → :222/:391） | 叶子 |
| `./useGuestRestriction` | 同层单例 | isGuestForbidden/reportGuestRestriction（:18 → :218-220/:406-407） | 引擎是 guest-restriction 的**第二个组件侧消费点**（第一个是 EvaluationPanel，arch 报告 5.3），约定一致 |
| `./useAuth` / `./useSessionStore` | **type-only** | AuthStore/SessionStore（:19-20） | 只依赖类型、实例经 options 注入——**依赖倒置式组装，环风险的结构性消除**（3） |
| `element-plus` | npm | ElMessage（:6 → :187/:191/:216/:223/:383/:396/:417/:467/:491 九处） | 引擎层直调全局 toast——业务 composable 持有 UI 副作用，记入边界（5.4） |
| `vue` / 浏览器 | 框架/平台 | computed/onBeforeUnmount/ref（:7）、AbortController（:280/:423）、window.open（:169）、window.setTimeout（:117） | onBeforeUnmount 在 composable 内注册（:531-536）依赖调用方处于 setup 上下文——唯一调用方 App.vue 满足，成立但属隐式契约（4.8） |

### 2.2 入边

- main 源码 1 个调用方（App.vue:353），消费面 = options 4 项 + 返回 23 成员（22 活 1 死）。
- 消费路径经 App.vue 二次分发：MessageList 消费 14 项（消息渲染/交互 props+events，:99-121）、ChatInput 消费 sending/isEmptyConversation、顶栏 tag 消费 status 三件（:57）。**引擎没有自己的视图**——它是 App 与展示件之间的业务中枢。
- 无测试引用。

## 3. 循环依赖评估

**文件级、模块级、运行时装配级均无环。**

- import 图：`App.vue → useChatEngine → {client → authRecovery, utils, types, useGuestRestriction}` 全部单向；engine 不 import App/store/auth 的**实例**（type-only），`useGuestRestriction` 是零回指叶子单例。
- 三 composable 协作（engine⇄store⇄auth）靠 options 注入单向成图：App 装配顺序 store → auth → engine（App.vue:254/:288/:353），engine 持两者引用、反向零回指。对照 authRecovery 注释自证的「避免 client ↔ useAuth 循环依赖」（authRecovery.ts:5-6）与 arch-client 3.2 的复制式消环——**本文件代表第三种也最干净的手法：类型-only + 注入**。正面样板。
- 运行时数据环（engine 写 `store.messages` → App props → MessageList events → engine 函数）是 Vue 标准单向数据流的闭环，非依赖环。
- 一条预埋风险：`submitEditAndResend`/`regenerateFrom` 直接操作 `session.branches.unshift(branch)`/`session.activeBranchId = ...`（:458-459/:507-508）——引擎**就地修改 store 持有的 session 对象**再调 `loadSession`。当前无环，但这是「引擎越过 store 的函数接口直改其内部数据」的形态：若 store 将来对 branches 加不变式校验（如去重、上限），这两处旁路写入会静默绕过。记入 4.3 同族（状态所有权跨界），记录项。

## 4. 越层评估

### 4.1 三层 401 恢复体系语义分散，且两层会对同一事件各自反应（核心越层项）

同一个 401 在前端有三层各自独立的恢复机制：

| 层 | 位置 | 机制 | 触发语义 |
| --- | --- | --- | --- |
| L1 数据层 | client.request :40-41 → recoverFrom401 | 静默 JWT 刷新 + 重试一次原请求 | 成功=对调用方完全透明 |
| L2 引擎层 | 本文件 :393-404 | `ensureCredential()`（API Key 重换 → 游客兜底，useAuth:67-89）+ 重发整条 ask | L1 失败后 401 传到 catch 时 |
| L3 App 钩子层 | App.vue configureAuthRecovery onAuthExpired :361-376 | 游客→`ensureCredential()`；非游客→清 token + **打开设置弹窗 + 警告** | L1 失败收尾时（与 L2 同时） |

L2 与 L3 由同一次 L1 失败**同时触发**（recoverFrom401 先调 handleAuthExpired → onAuthExpired，再把原 401 响应交回调用方，authRecovery.ts:65-66），后果逐角色核实：

- **非游客**：L3 清 token、开登录弹窗、警告「请重新登录」；与此同时 L2 靠保存的 API Key 重换成功并**静默重发消息**——用户看到登录弹窗弹出的同时回答已在重新生成，两个 UX 语义互相矛盾（且弹窗是 ask 重发的先兆性误报：凭证马上就恢复了）；
- **游客**：L3 调一次 `ensureCredential`（guest 兑换）+ L2 再调一次 `ensureCredential` + L2 重发——同一次过期**两次 exchangeGuestToken**，第二次结果还可能覆盖第一次的 token（时序竞争）。

定性：401 恢复的「谁负责什么」没有单一权威——L1 管令牌刷新、L2 管凭证重置、L3 管用户引导，但 L2/L3 的触发条件完全重叠。修复位（不改签名，均为直接相关文件）：**决议单一权威**——推荐 L2 退出恢复业务（引擎 401 只更新消息态，不再 ensureCredential/重发），把「API Key 重换+重发」并入 L3 钩子的非游客分支；或反向，L3 的游客分支退出（L2 已覆盖）。任一方向都先与 App.vue 逐分支对表。

### 4.2 `retriedAuth` 补丁 ref：重置路径不全，漂移已物化（audit 发现的架构归位）

`retriedAuth`（:56）是防重试死循环的补丁闸，全部读写点清点：置 true 仅 :394（进入重试前），**置 false 仅 :379——非流式成功分支**。即：流式成功、stopped、error、以及「重试本身成功」的所有出口都不重置。由于引擎是 App setup 级单例（不随视图切换销毁），**流式模式下经历一次 401 重试后，整个页面生命周期的后续 401 自动恢复永久失效**（retriedAuth 恒 true，:393 的闸永关）。与 arch-client 5.3 的 rawRefreshJwt、EvaluationPanel 的本地镜像同族——**补丁型 ref 的重置必须与所有出口对称**。修复位：重置移到 finally（:420）或成功出口统一置 false，一行级改动，不改签名。

### 4.3 await 窗口内的下标回访：对象引用在手边却不用（audit 发现的架构归位）

`ask` 捕获 `assistantIndex`（:267）后，跨 await 的回调与 catch 用 `messages.value[assistantIndex]` 回访（:304/:305/:321/:323/:361/:384/:387 等 10+ 处）。await 期间用户可切会话/清空/regenerate——`messages.value` 可被整体替换或缩短，下标即错位（audit-useChatEngine 已记为头号缺陷）。本报告补架构事实：**要持有的对象引用从第一行起就在手边**——`assistantMsg`（:254）就是被 push 的同一对象，回访完全可写 `assistantMsg.content = ...`，无需任何守卫或改签名。引擎选了下标是因为 done/error 分支沿用了「按 index 定位」的模板惯性。另见 3 的 branches 旁路写入——同族问题是「store 拥有数据、engine 按快照坐标操作」的所有权错位。修复位：回访改 `assistantMsg` 引用（:254 既有变量），仅本文件内改动。

### 4.4 `traceDurationMs`：×2000 估算值以「ms」实况名义展示（假数据生产端）

:109-113 以 `traceSteps.length * 2000` 估算轨迹总时长，注释自证「rough timing」；经 App.vue:103 下发 MessageList:230 渲染为 `{{ traceSteps.length }} 步 · {{ traceDurationMs }}ms`——**估算值以实测时延的格式呈现**。这是 arch-MessageList 4.4「检索四路召回条假数据」的同一面板另一处假数据，且该报告只记了消费端，本报告补出生产端在引擎。修复位：改读 ReactTraceStep 真实时间戳（后端 trace 有时间字段）或显示「约 Ns」标注估算——仅引擎+MessageList 直接相关文件。

### 4.5 `'回答反馈'` feature key 未登记 + 门控语义含混（漂移已物化的字符串契约）

`rateAnswer` 的游客 403 走 `reportGuestRestriction('回答反馈')`（:220），但 `useGuestRestriction.FEATURE_DETAILS` 的登记键只有 检索评测/成本看板/长期记忆/知识库上传/在线问答 五个（useGuestRestriction.ts:26-32，grep 证实）——**「回答反馈」未登记，403 恒落 GENERIC_DETAIL 通用文案**，专属引导从未生效。对照 arch-EvaluationPanel 5.3 的同型发现（该处键已登记、属「漂移风险」；本处是漂移已物化）。且上游门控含混加剧：:186 只查 `canUseRemoteSync`（= token 或 apiKey 存在，useAuth:44）——**游客令牌也算数**，游客可一路走到提交、被后端 403 后才见通用文案。修复位：FEATURE_DETAILS 登记「回答反馈」（+ EvaluationPanel 5.3 的常量化方案一并收敛）；`rateAnswer` 门控补 `!auth.guestMode.value` 直拦截——均不改签名。

### 4.6 双状态机：StreamPhase 七态与 `ChatMessage.state` 五态语义重叠

同一引擎养着两个消息生命周期状态机：`StreamPhase`（:22，idle/thinking/tool/streaming/done/error/stopped，管顶栏相位展示）与 `ChatMessage.state`（chat.ts:23，五态，管气泡渲染，arch-MessageList 4.5 已记其半边消费）。两者在 ask 的各分支**逐一手工联动**（:273/:297/:306/:334/:377/:388/:410/:415 八处成对赋值），done/error/stopped 三态语义重叠。联动无约束：任何新增分支漏配一对，顶栏与气泡立即失步（且 streamPhase 本身是死返回成员，1）。收敛方向：phase 的终态派生自 message.state（computed），仅 thinking/tool/streaming 三个过程态独立——属本文件内重构，不改签名。

### 4.7 通道旁路与假数据（已有 arch 结论的引用归位）

- `openCitation` 的 window.open 导航旁路 + 内联 API_BASE 副本：arch-client 4.2/5.1 已完整定性（结构性强制旁路 + 四副本之一），本文件是其消费侧，修复随 arch-client 6.1/6.2 批次。
- `citationTextOf`（MessageList 生成）/`parseCitation`（本文件 :150-159 解析）的跨栈协议串：arch-MessageList 4.2 已议「下沉 useChatEngine 与 parseCitation 成对」——本文件即协议解析端的现在住址。

### 4.8 composable 内注册 `onBeforeUnmount` 的隐式上下文契约（记录项）

:531-536 在工厂函数体内注册卸载钩子——**只有调用方处于 setup 上下文才合法**。唯一调用方 App.vue 满足，当前成立；但这把「必须在 setup 中调用」变成了使用契约却无任何静态标记（对照返回类型 `ChatEngine` :568 做了导出）。组件迁移（如挪进子组件或测试环境）会静默丢清理。记录项：头注释补使用约束即可。

## 5. 平行实现

### 5.1 done 载荷装配逻辑逐字两份（流式 vs 非流式）

citations/citationCards/evidence/retrievalTrace 的清洗装配（Array.isArray → map → filter）在流式 done 分支（:323-332）与非流式分支（:366-375）**逐字级重复**（仅 payload 变量名不同），citationCards 的 `card && card.file && card.snippet` 过滤条件两份拷贝。两分支由同一后端 `ReactChatResponse`（+交叉扩展字段）驱动，装配规则任何一边改动（如加 `chunkIndex` 校验）另一边必漏。收敛为 `applyChatResult(message, result)` 类内单点，不改签名。

### 5.2 401 语义在引擎内部不对称

`ask` 的 catch 有完整 401 分支（:393-404），而同文件的 `rateAnswer` catch（:217-224）只有 403/通用两分支——rateAnswer 遇 401（L1 刷新失败后）直接落 `toUserMessage` 通用文案，无凭证恢复。同引擎两动作对同一错误类的恢复语义不一致（ask 自愈重发、rateAnswer 报错了事）。与 4.1 的三层决议同批处理：若 L2 退出恢复业务，此处的不对称自动消解；若保留 L2，rateAnswer 是否也要 401 恢复需成文决议。

### 5.3 状态标签双 switch 平行表

`streamStatusLabel`（:71-88）与 `streamStatusTagType`（:90-107）是同一 StreamPhase 的两张平行映射表（七态×文案/颜色），新态漏配一处即文案与颜色失步。收敛为单张 `{ label, tagType }` 映射表，不改签名。

### 5.4 排除性结论（正面）

- **SSE 事件消费唯一站点**：引擎是 `streamReactChat` 事件的唯一消费者（arch-client 4.6 证实解析唯一在前端也只剩一处消费），done payload 的 citationCards/retrievalTrace 交叉扩展字段（:313-316/:362-365）只此一处读取——扩展字段应上收到 types/react.ts 的正式类型（类型层缺口，记录项）。
- **引用解析唯一站点**（4.7）；**消息态清扫单点**：`sanitizeMessageStates`（:237-245）是 pending/streaming 孤儿态的唯一修复入口，clearConversation（:518-528）走重置而非清扫，无平行。
- **abort 语义单点**：`currentAbortController`（:55/:234/:281/:400/:423）全生命周期单引用，stopGenerating 无第二控制通道。
- ElMessage 直调（九处）与全前端约定一致（arch-EvaluationPanel 5.4），无平行封装——但业务 composable 层直调 UI toast 值得记一笔：引擎的错误提示策略（哪些进气泡、哪些进 toast）与视图耦合在引擎内部，换展示容器（如移动端 banner）需改引擎。记录项。

## 6. 修复优先级建议（均不改对外 API 签名；改动仅限本文件+直接相关文件）

1. **P1** `retriedAuth` 重置与出口对称化（4.2）：重置移入 finally 或全部成功出口——一行级修复，消掉「流式 401 自愈只工作一次」。
2. **P1** await 窗口回访改对象引用（4.3）：`messages.value[assistantIndex]` 全部改 `assistantMsg`（:254 既有引用），消掉切会话错位的根源。
3. **P1** `'回答反馈'` key 登记 + `rateAnswer` 门控补游客直拦截（4.5）；与 EvaluationPanel 5.3 的 feature key 常量化同批收敛。
4. **P2** 三层 401 恢复决议单一权威（4.1）：L2 退出或 L3 游客分支退出，二选一后与 App.vue 逐分支对表；`rateAnswer` 的 401 语义随决议成文（5.2）。
5. **P2** done 载荷装配收敛 `applyChatResult`（5.1）；状态标签单表（5.3）；`traceDurationMs` 去假数据（4.4）。
6. **记录项**：`streamPhase` 死返回成员（1，删除属签名变更需与消费规划同批）；双状态机收敛方向（4.6）；citationCards/retrievalTrace 扩展字段上收 types/（5.4）；`session.branches` 旁路写入（3）；composable 的 setup 上下文契约入注释（4.8）。

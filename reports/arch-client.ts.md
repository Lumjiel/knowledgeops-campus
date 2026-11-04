# 架构分析报告：client.ts 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；前端环境无 node_modules，不运行任何 install/build/lint，全部结论来自静态交叉审查；每条结论附源码位置证据）
- **分析对象**：`frontend/src/api/client.ts`（647 行 = 基础设施 :25-104 + 鉴权 :106-169 + React 对话与 SSE :171-334 + 会话/分支 :336-482 + 反馈 :484-506 + 成本 :508-539 + 评测 :541-647）
- **核查范围**：21 个导出函数的调用方逐一 grep 清点（消费面表见 2.2）；直接协作者 `api/authRecovery.ts` 全文（182 行）、`utils/errors.ts` 全文（109 行）、`types/react.ts` 19 个类型（:1-20 import 块）；旁路通道排查（全前端 `VITE_API_BASE`/fetch 直连/X-Tenant-ID grep，MemoryHintBar.vue、useChatEngine.ts:161-171 逐一读）；跨栈对表——后端 19 个 @RequestMapping 基路径、`AuthController.refresh`（:136-142 X-Refresh-Token/cookie 双通道）、`RequestContextFilter`（:38-42 租户头语义）、`ReactController`（/ai/react/chat[/stream]）；既有 `audit-client.ts.md` 全部发现（H-1/M-1/M-2/L-1~L-6，本报告只引不重审）
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（21 个导出函数与内部函数均只记录不动）
- **关联报告**：`audit-client.ts.md`（同文件缺陷审查：干净截断/无超时/200 非 JSON 矛盾对象等，本报告只从边界角度引用）；`arch-EvaluationPanel.vue.md`/`arch-MessageList.vue.md`（消费方两端的边界事实）；`arch-AuthController.java.md`（refresh cookie 通道为预埋契约的跨栈一侧）；`arch-ReactAgentService.java.md`（formatSse 双布局服务端成因）

## 1. 模块定位

本文件是**前端唯一的数据访问层**：21 个导出函数一对一封装后端 HTTP 端点，外加一组「只有这一层知道」的基础设施——`request()` 统一 fetch 包裹（含 **401 拦截→静默刷新→重试一次**的恢复管线，:37-47）、`buildAuthHeaders` 凭证装配（:56-67）、`normalizeFetchFailure` 网络失败归一（:44-46 委托 errors.ts）、`resolveApi` 基路径解析（:49-54）、SSE 流解析器（:200-334）。

它同时是**错误语义的转换边界**：后端两种响应信封（`{ok,msg}` 与裸 VO）+ 原始 markdown 三种形态在这里统一折叠为「成功 payload / `FriendlyError(status)`」二值出口，展示层（composables/组件）只见 `toUserMessage` 的友好文案——`utils/errors.ts:9` 注释自证这个分工（「由 API 层抛出，展示层只读取文案」）。

**边界定性**：这是全前端依赖位置最重要的模块——5 个消费方（2 组件 + 3 composable）的全部后端访问都应经过它，因此它的每个「统一保证」（401 恢复、超时、错误归一）都是**全局保证**，每个旁路都是**全局保证的豁免口**。4.1/4.2 的两个旁路现场即按此标准评估。

## 2. 依赖全景

### 2.1 出边

| 依赖 | 层级 | 使用点 | 方向评价 |
| --- | --- | --- | --- |
| `types/react.ts` | 类型契约 | 19 个类型（:1-20），覆盖全部导出函数的入参/返回 | 叶子类型文件；注意 `PagedResult`（:336-341）与 `BasicResult`（:484-487）两个信封类型**定义在本文件内**而非 types/，见 4.5 |
| `utils/errors.ts` | 同 src 工具 | `createFriendlyError`/`normalizeFetchFailure`（:22 → :45/:82） | 叶子；错误语义转换的唯一委托点 |
| `api/authRecovery.ts` | 同层兄弟 | `recoverFrom401`（:23 → :41） | **同层唯一横边**，client → authRecovery 单向；authRecovery 零回指（3.2） |
| 浏览器 API | 平台 | `fetch`/`Headers`（间接）/`FormData`（:126）/`URLSearchParams`（:92）/`TextDecoder`（:302） | 均为标准通道 |

**关键零依赖**：不 import 任何 composable、组件、App.vue、storage——数据层对状态层零感知，鉴权状态经 authRecovery 的钩子注入间接获得（3.2）。方向严格单向，出边全叶。

### 2.2 入边

main 源码 5 个消费方，import 清单逐一核实：

| 消费方 | 导入函数 | 域 |
| --- | --- | --- |
| `composables/useAuth.ts`（:7-12） | exchangeApiKey、exchangeGuestToken、getTenantCostSummary、refreshJwt | 鉴权 + 成本 |
| `composables/useSessionStore.ts`（:8-16） | compareSessionBranches、listSessionStates、mergeSessionBranches、saveSessionState、setSessionArchived、setSessionPinned | 会话/分支 |
| `composables/useChatEngine.ts`（:8） | reactChat、streamReactChat、submitAnswerFeedback | 对话/反馈 |
| `components/EvaluationPanel.vue`（:106-113） | createEvalDataset、listEvalDatasets、triggerEvalRun、getEvalComparison、markEvalRunBaseline、exportEvalRunReport | 评测 |
| `components/ChatInput.vue`（:65） | uploadPdf | 上传 |

**死导出 1 个**：`updateTenantBudget`（:521-539）全前端**零消费方**（21 个导出逐一 grep，唯一 0 计数项）——后端 `/cost/budget` 端点能力在 API 层已接线、UI 层从未接（SettingsDialog 无预算 UI）。且它**不在任何白名单豁免语境**，是纯死面。删导出属对外签名变更，记录为未来收敛项（6）。

另：`refreshJwt` 的真实消费方只有 useAuth.ts:139 一处；authRecovery.ts:160 的提及是注释（刻意不复用，见 3.2/5.3）。

无测试引用（前端无测试设施）。

### 2.3 无样式依赖

纯逻辑模块，无 CSS 面（对照两份组件 arch 报告的 2.3 节，本文件无此项）。

## 3. 循环依赖评估

**文件级与模块级均无环**：`App/组件/composables → client → {authRecovery → utils/storage+types, utils/errors, types}`，全部单向；authRecovery 不回指 client（其 `rawRefreshJwt` 直接 fetch，:162-182），client 不感知 useAuth。

**两个历史环威胁、两种消环手法**（均被源内注释自证，是本模块边界史上最有记录价值的部分）：

1. **client → authRecovery → (需要鉴权状态) → useAuth → client**：以**钩子注入**消环——authRecovery 定义 `AuthRecoveryHooks`（:17-24），App.vue 装配期经 `configureAuthRecovery` 注入 getAuth/applyAuth/onAuthExpired（:35-37），authRecovery 头注释 :5-6 明示动机「避免 client ↔ useAuth 循环依赖」。**结构解法，正面样板**。
2. **client ↔ authRecovery 的 refreshJwt**：以**代码复制**消环——authRecovery 不复用 `client.refreshJwt`，自养 `rawRefreshJwt`（:162-182），注释 :159-161 明示「那会引入 client ↔ authRecovery 循环依赖」。**复制解法，代价是漂移面**——且漂移已发生：rawRefreshJwt 失败抛裸 `Error`（:179），非 FriendlyError，`toUserMessage`/`errorStatus` 都读不出状态码，刷新失败路径的用户文案质量与主路径已经分叉（5.3）。

**消环的结构性正解**（不改行为，触及导出归属故列为未来项）：`refreshJwt` 下沉 authRecovery（client 本就有 → authRecovery 单向边，方向不变），useAuth 改从 authRecovery 导入——`rawRefreshJwt` 副本即消。当前约束下短期最小动作是给两处副本加互指锚注释（6）。

## 4. 越层评估

### 4.1 统一通道旁路 #1：MemoryHintBar 自建 mini-client，绕过全部统一保证（核心越层项）

`MemoryHintBar.vue` 没有走 client.ts，自建了完整数据通道三件套：

- 基路径：`memoryApiBase()`（:36-38，`(import.meta.env.VITE_API_BASE) ?? '/api'` 逐字复制，见 5.1）；
- 凭证头：`memoryAuthHeaders()`（:40-46，buildAuthHeaders 的**缺租户分支副本**）；
- 请求：裸 `fetch` 两处（:53 GET /memory、:84 DELETE /memory/{id}）。

旁路的具体代价逐项成立：**401 恢复管线对其失效**（request():40 的拦截它不走——长期记忆场景 token 过期即裸 401）；**网络失败不经 normalizeFetchFailure**（断网抛裸 TypeError，错误文案降级路径未知）；**租户头缺位**（副本没有 X-Tenant-ID 分支，未鉴权场景下与其他通道行为不一致）。定性：这不是技术不可行（4.2 那种），是**纪律缺口**——组件层直建数据通道，数据层被降格为「可选建议」。修复位：client.ts **新增** `getMemoryHint`/`deleteMemoryHint` 两个封装（增量、零签名变更），MemoryHintBar 改为调用——改动仅两个直接相关文件。

### 4.2 统一通道旁路 #2：引用跳转的 window.open——结构性强制旁路（记录项，前端单侧不可修）

`useChatEngine.openCitation`（:161-171）以 `window.open` 新标签 GET `/ai/pdf/file/{chatId}`——新标签无法携带 Authorization/X-API-Key 头，而认证过滤器无 cookie 通道（audit-useChatEngine 已证），**必然 401**。与 4.1 不同：这不是纪律问题而是**通道能力缺口**——统一通道只有「页内 fetch」一种访问形态，对「导航型访问」无故事。修复需要后端（cookie 通道或签名短时 URL），前端单侧不可修；本报告记录其架构定性，防止被误归为与 4.1 同类的「忘了走 client」。

### 4.3 `X-Tenant-ID` 双发契约：与凭证并存时是静默 no-op（跨栈记录项）

`buildAuthHeaders`（:63-65）在 tenantId 存在时**总是**发 `X-Tenant-ID`，但后端 `RequestContextFilter.java:38-42` 的语义是：**已认证身份的租户权威，header 仅在无认证身份时生效**（防伪造，注释 :33-36 自证）。对表结果：本文件内携带凭证的调用全部同时带租户头（恒 no-op），不带凭证的调用只有 exchangeApiKey（:115）/exchangeGuestToken（:146）两处（恰好是 header 真正生效的场景）——**行为正确，但契约形态误导**：调用方会形成「传 tenantId 即可控租户」的假象，实际控制权在凭证里。且全前端 grep 证实 X-Tenant-ID 只有 client.ts 一处发送方，无旁路强化点。修复位：`buildAuthHeaders` 补注释锚定「header 仅未鉴权场景生效」（不改签名）。

### 4.4 凭证优先级是双文件散落的单点知识

`buildAuthHeaders` :58-62：token 存在时静默压过 apiKey（audit L-3 已记缺陷侧）。本报告补边界侧事实：**同一条优先级知识的另一消费点在 authRecovery**——401 重试时 `headers.delete('X-API-Key')` + `set('Authorization', ...)`（authRecovery.ts:53-56，注释 :54 自证「刷新后的 token 是权威凭证」）。即「token > apiKey」这条排序知识住在两个文件、只有 client 侧有函数单点、authRecovery 侧是内联操作——两处将来改动不同步即恢复行为漂移。记录项：两处互指锚注释。

### 4.5 响应信封三形态：折叠知识正确、逐函数散写是 audit M-2 的结构性根源

对后端逐端点核实的信封形态在本文件的分布：

| 信封 | 函数 | 对应后端风格 |
| --- | --- | --- |
| `{ok, msg}`（判 `payload.ok !== 1`） | exchangeApiKey、exchangeGuestToken、refreshJwt、uploadPdf、reactChat、submitAnswerFeedback（6 个） | Result 风格 controller |
| 裸 VO（只判 `response.ok && payload`） | sessions×6、cost×2、eval×6（14 个） | 裸 VO 风格 controller |
| 原始文本（只判 `response.ok`） | exportEvalRunReport（:642-645，text/markdown） | markdown 产出端点 |

折叠本身正确（类型层佐证：`AuthTokenResponse.ok: number` 是非可选字段，SessionState 族无 ok 字段——两种信封在前端类型里已如实分型）。但**信封判定知识逐函数手写、无共用判定器**，200+非 JSON 的矛盾对象（audit M-2）正是 14 个裸 VO 函数每个都独立裸奔的结构性结果；`PagedResult`/`BasicResult` 两个信封类型定义在本文件而非 types/（:336-341/:484-487），与「类型住 types/」的模块约定有轻度偏离（因其纯是传输层信封，留在此处亦可辩护——记录定性，不要求搬）。修复方向与 audit M-2 一致但在架构面表述为：信封判定收敛为单一 helper（裸 VO 路径增加形态嗅探），属本文件内改动，不改签名。

### 4.6 SSE 解析子系统住在数据层：归属正确 + 类内小平行

`parseSseChunk`/`takeNextChunk`/`streamReactChat`（:193-334）是传输层协议解析，住数据层是**正确归属**（对照 arch-EvaluationPanel 4.4 的解析知识误住 UI 组件——同一类知识两种归属的正反例）。排除性核查证实它是**全前端唯一 SSE 解析站点**（useChatEngine 无任何 `data:event:` 处理；e2e 脚本的同款双布局解析在后端仓库栈外）。类内一处小平行：4 个事件名的白名单判断逐字写两遍（:211 与 :225），收敛为单个常量数组即可。

### 4.7 死导出与能力缺口同源（2.2 的架构面）

`updateTenantBudget` 零消费不是孤例噪音：`getTenantCostSummary` 已被 useAuth 接线（成本摘要有 UI），而**预算更新是同一成本域里没接到 UI 的另一半**——API 层先行、UI 未跟的半截接线。处置二选一（删导出或补 UI）都触及对外签名/新功能，均列为未来项；在删除前它是「这个端点前端到底支不支持」的口头契约噪音源。

## 5. 平行实现

### 5.1 API_BASE 解析逐字四副本（本报告核心平行发现）

`(import.meta.env.VITE_API_BASE as string | undefined) ?? '/api'` 逐字出现 **4 处**：

| 位置 | 形态 |
| --- | --- |
| client.ts:25 | `API_BASE` 常量 + `resolveApi`（含 http(s) 直通分支 :50-52）——**唯一带直通与拼接逻辑的实现** |
| authRecovery.ts:163 | rawRefreshJwt 内联（无直通分支） |
| useChatEngine.ts:162 | openCitation 内联（无直通分支） |
| MemoryHintBar.vue:37 | `memoryApiBase()` 私有函数（无直通分支） |

成因明确：`API_BASE`/`resolveApi` 均未导出，通道外成员只能复制。改 base 策略（如加版本前缀、切网关）需要同步四处，漏一处即静默打到错误前缀。**修复位最小**：client.ts 导出 `resolveApi`（增量导出，不改既有签名），三处副本收敛——全部在直接相关文件内。

### 5.2 凭证头装配两副本且已漂移

`MemoryHintBar.memoryAuthHeaders`（:40-46）是 `buildAuthHeaders`（:56-67）的副本，且**漂移已发生**：副本缺 `X-Tenant-ID` 分支。同 4.1 一并收敛后此副本即消。

### 5.3 `rawRefreshJwt` vs `refreshJwt` 双实现（3.2 的漂移面清点）

除少一层 request() 恢复包裹（刻意的、注释自证）外，两份实现的**非刻意**差异：错误构造——refreshJwt 抛 `FriendlyError`（经 formatHttpError，:81-83），rawRefreshJwt 抛裸 `Error(msg)`（:179）。下游效应：useAuth.ts:139 的主刷新路径错误可被 `errorStatus`/`toUserMessage` 正常解读；authRecovery 的静默刷新失败被 `catch { return false; }`（:91-93）吞掉倒无影响——**当前无用户可见故障，但两条路径的错误语义已分型**，任何人将来在 doSilentRefresh 的 catch 里补用户提示时会拿到一个 status 缺失的错误对象。收敛同 3.2 的下沉方案。

### 5.4 排除性结论（正面）

- **fetch 统一包裹唯一站点**：`request()` 无第二实现；全部旁路点已枚举（4.1/4.2 两处，且有结构性成因），无第三通道。
- **网络失败归一唯一站点**：normalizeFetchFailure 只被 client.ts 调用；各调用方兜底文案统一走 toUserMessage（errors.ts 职责链完整）。
- **基路径直通逻辑唯一**：http(s) 判定只在 resolveApi 有，三副本均无此分支（当前三处副本只打固定路径，行为暂等价——这是「副本暂时无害」的典型态，5.1 收敛的时机窗口）。
- **SSE 解析唯一站点**（4.6）；**信封判定逐函数独立但无错误平行**（14 个裸 VO 函数的 `!response.ok || !payload` 判定形态一致，系逐字同构而非漂移）。

## 6. 修复优先级建议（均不改对外 API 签名；改动仅限本文件+直接相关文件）

1. **P1** MemoryHintBar 收敛进统一通道（4.1/5.2）：client.ts 增设 `getMemoryHint`/`deleteMemoryHint` 封装，组件删除自建 base/headers/fetch 三件套——消除唯一「无故旁路」，401 恢复与错误归一即刻覆盖长期记忆功能。
2. **P1** 导出 `resolveApi` 并收敛 5.1 三处 base 副本（authRecovery/useChatEngine/MemoryHintBar 随 P1 一并改）。
3. **P2** SSE 事件名白名单常量单点（4.6）；`buildAuthHeaders` 补租户头语义注释（4.3）、与 authRecovery 凭证优先级操作互指锚注释（4.4）。
4. **P2（未来批次，触及导出归属）** `refreshJwt` 下沉 authRecovery、消 `rawRefreshJwt` 副本（3.2/5.3）；若嫌动导出面，最低限先给 rawRefreshJwt 的错误构造换成 `createFriendlyError`（authRecovery 增一条 → utils/errors 的边，增量）。
5. **记录项**：引用跳转的导航通道缺口需后端侧方案（4.2）；信封判定收敛 helper（4.5，与 audit M-2 修复同批）；`updateTenantBudget` 删除或补 UI（4.7，删除属签名变更需与消费方规划同批）。

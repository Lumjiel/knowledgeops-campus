# 缺陷审查报告：client.ts

- **审查对象**：`frontend/src/api/client.ts`（647 行，全文通读）
- **审查方式**：只读静态审查（环境无 node_modules，无法运行 vue-tsc/build，结论均来自源码交叉比对）；直接协作者已逐一核实——`utils/errors.ts` 全文（`FriendlyError`/`normalizeFetchFailure` 恒抛出/`toUserMessage`）、`api/authRecovery.ts` 全文（单飞刷新、重试、钩子注入、直写 localStorage 兜底）、全部 7 个调用方（App.vue、useChatEngine、useSessionStore、useAuth、ChatInput、EvaluationPanel、authRecovery 自身）、后端契约逐点核对：`ReactResponseFormatter.formatSse:47-49`（SSE 线格式为标准 `event: x\ndata: json\n\n`）、`AgentSessionController`（裸 VO/PagedResult）、`CostGovernanceController`（裸 VO）、`IngestionController:40-51`（`{ok,msg,job}`，且支持 `X-Idempotency-Key`）、`EvaluationController`（裸 VO）、`AuthController`（`{ok,...}` + **refresh cookie 签发**）、`RequestContextFilter:33-43`（认证身份租户权威，X-Tenant-Id 仅无认证身份时采用）、`SecurityConfiguration:37`（**CSRF disabled**）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm install/build 操作
- **关联报告**：`reports/audit-EvaluationPanel.vue.md`、`reports/audit-MessageList.vue.md`、`reports/audit-AuthController.java.md`

---

## 概览

该文件是全前端唯一的 API 层：统一 fetch 包装（401 恢复 + 网络失败归一）、30 余个端点函数、自研 SSE 解析器。做对的部分：

- **401 恢复设计完整**：`request()` 统一拦截 401 → 单飞（`refreshInFlight` 共享同一次刷新）→ 换新 token 重试一次 → 仍 401 按过期收尾；鉴权端点豁免防递归（`:27-30`）；`authRecovery` 用注入钩子解开 client↔useAuth 循环依赖，注释明确记录设计动机；
- **网络失败归一正确**：`normalizeFetchFailure` 恒抛出（`never`），AbortError 原样透传保留"用户取消"语义；
- **后端响应形态逐点吻合**：裸 VO 端点（sessions/cost/eval）不查 `ok` 字段、Result 型端点（feedback/auth/upload）查 `ok === 1`——两种后端风格没有混用错；
- **所有路径参数 `encodeURIComponent`**；`withQuery` 跳过 undefined/null/''，布尔正常序列化；
- **SSE 解析器整体健壮**：跨 chunk 缓冲（`takeNextChunk` 处理 `\n\n` 与 `\r\n\r\n` 两种分隔）、`TextDecoder(stream:true)` + 尾部 flush、keep-alive/注释行静默忽略、非 JSON payload 回退为字符串、同时兼容标准 `event:`/`data:` 与旧自定义 `data:event:`/`data:data:` 线格式（与后端 `formatSse` 实测吻合）；
- **`buildAuthHeaders` 的租户头不构成伪造面**：后端 `RequestContextFilter` 明确以认证身份的租户为权威，header 仅在无认证身份时采用。

核心问题集中在**流式生命周期的两个缺口**（H-1 干净截断不可检测、M-1 无超时）——恰好都是"只有 API 层知道、调用方无法自行补救"的职责所在。

---

## 高严重度

### H-1 流式响应"干净截断"不可检测：流结束即正常返回，不校验是否收到过 done/error 事件

- **位置**：`:275-334`（`streamReactChat`：`reader.read()` 返回 done 后循环正常退出、flush 尾巴、`return`，全程未跟踪是否收到过 `done`/`error` 事件）；对照调用方 `useChatEngine.ts:345-349`（`streamReactChat` resolve 后只检查 `streamError`——由 `error` 事件设置）与 `useChatEngine.ts:431-438`（finally 中 `streamPhase` 不在 done/error/stopped 时**不调用** `scheduleStreamReset`）
- **描述**：连接被服务端/代理**正常关闭**（TCP FIN，fetch 不抛错）但未发出终止事件时——部署重启、LB 空闲超时切断、upstream 连接复用被回收、后端 Flux 异常终止但连接优雅收尾——`streamReactChat` 无声无息地正常 resolve。调用方侧：`streamError` 为空 → 不走 catch → 消息永久停留 `state='streaming'`（气泡上"生成中"不消失）、`streamPhase` 停在 'streaming'/'tool'，finally 因 phase 不匹配跳过状态行重置；`sending` 虽复位，但用户看到的是一个"还在生成"的僵尸消息，且没有 error 提示。与之对照，非流式 `reactChat` 路径不存在此问题（响应要么完整要么抛错）。
- **触发条件**：任一次流式回答中，服务端在 done 事件前断开连接且断开方式为正常关闭（生产环境长连接下的常见事件）。
- **影响**：主聊天路径的静默挂死；用户唯一恢复手段是重新提问，且无从得知刚才的回答丢了。这是 API 层职责：**只有 client 知道流是否完整终止**，调用方仅凭事件回调无法区分"还在传"与"断没了"。
- **修复建议**：在 `streamReactChat` 内跟踪 `sawDone`/`sawError`，流正常结束但未见任一终止事件时 `throw new FriendlyError(0|502, …, 'stream truncated')`（行为变更，**不需改函数签名**），调用方现有 catch 即可正确收尾（消息置 error、状态行复位）；若担心误伤可提供选项开关。同时建议在函数内 `try/finally { reader.cancel() }`——目前调用方 `onEvent` 回调抛异常时（如 payload 形状漂移导致引擎侧取属性报错）reader 不会释放，连接悬挂。

---

## 中严重度

### M-1 所有请求无超时控制：服务端挂起即永久 pending，UI 状态永久占用

- **位置**：`:37-47`（`request()` 直接 `fetch(url, init)`，无 `AbortSignal.timeout`，也无手动定时器）；调用方状态占用例：`useChatEngine`（`sending`/`currentAbortController`）、`EvaluationPanel.vue:392`（`evalRunning`，配合其分钟级同步运行，见该报告 M-1）
- **描述**：连接已建立但响应不返回（后端线程 hung、代理黑洞、TCP 半开）时，fetch 永远 pending。前端没有任何请求有超时兜底：聊天按钮永久转圈、评测运行按钮永久 loading、会话保存静默丢失，唯一恢复手段是刷新页面。`AbortError` 语义与超时可以共存：超时只是另一种主动取消。
- **触发条件**：任意请求期间后端/代理停滞。
- **影响**：所有依赖"请求必然终结"假设的 UI 状态机（sending/loading/running）失效；配合 M-1 缺口，用户无法区分"慢"与"死了"。
- **修复建议**：`request()` 内统一挂超时——`AbortSignal.any([init.signal, AbortSignal.timeout(timeoutMs)])`（Chrome 116+/FF 113+；项目 target 支持则最简），SSE 流可豁免或用远超常规的空闲超时；超时归一为 `FriendlyError(408, …)`（errors.ts 已有 408 文案'请求超时'）。**不改变 `request`/各端点函数签名**；需要调用方可控的场景后续再暴露 options 参数。

### M-2 200 响应但非 JSON（代理污染/SPA 回退）被报成"服务暂时不可用"，且错误对象 status=200 自相矛盾

- **位置**：`:69-79`（`parseJsonSafely` 解析失败返回 null）+ 各端点的 `if (!response.ok || !payload)` 模式（如 `:369-372`、`:554-557`）
- **描述**：反代/网关返回 200 但响应体不是 JSON 时（Vite dev proxy 未命中走 SPA 回退返回 index.html、某些鉴权代理插 200 HTML 页、网关 200 空体），`parseJsonSafely` 返回 null → 走 `formatHttpError(response.status=200, …)` → `FriendlyError(200)`，用户看到 `describeHttpStatus(200)` 落 DEFAULT 的"服务暂时不可用"，而 `errorStatus(error)` 返回 200——既不是 401（不会触发恢复）也不是 403（游客引导卡不出现），错误完全失真且无从排查（detail 里倒是有"page HTML"之类，但只进 console）。`exportEvalRunReport`（`:642-646`）无此问题（只查 response.ok）。
- **触发条件**：dev 代理配置漂移、网关/鉴权代理插入 200 响应——联调与部署切换期常见。
- **影响**：错误提示误导 + 语义矛盾的错误对象；排查时用户侧无任何"响应被代理替换"的线索。
- **修复建议**：`!response.ok || !payload` 拆开：`response.ok && !payload` 时抛专用错误（建议 `createFriendlyError(502, \`non-JSON response (content-type: ${response.headers.get('content-type')})\`)`），把"代理污染"与业务失败区分开。

---

## 低严重度

### L-1 `uploadPdf` 未利用后端 `X-Idempotency-Key` 契约，上传可重复提交

- **位置**：`:125-138`；对照 `IngestionController.java:43`（显式支持 `X-Idempotency-Key` 请求头做幂等）
- **描述**：后端为重复上传留了幂等键入口，前端从不发送。上传进行中用户可再次点上传按钮（ChatInput 的 `pdfUploading` 只做 loading 态、未禁用触发入口，文件对话框可再次打开），同一文件并发提交会走两次解析入库。
- **修复建议**：每次选择文件时生成 uuid 作为 `X-Idempotency-Key` 随上传发送；配合调用方在上传期间禁用触发入口。

### L-2 SSE 解析器的健壮性边角

- **位置**：`:254-273`（`takeNextChunk` 不处理 `\r\r` 与 `\n\r\n` 混合分隔）；`:230-235`（空 `data:` 行按 SSE 规范应累积为数据换行，此处直接跳过）；`:209-214,223-229`（白名单外的事件名静默沿用上一值/默认 'token'）
- **描述**：当前后端 `formatSse` 固定输出 `\n\n` 分隔 + 四种事件名，以上均无实害；但作为通用解析器，一旦后端切换 SSE 框架（如改用 SseEmitter 默认 keep-alive 注释行、或事件名扩展）会静默错位——未知事件被当作 token 传给调用方尤其危险。
- **修复建议**：未知事件名至少 console.warn 一次；`\r\r` 分隔可在 `takeNextChunk` 中补一档。

### L-3 `buildAuthHeaders` 中 token 静默压过 apiKey

- **位置**：`:56-67`
- **描述**：`AuthContext` 同时携带 token 与 apiKey 时，apiKey 被静默丢弃，无文档说明优先级；401 恢复重试时 `headers.delete('X-API-Key')`（authRecovery:55）说明作者意识到两者可共存，但正常路径的优先级未成文。
- **修复建议**：注释写明"token 优先"；或在两者并存时 console.warn 一次（配置错误信号）。

### L-4 除 chat 两函数外全部无 `signal` 参数，不可取消

- **位置**：`:343-647`（sessions/eval/cost/feedback 全系列）
- **描述**：只有 `reactChat`/`streamReactChat` 接受 `AbortSignal`。评测对比加载（EvaluationPanel 的切换竞态，见其报告 H-1）、会话列表加载等均无法中止——这不是独立缺陷，而是这些函数无法参与上层竞态治理的公共原因。
- **修复建议**：为高频函数（`getEvalComparison`、`listSessionStates`、`listEvalDatasets`）增加可选 `signal?: AbortSignal` 末位参数（可选参数不构成对外签名破坏，现有调用不变）。

### L-5 `isAuthEndpoint` 用 `includes` 匹配

- **位置**：`:28-30`
- **描述**：`url.includes('/auth/token')` 对理论上的 `/auth/tokenXYZ` 会误判为鉴权端点而跳过 401 恢复。当前路由表无此类路径，纯防御性问题。
- **修复建议**：改用 `API_BASE + path` 的 `endsWith` 或精确路径集合匹配。

### L-6 `exportEvalRunReport` 把错误响应体整段塞进 detail，可能全量打进控制台

- **位置**：`:642-644`（`formatHttpError(response.status, text || …)` → `createFriendlyError` 的 `console.warn('[api] HTTP …: <detail>')`）
- **描述**：非 ok 时 `text` 是后端 JSON 错误体，可能含评测用例/租户数据，整段进浏览器 console。detail 设计初衷是"后端 msg"，JSON 全文超出预期。同为敏感面的还有 detail 经 `toUserMessage` 的 `isReadableText` 过滤——JSON 含中文时（后端中文报错）会把整段 JSON 透出给用户。
- **修复建议**：非 JSON content-type 时截断 detail（如前 200 字符）；或解析出 `msg` 字段再传。

---

## 修复优先级建议

1. **先做 H-1（流截断检测）**：`streamReactChat` 内跟踪终止事件 + finally `reader.cancel()`——主聊天路径静默挂死的直接解，且不需改对外签名。
2. **再做 M-1（请求超时）**：`request()` 统一 `AbortSignal.any + timeout`，配合 errors.ts 已有的 408 文案，一处修复覆盖全部 30 余个端点。
3. **M-2**：拆开 `!response.ok || !payload`，把代理污染从业务失败中区分出来——联调/部署期排查成本的主要来源。
4. **低严重度项**：幂等键、SSE 边角、signal 参数、includes 匹配、detail 截断，均可随上述批次顺带完成。

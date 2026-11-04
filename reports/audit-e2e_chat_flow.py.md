# 审查报告：scripts/e2e_chat_flow.py

- **审查日期**：2026-11-04
- **审查方式**：只读通读全文（167 行），将脚本调用的两个业务端点、健康检查、认证头与 SSE 解析逻辑逐一对码核实，未改动任何源码，未 git commit/push，未删除文件，未执行 npm install / build / mvn 构建。
- **协作者清单**（结论均 grounded 到以下文件）：
  - 端点：`controller/ReactController.java`（`/ai/react/chat`、`/ai/react/chat/stream`）、`domain/vo/ReactChatRequestVO.java`、`ReactChatResponseVO.java`
  - 管道：`service/ReactAgentService.java`（MAX_STEPS=4，`:39`；流式事件构造 `:172-234`）、`service/ReactResponseFormatter.java`（`formatSse` `:47-49`、`success` `:21-33`）
  - 安全：`config/SecurityConfiguration.java`（`:51,58`）、`security/ApiKeyOrJwtAuthFilter.java`（`:25`）、`security/RequestContextFilter.java`（`:31-43`）
  - 配置：`application.yml`（model-router profiles `:133-151`、actuator 同端口）
  - 迁移：`V15__revoke_seeded_demo_api_keys.sql`（种子 key 已吊销，与脚本提示语对账）
- **关联报告**：`reports/audit-ReactAgentService.java.md`、`reports/audit-client.ts.md`、`reports/audit-api-recipes.md.md`、`reports/audit-ModelRouter.java.md`

## 概述（先说做对的部分）

1. **SSE 解析与后端线格式逐字吻合**：`ReactResponseFormatter.formatSse`（`:47-49`）产出 `event: X\ndata: Y\n\n`，经 WebFlux `Flux<String>` + `TEXT_EVENT_STREAM` 可能被二次包裹为 `data:event:/data:data:` 前缀——脚本 `stream_sse`（`e2e_chat_flow.py:83-97`）对两种布局都做了兼容，且 `data:event:` 的前缀判断顺序正确（先于 `data:`）。事件契约（`token`/`done`/`error`，`done` 携带完整 `ReactChatResponseVO`、`error` 携带 `{"message": ...}`）与 `ReactAgentService.java:216-232` 一致。
2. **凭证前置校验与数据库事实一致**：`e2e_chat_flow.py:114-118` 拒绝无凭证运行，报错文案"the seeded demo admin keys have been revoked"与 `V15` 迁移吊销种子 key 的事实对账——`reports/audit-api-recipes.md.md` 里文档类任务曾在这点上翻车，脚本是对的。
3. **端点与请求体字段完全正确**：`/ai/react/chat`、`/ai/react/chat/stream` 均为 POST（`ReactController.java:26,34`），`chatId/prompt/modelProfile` 与 `ReactChatRequestVO` 一致，`"balanced"` 是 `application.yml:142` 定义的合法 profile。
4. **权限路径选对了**：`SecurityConfiguration.java:58` 要求 `PERM_CHAT_WRITE` 或 `ROLE_ADMIN`，脚本用 API key / Bearer 均可满足；`/actuator/health` 在 `:51` permitAll 且与业务同端口（无 `management.server.port` 覆盖）。
5. **流式断言严格**：缺 done 事件报错（`:150-151`）、error 事件终止（`:148-149`）、"既无 token 又无 answer"报错（`:154-155`）——与 `audit-client.ts.md` 中前端流式解析"干净截断静默挂死"的缺陷相比，这个脚本把该坑填了。

## 高严重度

### H1. LLM 完全失败时 e2e 仍报 success：脚本不检查 fallback 标志

- **位置**：`scripts/e2e_chat_flow.py:137-141`（非流式断言）、`:152-155`（流式断言）；服务端依据 `ReactResponseFormatter.success`（`ReactResponseFormatter.java:28-33`，恒 `ok=1` + `fallback` 字段）与 `ReactAgentService.java:60-109`（`summarizeAnswer` 兜底路径）
- **描述**：服务端即使整个 React 循环 fallback（模型不可达、路由全链失败），`chat()` 仍返回 `ok=1` + 非空的兜底文案（如"（模型输出异常，请重试）"或降级答案），并把 `fallback=true` 放在响应里。脚本只断言 `ok == 1` 和 `answer` 非空——**不读 `fallback` 字段**。上游 LLM 凭据失效、模型路由整体不可用这种 e2e 最该抓住的故障，会被脚本判定为 "e2e chat flow success" 并以退出码 0 通过。
- **触发条件**：`E2E_API_KEY` 有效但上游模型凭据/网络故障；或 CI 中模型服务未就绪。
- **影响**：这是 e2e 门禁脚本最致命的失效模式——假绿灯。健康检查只证明进程活着，chat 断言又挡不住 fallback，脚本的实际覆盖从"全链路"缩水为"进程 + 数据库 + 权限链"。
- **修复建议**：在两处断言点读取 `fallback` 字段（非流式 `payload.get("fallback")`、流式 `done_payload.get("fallback")`）：为 True 时要么直接 `SystemExit`，要么打印醒目警告并输出 `routeReason`/`routeProfile` 帮助归因（降级为警告取决于 CI 语义，但绝不能静默）。纯脚本内改动，不涉及任何服务端 API 签名。

## 中严重度

### M1. 4xx/5xx 时服务端错误体被丢弃，401/403 只剩状态码

- **位置**：`scripts/e2e_chat_flow.py:165-166`（except 清单）+ `urllib.error.HTTPError` 语义；服务端对照 `GlobalExceptionHandler.java`（错误以 JSON 体返回）
- **描述**：`urlopen` 对非 2xx 直接抛 `HTTPError`，`stream_sse:63-64` 的 `resp.status != 200` 检查因此是死代码（根本到不了）。异常冒泡到 `__main__` 后只打印 `f"e2e chat flow failed: {exc}"`——`HTTPError` 是类文件对象，`e.read()` 里是服务端的具体诊断（权限不足、租户错误、prompt 校验失败的具体 message），全部丢弃。e2e 失败时最常见的排查动作"看响应体"要靠手工 curl 重放。
- **触发条件**：凭证过期/权限不符（401/403）、请求体被校验拒绝（400）——CI 里首次接入时的必经故障。
- **影响**：中低——不产生假结果，但显著拉长排障路径。
- **修复建议**：except 分支里对 `HTTPError` 读 body（`exc.read().decode(errors="replace")` 截断后附加到消息）；顺带删除 `stream_sse` 里的死代码或保留但注明其真实作用域（仅 2xx 非 200）。

### M2. `--tenant-id` 是一个静默无效的参数

- **位置**：`scripts/e2e_chat_flow.py:110,18-19`；服务端依据 `RequestContextFilter.java:31-43`
- **描述**：脚本强制要求凭证（`:114-118`），而 `RequestContextFilter` 明确注释"认证身份解析出的租户是权威的，X-Tenant-Id 头仅在无认证租户时被采纳"——有 API key / JWT 时头里的租户被忽略。所以 `--tenant-id` 在脚本所有可达路径上都是 no-op，但它在 `--help` 里呈现为有效参数。使用者会以为可以用它对第二个租户跑 e2e，实际请求仍落在凭证所属租户——更糟的是不会报错，两个"不同租户"的 e2e 结果无法区分。
- **触发条件**：多租户验证场景传入 `--tenant-id other-tenant`。
- **影响**：测试结果与使用者认知不符；这是脚本 CLI 语义与后端租户权威模型的错位。
- **修复建议**：最小改法是改 argparse 的 help 文案与注释，注明"仅当凭证无租户语义时生效，当前两类凭证都自带租户，此参数实际无效"；若需要真正多租户 e2e，应为每个租户各备一个 key（不改服务端）。

## 低严重度

### L1. `json.JSONDecodeError` 逃逸出异常清单，网关 200+HTML 时打出 traceback 而非干净报错

- **位置**：`scripts/e2e_chat_flow.py:29`（`json.loads`）与 `:165`（except 清单仅含 `HTTPError/URLError/TimeoutError/RuntimeError`）
- **描述**：`JSONDecodeError` 是 `ValueError` 子类，不在捕获清单里。当响应是 200 + 非 JSON（反向代理错误页、SPA 回退——`audit-client.ts.md` 中同源问题）时，脚本以完整 traceback 崩溃而非 "e2e chat flow failed: ..." 的干净退出。
- **触发条件**：base-url 指到了静态站点/网关错误页而非后端。
- **影响**：轻微；错误形态不友好但方向正确（非零退出）。
- **修复建议**：`ValueError` 加入 except 清单，或在 `http_json` 内包装为 `RuntimeError` 并附响应前 200 字符。

### L2. 非流式 40s 超时对 MAX_STEPS=4 的 React 循环偏紧，且不可配置

- **位置**：`scripts/e2e_chat_flow.py:134`（`timeout=40.0`）；服务端依据 `ReactAgentService.java:39`（`MAX_STEPS = 4`，最多 4 次 reason 往返 + 可能的 summarize）
- **描述**：非流式 chat 是同步等全量响应——慢模型/多步工具调用时整个循环逼近或超过 40s，urllib 抛 `TimeoutError`，e2e 假失败。timeout 数值写死，无法按环境放宽（CI 弱网更明显）。流式的 60s 是字节间隔上限（urllib timeout 是 per-socket-op），逐 token 下发下基本无虞——问题集中在非流式路径。
- **触发条件**：模型延迟 >40s（economy 档高峰期、reasoning 类模型误配到 balanced profile）。
- **影响**：低；假失败比假通过好，但会污染 CI 信号。
- **修复建议**：新增 `--timeout` 参数（新增 CLI 选项，不改动既有参数签名），默认值可保留 40s；或至少在注释中说明该值与 `MAX_STEPS` 的关系。

### L3. `stream_sse` 的 done 载荷 JSON 解析失败时错误信息误导

- **位置**：`scripts/e2e_chat_flow.py:44-47` + `:152-153`
- **描述**：`done` 事件数据非法 JSON 时走 `payload = {"raw": joined}`，随后 `done_payload.get("ok") != 1` 报 "stream done payload ok != 1"——真实原因是 JSON 解析失败，报错文案把它伪装成业务字段问题，排障方向被带偏。
- **触发条件**：服务端序列化异常返回 `{"message":"serialization_failed"}` 以外的畸形帧（`ReactResponseFormatter.java:51-57` 的兜底行为变更时）。
- **影响**：低。
- **修复建议**：`{"raw": ...}` 兜底分支里打标记，done 分支遇到 `raw` 键时报 "done payload is not JSON: <前缀>"。

## 修复优先级建议

1. **H1**：立即加 `fallback` 断言——一行读取 + 一个分支，把脚本从"假绿灯"变回真门禁，这是本次审查唯一影响测试有效性的缺陷。
2. **M1**：HTTPError 读 body，排障体验改善立竿见影。
3. **M2**：help 文案修正（防止多租户误用）。
4. **L1–L3**：合并为一次小的健壮性修订。

## 约束遵守声明

- 未修改任何源码、配置或本脚本本体；仅新增本报告文件。
- 未执行 git commit/push；未删除文件；未执行 npm install 或任何 build/mvn 构建。
- 修复建议均不改动既有 CLI 参数签名（M2/L2 的建议分别是文案修正与新增可选参数），也不涉及服务端任何对外 API 签名。

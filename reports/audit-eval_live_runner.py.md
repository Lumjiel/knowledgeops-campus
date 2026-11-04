# 审查报告：scripts/eval_live_runner.py

- **审查日期**：2026-11-04
- **审查方式**：只读通读 + 静态交叉审查（未执行脚本、未改动任何源码；结论全部以下钻协作者源码为据）
- **审查对象**：`scripts/eval_live_runner.py`（201 行，Live Quality Gate 的数据采集端——`evaluation/README.md:44-54` 文档化流水线 `eval_live_runner.py` → `run_regression.py --require-live-predictions`）
- **协作者清单**（均已逐一核对）：
  - `ReactController.java:32-38` — `POST /ai/react/chat/stream`，`produces=TEXT_EVENT_STREAM`，`Flux<String>`（即脚本注释 :111 所述 WebFlux 包裹布局的来源）；请求权限 `PERM_CHAT_WRITE`/`ROLE_ADMIN`（`SecurityConfiguration.java:58`）
  - `ReactChatRequestVO`（prompt/chatId/modelProfile）— 与脚本 body（:171-175）字段完全一致
  - `ReactAgentService.java` — SSE 事件 `token {token}`（:216）、`done {ReactChatResponseVO}`（:227）、`error {message}`（:232）；`conversationId` = `"react:" + chatId`（:402-405）→ `CONVERSATION_ID` advisor（:398）→ ChatMemory；`usedFallback` 贯穿决策与回答两级（:65,:77-78,:136）并写入 done 响应的 `fallback` 字段（:111,:227）
  - `ReactResponseFormatter.java:47-49` — `formatSse` 产出 `event: X\ndata: Y\n\n` 原始布局
  - `ReactChatResponseVO`（answer/citations List\<String\>/fallback/ok/msg/trace）、`ReactTraceStepVO`（observation 为 Object）、`ReactDecisionParser.java:212`（`decision.citations()` 为 `List<String>`）
  - 消费方 `run_regression.py` — `normalize_prediction`（:44-69）字段名逐一对上；`SUCCESS_STATUSES = {"ok","success","done"}`（:9）；`--require-live-predictions` 只校验 `prediction_source == "live_api"`（:268-276）；`--first-token-max-ms 4000`（:260）作用于 `first_token_latency_ms` 样本 P95
  - `V15__revoke_seeded_demo_api_keys.sql` — 吊销明文 `dev-admin-key-2026`
  - `ApiKeyOrJwtAuthFilter.java:48,66` + `RequestContextFilter.java:33-43` — 已认证身份的租户权威，`X-Tenant-ID` header 仅在无认证身份时生效
  - `evaluation/dataset.json`（4 项，含固定 chatId）、`evaluation/dataset.large.json`（240 项裸数组）；无 Makefile 接线，调用入口只有文档
  - 姊妹脚本 `e2e_chat_flow.py:108-117`（已要求显式凭据）、`eval_demo.py`（同批未修复，见 `audit-eval_demo.py.md`）
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、未变更任何对外 API 签名
- **关联报告**：`audit-eval_demo.py.md`（同批脚本，多处同根因发现）、`audit-e2e_chat_flow.py.md`、`audit-ReactAgentService.java.md`

## 概述

先说做对的部分——这个脚本的整体质量高于同批的 `eval_demo.py`：

1. **SSE 双布局解析正确且注释了依据**（:111-122）：先匹配长前缀 `data:event:`/`data:data:` 再匹配 `event:`/`data:`，与 `ReactResponseFormatter.formatSse` 原始布局经 WebFlux `Flux<String>` 包裹后的实际线上格式吻合（`audit-e2e_chat_flow.py.md` 中"前缀判断要先长后短"的要点此处做对了）。
2. **`TimeoutError` 在捕获清单内**（:179）：socket 读超时可被逐 case 捕获记录，不会像 `eval_demo.py` 那样裸 traceback——这点修得对。
3. **与消费方的字段契约完全对齐**：输出字段（id/prediction_source/status/answer/citations/error/first_token_latency_ms/total_latency_ms）与 `run_regression.py::normalize_prediction` 逐字段匹配；`prediction_source="live_api"`（:189）正是 `--require-live-predictions` 的校验值。
4. **单 case 失败不中断批处理**（:177-191）：HTTPError/URLError/TimeoutError/RuntimeError 逐 case 落记录为 `status=error`，回归阶段按 `failure-max-rate` 门槛拦截。
5. **citations 提取类型假设正确**：`done.citations` 与 `trace[*].observation.citations` 均为 `List<String>`（`ReactChatResponseVO`、`ReactDecisionParser.java:212`），:22-55 的 `str(item)` 不会产生 dict repr 垃圾；且做了去重保序。
6. 事件名（token/done/error）、请求字段、`Accept: text/event-stream` 均与服务端一致。

问题按严重度列出如下。

## 高严重度

### H1. 默认 API key 是已被 V15 吊销的公开凭据——文档化的 Live Quality Gate 开箱全 401

- **位置**：`scripts/eval_live_runner.py:157`
- **描述**：`--api-key` 兜底默认 `"dev-admin-key-2026"`，而 `V15__revoke_seeded_demo_api_keys.sql:2-17` 已将该"提交进仓库、等同于公开"的种子 key 吊销。`POST /ai/react/chat/stream` 要求 `PERM_CHAT_WRITE` 或 `ROLE_ADMIN`（`SecurityConfiguration.java:58`），无匿名通道。姊妹脚本 `e2e_chat_flow.py:108-117` 已改为"缺显式凭据即报错退出"，本脚本未跟进（`eval_demo.py:63` 同病，见 `audit-eval_demo.py.md` H1）。
- **触发条件**：不设 `E2E_API_KEY` 直接按 `evaluation/README.md:44-54` 的流水线执行。
- **影响**：每个 case 的 urlopen 抛 `HTTPError 401`，被 :179 捕获后逐 case 记为 `status=error`，`predictions.live.json` 全量 error。回归门槛（`--failure-max-rate 0.10`）确实会拦下，但根因（凭据）只能从 `"HTTP Error 401"` 这个无 body 的字符串猜（见 M1）。
- **修复建议**：与 `e2e_chat_flow.py` 对齐——默认 `os.getenv("E2E_API_KEY")`（无兜底值），bearer 与 api-key 均缺失时 `SystemExit` 提示"种子 demo key 已被 V15 吊销，需显式凭据"。同时排查 `eval_demo.py:63`。纯脚本改动，不涉及对外 API 签名。

### H2. 丢弃 done 响应的 `fallback`/`ok` 字段——降级答案与真实答案在质量门禁中不可区分（假绿灯通道）

- **位置**：`scripts/eval_live_runner.py:126-146`（`stream_chat` 返回值仅含 6 个字段，done payload 中其余字段全部丢弃）；对照 `ReactAgentService.java:65,:77-78,:111,:227`（`usedFallback` 聚合后写入 `ReactChatResponseVO.fallback`）与 `ReactChatResponseVO`（`fallback`/`ok`/`msg` 字段存在）
- **描述**：服务端在决策解析失败、检索/LLM 降级时会置 `fallback=true` 并下发兜底文案（服务端语义即"恒 ok=1 + fallback 标记的降级响应"，与 `audit-e2e_chat_flow.py.md` 的头号发现同源）。脚本对 `fallback` 视而不见：降级回答照样得到 `status=ok` + 非空 answer + `prediction_source=live_api`，而下游 `run_regression.py` 恰恰**只**依赖这三个东西打分——它无法知道答案来自真实 RAG 链路还是兜底文案。
- **触发条件**：上游 LLM 或检索链路部分降级（模型路由 fallback、决策解析 fallback）。若兜底文案恰好命中个别 expected_keywords，对应 case 计为通过。
- **影响**：Live Quality Gate 的核心语义被架空——"真实回答质量达标"与"兜底文案蒙对关键词"在报告里同一待遇。这正是本脚本作为门禁数据源的存在意义所在。
- **修复建议**：在 `stream_chat` 返回值与 prediction 中透传 `fallback`（`done_payload.get("fallback")`）与 `ok` 字段；至少打印汇总（"N/M 条为降级回答"）。是否将 fallback 计为失败应由下游决定（部分降级场景 fallback=true 但回答仍真实可用），但**采集端必须先把字段留下来**。不改后端。

### H3. 固定 chatId → 对话记忆跨 run 持久污染，回归分数不可比

- **位置**：`scripts/eval_live_runner.py:173`（`case.get("chatId", f"eval-live-{idx:03d}")`——两条路径都是稳定值）；失效链：dataset case 自带 chatId（`evaluation/dataset.json` 全部 4 条均带）或脚本按序号生成的确定性缺省 → `ReactAgentService.conversationId`（`ReactAgentService.java:402-405`，`"react:" + chatId`）→ `CONVERSATION_ID` advisor（:398）→ ChatMemory 历史持久化
- **描述**：同一 chatId 的会话历史跨 run 累积：第 N 次跑回归时，每个 case 的提问都带着前 N-1 次的问答上下文进模型。与 `audit-eval_demo.py.md` M2 同根因，但此处后果更重——这是质量门禁的数据源，门禁的前提是"同一数据集两次跑分的差异反映代码变更的影响"，会话污染直接破坏该前提。
- **触发条件**：对同一数据集的第二次及以后回归。
- **影响**：回归分数漂移不可归因；修了检索 bug 后分数可能因历史上下文"变好/变坏"，掩盖或夸大真实变化。
- **修复建议**：根治在服务端（eval 路径应隔离会话或不带记忆），脚本侧可先行规避：把 chatId 统一替换为带本次 run 时间戳的前缀（如 `f"eval-live-{int(time.time())}-{idx:03d}"`），并覆盖 case 自带 chatId（或新增 `--fresh-chat` 开关默认开启）。注意覆盖 case chatId 会改变"多轮追问类 case"的语义，若有此类 case 需保留原 chatId——当前 `evaluation/dataset.json` 各 case chatId 互不相同且无追问链，覆盖安全。

## 中严重度

### M1. HTTPError 响应体丢弃，服务端诊断不可见

- **位置**：`scripts/eval_live_runner.py:179-187`（`error: str(exc)`）
- **描述**：`HTTPError` 的 `e.read()` 从未调用，`str(exc)` 只有 `"HTTP Error 401"` 这类一行字。服务端校验失败的具体原因（权限名、参数错误消息）在响应 body 里，全部丢失。
- **触发条件**：任何 4xx/5xx——最常见即 H1 的 401。
- **影响**：H1 发生时用户面对 240 条 `"HTTP Error 401"` 无法区分"key 无效/被吊销"还是"key 有效但无 PERM_CHAT_WRITE"，排障方向被带偏。
- **修复建议**：`isinstance(exc, urllib.error.HTTPError)` 分支读 body（截断到几百字符）并入 error 字段。注意 401 时 body 可能含敏感信息，落盘前酌情保留 message 字段即可。

### M2. `--tenant-id` 在 API key 路径下是静默 no-op

- **位置**：`scripts/eval_live_runner.py:17-18`；协作者证据：`ApiKeyOrJwtAuthFilter.java:48,66`、`RequestContextFilter.java:33-43`
- **描述**：与 `audit-eval_demo.py.md` M1 同根因：带 `X-API-Key`（或 Bearer）认证时，认证身份的租户权威，`X-Tenant-ID` header 被服务端忽略。本脚本默认 `--tenant-id=""`（不发 header），但用户显式传入时同样静默无效。
- **触发条件**：同时提供凭据与 `--tenant-id`。
- **影响**：用户以为在评目标租户的数据，实际跑在 key 所属租户上。
- **修复建议**：凭据存在且 tenant_id 非空时打印警告"租户跟随认证身份，header 将被忽略"；或直接移除该参数。

### M3. 数据集格式不容错：dict 包装直接 TypeError，非 dict 条目 AttributeError

- **位置**：`scripts/eval_live_runner.py:162-164`（`dataset[: args.limit]` 假设裸 list）、:170-173（`case.get(...)` 假设元素是 dict）
- **描述**：`load` 后直接切片与 `.get`，无任何形态归一。而姊妹脚本 `eval_demo.py:51-55` 对 `{"cases": [...]}` 包装与非 dict 条目都做了兼容——同一目录、面向同一份 `evaluation/` 数据的两个脚本行为漂移。当前 `dataset.json`/`dataset.large.json` 均为裸数组所以今天能跑，但 `generate_eval_dataset.py` 一旦改输出包装形态（或用户手写包装文件）即崩。
- **触发条件**：数据集为 `{"cases": [...]}` 包装，或数组中混入非对象条目。
- **影响**：`TypeError: unhashable type: 'slice'` / `AttributeError: 'str' object has no attribute 'get'` 裸 traceback。
- **修复建议**：抽 3 行做归一（`data.get("cases", data) if isinstance(data, dict) else data` + 过滤非 dict 并计数警告），或直接复用 `eval_demo.py::load_cases` 的逻辑。

## 低严重度

### L1. done 无 token 时把总延迟记作首 token 延迟，污染 P95 门禁样本

- **位置**：`scripts/eval_live_runner.py:95-96`（done 事件补记 `first_token_ms` = 已流逝总时长）
- **描述**：服务端确实保证 done 前必有 token（`ReactAgentService.java:210-227` tokenFlux 先于 done concat），但空回答/异常收尾路径可能零 token 直达 done，此时 `first_token_latency_ms` 记成总延迟。该字段被 `run_regression.py --first-token-max-ms 4000`（:260,:172-176）纳入 P95 门槛样本。
- **触发条件**：零 token 的 done（如防泄漏缓冲路径整体替换后异常收尾、空回答）。
- **影响**：个别极大的"伪首 token"样本抬高 P95，可能让本应通过的延迟门禁误报；这些 case 同时也因无答案计为失败，双重信号混淆归因。
- **修复建议**：零 token 的 done 不补记 `first_token_ms`（保持 None），让 `run_regression.py` 的空值跳过逻辑（:151）自然排除。

### L2. case 缺 `id` 时预测记录 id 为空串，消费方按 id 折叠

- **位置**：`scripts/eval_live_runner.py:170`（`case.get("id", "")`）；消费方 `run_regression.py:282-284` 以 `str(normalized.get("id",""))` 建 map
- **描述**：数据集缺 id（或 id 重复）时，空串 id 的预测在回归侧互相覆盖、且与 dataset 侧 `case.get("id","")` 的空串 case 对齐方式脆弱。
- **触发条件**：手工编辑的数据集漏写 id，或 id 重复。
- **影响**：静默错配——case A 的预测可能被 case B 覆盖，回归结果失真无告警。
- **修复建议**：id 缺失或重复时退出报错（数据集是静态标注集，宁可拒绝不可错配）；或以 `case-<idx>` 兜底并在输出中保留原序。

### L3. bearer 与 api-key 同时给出时 api-key 被静默丢弃

- **位置**：`scripts/eval_live_runner.py:13-16`（`if bearer ... elif api_key`）
- **描述**：两者互斥无警告。传了两个凭据的用户无从知道实际生效的是哪个。
- **触发条件**：同时传 `--bearer-token` 与 `--api-key`（常见于从环境变量继承了一个又显式传了另一个）。
- **影响**：轻微，但与 M2 同属"静默忽略用户输入"的模式。
- **修复建议**：两者同时存在时打印一行说明优先级。

### L4. `--dataset`/`--output` 相对路径依赖 CWD

- **位置**：`scripts/eval_live_runner.py:151-152,194`
- **描述**：与 `audit-eval_demo.py.md` L4 同款：相对路径只在仓库根运行时正确；文档示例（`evaluation/README.md:44`）恰好从仓库根跑所以未暴露。
- **触发条件**：从其他目录按文档命令运行。
- **影响**：`FileNotFoundError` 裸 traceback，或预测文件写到意外位置后回归读的是旧文件。
- **修复建议**：以 `Path(__file__).resolve().parent.parent` 锚定默认基准目录。

## 修复优先级建议

1. **H1**（默认凭据已吊销）——与 `eval_demo.py:63` 一并修，改动最小、直接决定文档流水线能否开箱跑通。
2. **H2**（透传 fallback/ok）——质量门禁语义的关键补丁，采集端加两个字段即可，消费端可后续跟进。
3. **H3**（会话隔离）——需决定 chatId 策略（时间戳前缀 vs 服务端 eval 路径隔离），建议与 `audit-eval_demo.py.md` M2 立同一个项。
4. **M1、M2、M3**——同一次"错误处理与输入归一"清理中顺手完成。
5. **L1-L4**——随任一次顺手修。

以上修复建议均不要求变更任何对外 API 签名；H1/H2/M1/M3/L1-L4 可仅在 `scripts/eval_live_runner.py` 内完成，H3/M2 视选择可能触及 `evaluation/dataset.json`（可选）或仅打印警告。

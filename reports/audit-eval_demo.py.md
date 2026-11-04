# 审查报告：scripts/eval_demo.py

- **审查日期**：2026-11-04
- **审查方式**：只读通读 + 静态交叉审查（未执行脚本、未改动任何源码；结论全部以下钻协作者源码为据）
- **审查对象**：`scripts/eval_demo.py`（103 行，`make eval-demo` 的入口脚本）
- **协作者清单**（均已逐一核对）：
  - `EvaluationController.java` — `POST /ai/evaluation/datasets`（:28）、`POST /datasets/{datasetId}/runs`（:38）、`GET /runs/{runId}/report`（:67，`produces=text/markdown;charset=UTF-8`）
  - `EvaluationService.java` — `triggerRun` 为**同步内联执行**（:104-156，逐 case 调 LLM 后才返回）；`createDataset` 无 `@Transactional`（:47-95）；`resolveChatId` 优先 case 自带 chatId（:370-378）
  - `vo/EvalDatasetVO` / `EvalRunVO` / `EvalCaseCreateVO` — 裸 VO 返回，`datasetId`/`runId` 直取；case 字段名与脚本 payload 完全一致
  - `V15__revoke_seeded_demo_api_keys.sql` — 吊销了明文 `dev-admin-key-2026` 的种子 key
  - `ApiKeyOrJwtAuthFilter.java:48,66` + `RequestContextFilter.java:33-43` — 已认证身份的租户权威，`X-Tenant-ID` header 仅在无认证身份时生效
  - `HybridRagAnswerService.java:90`（CONVERSATION_ID advisor 参数）+ `MysqlChatMemory.java:52,86` — 对话历史持久化于 MySQL
  - `Makefile:15-16` — `eval-demo: python3 scripts/eval_demo.py`
  - `evaluation/dataset.json` — 裸数组、snake_case `expected_keywords`、每 case 带 chatId
  - 姊妹脚本 `e2e_chat_flow.py:108-117` — 已要求显式凭据并提示种子 key 已吊销
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、未变更任何对外 API 签名
- **关联报告**：`audit-e2e_chat_flow.py.md`（同类脚本，凭据缺陷已在此修复）、`audit-EvaluationService.java.md`、`audit-EvaluationPanel.vue.md`

## 概述

先说做对的部分：

1. **数据集格式兼容正确**：`load_cases`（:51-55）同时接受裸数组与 `{"cases": [...]}` 包装，字段 snake_case/camelCase 双兼容——与仓库 `evaluation/dataset.json`（裸数组 + `expected_keywords`）实际形态吻合。
2. **空数据集提前失败**：cases 为空时 `SystemExit`（:71-72），不会打到后端吃一个 400（"dataset cases are required"）。
3. **报告端点匹配**：`request_text` 的 `Accept: text/markdown` 与 `EvaluationController.java:67` 的 `produces` 一致；响应是裸字符串，按文本读取正确。
4. **响应形态核对正确**：`dataset['datasetId']`、`run['runId']`（:85,:90,:94-95）——后端是裸 VO 无 `Result{ok,msg}` 包装，直取键没有误判响应风格。
5. **取报告时机不是缺陷**：`triggerRun` 是同步内联执行（`EvaluationService.java:137-155` 逐 case 跑完才返回），所以 :90 紧接着取 report 不会读到 RUNNING 态——这一点与前端 `EvaluationPanel.vue` 的异步轮询路径不同，看似可疑实际成立。
6. 输出目录 `mkdir(parents=True, exist_ok=True)`（:92）处理妥当。

问题按严重度列出如下。

## 高严重度

### H1. 默认 API key 是已被 V15 吊销的公开凭据——`make eval-demo` 开箱必 401

- **位置**：`scripts/eval_demo.py:63`
- **描述**：`--api-key` 的兜底默认值是 `"dev-admin-key-2026"`。`V15__revoke_seeded_demo_api_keys.sql:2-17` 已明确将该明文"已提交进仓库、等同于公开"的种子 key 吊销（`enabled=0, revoked_at=NOW()`）。而 `POST /ai/evaluation/**` 要求 `PERM_EVAL_WRITE` 或 `ROLE_ADMIN`（`SecurityConfiguration.java:62`），无匿名通道。
- **触发条件**：用户不设 `E2E_API_KEY` 环境变量、直接跑 `make eval-demo`（Makefile:16 也不传参）。
- **影响**：三个请求全部 401，脚本只报 `evaluation API is unavailable: HTTP Error 401`（见 L7，诊断还被误导为"服务不可用"）。demo 开箱即坏。
- **修复建议**：与已修复的姊妹脚本 `e2e_chat_flow.py:108-117` 对齐——默认改为 `os.getenv("E2E_API_KEY")`（无兜底值），缺失时 `SystemExit` 提示"显式凭据必需，种子 demo key 已被 V15 吊销"。顺带排查同目录 `eval_live_runner.py:157` 存在同样问题。纯脚本改动，不涉及任何对外 API 签名。

### H2. 同步全量评测撞上 120 秒 socket 超时，且超时异常不在捕获清单内

- **位置**：`scripts/eval_demo.py:84-89`（`timeout=120` 默认值来自 :10,:16）；根因链在 `EvaluationService.java:137-155`（同步逐 case 调 `HybridRagAnswerService.answer` → LLM）
- **描述**：`POST /datasets/{id}/runs` 在服务端内联跑完全部 case 才返回响应，期间无任何字节流动。urllib 的 `timeout` 是 per-socket-op，对"发完请求、干等完整响应"的场景就是总等待上限。同时，读响应阶段的超时抛出的是 `socket.timeout`（`TimeoutError`，`OSError` 子类），**不是** `urllib.error.URLError`——:102 的 `except urllib.error.URLError` 接不住。
- **触发条件**：case 数 × 单 case LLM 延迟超过 120 秒（`dataset.large.json` 这类数据集、或上游 LLM 慢时，极易触发）。
- **影响**：裸 traceback 崩溃；服务端 run 不受影响继续执行并落库，但客户端已丢失 `runId`，报告永远取不回来，白烧一遍 LLM 费用。
- **修复建议**：① run 触发请求的 timeout 独立可配（`--run-timeout`）并给足余量；② 顶层捕获放宽为 `except (urllib.error.URLError, OSError)`，或单独接 `TimeoutError`；③ 更彻底的做法是缩短服务端等待诉求——但后端 `triggerRun` 本身就是同步契约，脚本侧先把超时调对即可。

### H3. 对评测结果零断言——全部 case 失败也以 exit 0 "成功"收场（假绿灯）

- **位置**：`scripts/eval_demo.py:90-96`（取完报告只打印路径即返回）；根因佐证：`EvaluationService.java:143` run 级 status 恒置 `"SUCCESS"`（per-case 失败只体现在 `metrics.failureRate`/result.status 里）
- **描述**：脚本对 `run` 的 `metrics.failureRate`、`runScore`、`passedCases` 不做任何检查，也无 `--fail-on-failure` 之类的开关；Makefile 的 `eval-demo` 目标因此永远绿。
- **触发条件**：知识库为空、LLM 全挂、检索降级——任何导致全部 case `FAILED` 的场景。注意 run 级 status 仍是 `SUCCESS`（它只表示"运行执行完了"），脚本即使想查 status 也会被这个语义骗到。
- **影响**：与 `audit-e2e_chat_flow.py.md` 的头号发现同款——演示/回归入口给出"成功"假象，退化只有在人工打开报告时才被发现。
- **修复建议**：至少打印 `failureRate`/`passedCases/totalCases` 摘要；建议增加 `--fail-on-failure`（默认关，保持 demo 语义），开启时 `failureRate > 0` 则 `SystemExit(1)`。断言对象应取 `run['metrics']['failureRate']` 而非 `run['status']`。

## 中严重度

### M1. `--tenant-id` 在默认路径下是静默 no-op

- **位置**：`scripts/eval_demo.py:31-32,64`；协作者证据：`ApiKeyOrJwtAuthFilter.java:48,66`（认证身份的租户写入 `TENANT_REQUEST_ATTRIBUTE`）、`RequestContextFilter.java:33-43`（已认证身份权威，header 仅在无认证身份时生效）
- **描述**：脚本默认总带 `X-API-Key`，此时 `X-Tenant-ID` header 被服务端忽略，实际租户永远是 key 所属租户。`--tenant-id`（默认 `public`）给用户"可以指定被评租户"的错觉。
- **触发条件**：任何带 API key 的调用——即默认的全部路径。
- **影响**：用户传 `--tenant-id acme` 评的其实是 key 的租户 `public` 的数据，评测结论张冠李戴且无任何警告。
- **修复建议**：要么删掉该参数与 header（诚实暴露"租户跟随 key"），要么在两者不一致时打印警告说明 header 将被忽略。不改后端。

### M2. 数据集 chatId 固定 → 对话记忆跨 run 持久污染，评测分不可比

- **位置**：`scripts/eval_demo.py:87`（`chatIdPrefix: "eval-demo"`）；失效链：`evaluation/dataset.json` 每 case 自带 chatId → `EvaluationService.resolveChatId`（`EvaluationService.java:371-372`，case.chatId 优先于前缀）→ `ConversationIdHelper.build("eval", chatId)`（:224）→ `HybridRagAnswerService.java:90`（CONVERSATION_ID advisor 参数）→ `MysqlChatMemory.java:52`（历史持久化在 MySQL）
- **描述**：`chatIdPrefix` 只对无 chatId 的 case 生效，而仓库自带数据集的 4 个 case 全部带固定 chatId（`eval-rag-a` 等）。同一 conversation 的历史跨 run、甚至跨服务重启持续累积（MySQL 持久化），第 N 次评测时 LLM 带着前 N-1 次的问答上下文作答。
- **触发条件**：对同一数据集执行第二次 `make eval-demo` 即开始。
- **影响**：评测分数不可比、不可复现——本脚本的唯一存在意义就是产出可比的分数；`compareLatest` 的基线对比随之失真。
- **修复建议**：根治在服务端（eval 会话应每 run 唯一，或 eval 路径走 `MysqlChatMemory.clear`），但脚本侧可先行规避：不传 case 自带 chatId 之外的稳定值时，至少在文档/输出中提示；或数据集侧把 chatId 留空让其落入 `chatIdPrefix + 序号`（仍跨 run 重复，需前缀带时间戳——脚本可生成 `eval-demo-<utcnow>` 作前缀）。

### M3. 每次运行新建同名数据集，且平台无任何删除路径——永久堆积

- **位置**：`scripts/eval_demo.py:74-83`（固定 `name: "RAG Evaluation Studio Demo"`）；协作者证据：`EvaluationController.java` 全部端点无 DELETE；`EvalDatasetMapper`/`EvalCaseMapper` 无 delete 方法；`eval_case` 有唯一键（`V13__rag_evaluation_studio.sql:28`）但不同 datasetId 之间互不冲突
- **描述**：脚本从不查询已有数据集（`GET /datasets` 端点存在而未用），每次 demo 都插入新 dataset + N 条 case。服务端没有删除 API，堆积只能靠手工清库。
- **触发条件**：第二次及以后的 `make eval-demo`。
- **影响**：`eval_dataset`/`eval_case` 无限增长；评测工作台的数据集列表被同名条目淹没；每次全量重新入库也拖慢 demo。
- **修复建议**：先 `GET /ai/evaluation/datasets` 按 name 匹配复用 `datasetId`，命中则跳过建库直接触发 run；未命中再创建。纯脚本改动。

### M4. HTTPError 诊断信息被丢弃，4xx 被误述为"服务不可用"

- **位置**：`scripts/eval_demo.py:102-103`
- **描述**：`HTTPError` 是 `URLError` 子类，会被 :102 捕获，但 `e.read()`（服务端校验消息所在的响应体）从未读取；同时 400/401/404（参数错、凭据错、路径错）与真正的连接失败被统一表述为 `evaluation API is unavailable`，语义错误。触发条件示例：数据集里混入空 question 的 case 虽被脚本预过滤，但重复 `caseId` 会命中 `eval_case` 唯一键（`V13:28`），叠加 `createDataset` 无 `@Transactional`（`EvaluationService.java:47-95`）→ 500 + 半截数据集残留，而用户看到的仍是"unavailable"。
- **触发条件**：任何 4xx/5xx 响应。
- **影响**：排障方向被带偏（去查服务是否在线，实际是参数/凭据/数据问题）；上述半截数据集残留还会污染后续运行。
- **修复建议**：拆分 `except urllib.error.HTTPError` 分支，读 body 并原样打印服务端消息（本项目裸 VO 风格，错误体通常是 `{"timestamp":...,"message":...}` 或纯文本），再保留 `URLError` 分支处理连接类失败。

## 低严重度

### L1. `normalize_case` 遇到显式 `null` 字段崩 TypeError

- **位置**：`scripts/eval_demo.py:37-39`
- **描述**：`raw.get("expectedKeywords", raw.get("expected_keywords", []))` 的默认值只在**键不存在**时生效；若数据集写了 `"expectedKeywords": null`，得到 `None`，:45 的列表推导即 `TypeError`。
- **触发条件**：数据集字段显式为 null。
- **影响**：裸 traceback。
- **修复建议**：`raw.get(...) or []` 包一层。

### L2. 静默丢弃非 dict 项与空 question，数据问题无提示

- **位置**：`scripts/eval_demo.py:54-55`
- **描述**：`isinstance(item, dict)` 过滤和 `case["question"]` 过滤都是静默的，源文件损坏/字段名打错（如 `query`）时 case 被悄悄丢光，最后只在完全为空时才报"no cases"。
- **触发条件**：数据集含格式错误条目。
- **影响**：评测覆盖面缩水而用户以为全部执行；部分丢弃时无任何日志。
- **修复建议**：丢弃时打印 `stderr` 警告并计数。

### L3. `JSONDecodeError` 与 `KeyError` 未捕获

- **位置**：`scripts/eval_demo.py:18,85,90,94-95`
- **描述**：代理/SPA 回退返回 HTML 时 `json.loads` 抛 `JSONDecodeError`；响应形态意外时 `dataset['datasetId']` 抛 `KeyError`——都不在 :102 的捕获范围，裸 traceback。`JSONDecodeError` 是 `ValueError` 子类，与 `audit-e2e_chat_flow.py.md` 的同款发现一致。
- **触发条件**：8080 被其他服务/前端代理占用。
- **影响**：排障体验差（真实响应内容不可见）。
- **修复建议**：并入顶层 `except (URLError, OSError, ValueError, KeyError)` 或在 `request_json` 内校验响应 Content-Type。

### L4. `--dataset`/`--output` 相对路径依赖 CWD

- **位置**：`scripts/eval_demo.py:61-62,91-93`
- **描述**：默认值 `evaluation/dataset.json`、`evaluation/reports/latest-evaluation-report.md` 是相对路径，只有从仓库根运行（Makefile 场景）才正确；从别处运行时找不到数据集或把报告写到意外位置。
- **触发条件**：手动在非仓库根目录运行脚本。
- **影响**：轻微，报错信息（`FileNotFoundError` 裸 traceback）也不够友好。
- **修复建议**：以脚本文件位置锚定仓库根（`Path(__file__).resolve().parent.parent`）作为默认基准。

## 修复优先级建议

1. **H1**（默认凭据已吊销，demo 开箱即坏）——改动最小、收益直接，与 `eval_live_runner.py` 一并修。
2. **H2**（超时模型错误 + 异常漏接）——数据集一大必炸，且烧真实 LLM 费用。
3. **H3**（假绿灯）——加摘要打印与可选失败退出码。
4. **M3**（数据集复用）+ **M4**（错误诊断）——同一次改动顺手完成（都会碰请求封装）。
5. **M1、M2**——一个是诚实话术问题，一个需与 EvaluationService 侧协调（eval 会话隔离），可单独立项。
6. **L1-L4**——随任一次顺手修。

以上修复建议均不要求变更任何对外 API 签名；H1/H3/M3/M4/L1-L4 全部可仅在 `scripts/eval_demo.py` 内完成。

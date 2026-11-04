# 架构分析报告：eval_demo.py 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码与脚本；不运行任何 build/mvn；全部结论来自静态交叉审查，每条结论附脚本行号与代码位置证据）
- **分析对象**：`scripts/eval_demo.py`（104 行：request_json/request_text :10-24 + auth_headers :27-33 + case 归一化 :36-55 + main :58-96 + 异常翻译层 :99-103）
- **核查范围**：脚本「依赖面」= 四类契约逐条对码——①评测端点契约：`POST /ai/evaluation/datasets`（EvaluationController.java:28-31）、`POST /ai/evaluation/datasets/{id}/runs`（:38-42）、`GET /ai/evaluation/runs/{runId}/report`（:67-74，produces text/markdown）+ 权限（SecurityConfiguration.java:61-62 PERM_EVAL_WRITE/ROLE_ADMIN）+ 请求/响应 VO 全字段（EvalDatasetCreateVO/EvalCaseCreateVO/EvalRunRequestVO/EvalRunVO/EvalDatasetVO）；②执行模型：EvaluationService.triggerRun 同步内联（evaluation 包 grep 零 @Async/@Scheduled/线程池）+ status 语义（:121 RUNNING/:143 SUCCESS/:212-235）+ resolveChatId 优先级链（:370-376）+ exportReport 无状态守卫（:201-203）；③凭证契约：V15 吊销 SQL 全文（`V15__revoke_seeded_demo_api_keys.sql`）+ ApiKeyOrJwtAuthFilter.java:25 + RequestContextFilter.java:33-42 租户权威序；④接线面：Makefile:16（eval-demo 目标）+ 数据集实况（evaluation/dataset.json 顶层 list、4/4 case 自带 chatId，-I 统计）+ 同批脚本横向（e2e_chat_flow.py / eval_live_runner.py）
- **约束遵守**：未改源码与脚本、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（所有修复建议均为脚本侧编辑）
- **关联报告**：`audit-eval_demo.py.md`（同文件缺陷审查一手记录：凭据默认值、timeout 语义、chatId 污染）；`arch-e2e_chat_flow.py.md`（同批脚本 arch，H-1/M-1 与本报告互证）；`audit-eval_live_runner.py.md`（同批姊妹脚本的采集端发现）；`arch-EvaluationService.java.md`（triggerRun 同步内联执行模型、run 行僵尸 RUNNING、resolveChatId 兜底链的架构定性）；`audit-api-recipes.md.md`（V15 吊销种子 key 的事实来源）

## 1. 模块定位

本脚本是**评测子系统的演示管线（make eval-demo 入口）**：读本地数据集 → 建数据集 → 触发评测 run → 拉取 markdown 报告落盘，三跳完成一次端到端评测演示。它不是门禁——**全程零断言**，交付物是 `evaluation/reports/latest-evaluation-report.md` 文件本身。

**架构定性（本报告主线）**：与 e2e_chat_flow（黑盒门禁）不同，本脚本的依赖面有一个**结构性杠杆点**：它的三跳全部落在 evaluation 封闭子系统内（EvaluationController → EvaluationService → HybridRagAnswerService），而该子系统的执行模型是**HTTP 请求线程内联逐 case 跑 LLM**（arch-EvaluationService 头号发现）——这意味着脚本的依赖方向图里藏着一条最重的隐性耦合：`POST .../runs` 的返回时间 = 全部 case 的 LLM 延迟总和，脚本的 timeout 参数实际是**评测总预算**而非单请求超时。端点契约本身对码全真（端点/方法/VO 字段/produce 类型逐参成立），漂移集中在凭证链（H-1）、执行模型适配（M-2）与数据集生命周期（M-3）。

## 2. 依赖全景

### 2.1 出边对码表（脚本对评测子系统的三跳契约）

| 脚本行为 | 位置 | 核实结果 |
| --- | --- | --- |
| POST /ai/evaluation/datasets，body={name,description,cases}（:74-83） | EvaluationController:28-31 + EvalDatasetCreateVO{name,description,cases:List\<EvalCaseCreateVO\>} + SecurityConfiguration:62（PERM_EVAL_WRITE/ROLE_ADMIN） | ✅ 逐参对码成立；case 七字段（caseId/category/chatId/question/expectedKeywords/expectedCitations/forbiddenKeywords）与 EvalCaseCreateVO **逐字全真** |
| case 归一化双键名兼容 camelCase/snake_case（:37-39） | 本地数据集实况（dataset.json 用 snake_case：expected_keywords 等，-I 统计 4 case） | ✅ 兼容层真实必要——平台 VO 是 camelCase，仓库数据集是 snake_case，脚本正确承担了形态转换职责 |
| load_cases 双容器兼容：顶层 list 或 {cases:[...]}（:53） | dataset.json 顶层 list | ✅ **同批唯一**做了 dict 包装兼容的脚本（eval_live_runner 直接切片，遇 {cases} 包装会 TypeError——audit-eval_live_runner 已记）——本脚本在此项上是**权威副本** |
| POST /datasets/{id}/runs，body={modelProfile,chatIdPrefix}（:84-89） | EvaluationController:38-42 + EvalRunRequestVO{datasetId,modelProfile,chatIdPrefix} | ⚠️ 字段合法；**chatIdPrefix 对默认数据集是死参数**：dataset.json 4/4 case 自带 chatId，resolveChatId:371-373 case.chatId 优先 → 前缀永不被消费（6.4 M-4） |
| run 载荷只取 runId（:85/:90），不检查 status/metrics | EvalRunVO{runId,status,metrics,results,...} + EvaluationService:143 status 恒 "SUCCESS" | ⚠️ 零断言设计（脚本定位如此），但意味着 failureRate=1.0 的 run 照样退出码 0（6.2 M-2） |
| timeout=120（request_json 默认 :10） | triggerRun 同步内联逐 case 跑 LLM（evaluation 包零异步设施，grep 证实） | ❌ **执行模型错配**：120s 是全部 case 的总预算而非单请求超时；读响应阶段 socket.timeout 不被 ：102 的 URLError 捕获 → 裸 traceback（6.3 M-2） |
| GET /runs/{runId}/report，Accept text/markdown（:90） | EvaluationController:67-74 produces="text/markdown;charset=UTF-8" | ✅ 方法/Accept/produce 全对；exportReport（:201-203）无 run 状态守卫，RUNNING/FAILED 状态的 run 也能出报告——与零断言设计叠加，报告可能是半程快照 |
| X-API-Key 认证（:30） | ApiKeyOrJwtAuthFilter:25 | ✅ 头名对码 |
| X-Tenant-ID 旗标（:31-32，默认 public） | RequestContextFilter:33-42 | ❌ **幽灵参数**：API key 认证后认证身份租户权威，header 恒被忽略（6.5 L-1） |
| 凭据默认 `dev-admin-key-2026`（:63） | V15__revoke_seeded_demo_api_keys.sql 全文 | ❌ **默认凭据已被吊销**：V15 对 key_name='demo-admin-key-2026' 置 enabled=0 + revoked_at——默认路径必 401（6.1 H-1） |
| 异常翻译仅捕 URLError（:102） | HTTPError 是 URLError 子类 | ⚠️ 401 会被翻译成 "evaluation API is unavailable"——**认证失败被误报为服务不可用**，与 H-1 叠加后默认路径的报错把人引向查服务而非换凭据（6.1 H-1） |

### 2.2 入边与数据面

| 项 | 实况 |
| --- | --- |
| `Makefile:16`（eval-demo 目标：`python3 scripts/eval_demo.py`，零参数） | ✅ 唯一仓库内调用方——零参数意味着**完全依赖脚本内默认值**，H-1 的默认凭据经此直达 |
| 数据集 `evaluation/dataset.json`（默认 --dataset） | 顶层 list、4 case、全部自带 chatId（-I 实测） |
| 输出 `evaluation/reports/latest-evaluation-report.md`（:62） | 每次覆盖写；平台侧无对应读取方（报告的下游是人，非 CI——四个 workflow 零引用本脚本，与 e2e_chat_flow M-2 同型） |
| CI | 无任何 workflow 引用 eval_demo；nightly-regression 走 generate_eval_dataset → contract fixture → run_regression 链，与本脚本无交集 |

## 3. 循环依赖评估

**结论：不适用，单向干净；等价风险面在同批横向复制。** 脚本 → 评测 HTTP 面单向，evaluation 子系统无任何反向感知（grep 全仓：本脚本仅 Makefile 一处引用）。横向风险两处：

1. **凭证姿势漂移已物化**：e2e_chat_flow:114-118 已改为强制显式凭据（报错文案自带吊销背景），eval_demo:63 与 eval_live_runner 仍兜底 `dev-admin-key-2026`——「同批脚本对齐 E2E_API_KEY 环境变量名但未对齐默认值策略」是三份脚本间的真实漂移谱（eval_demo 独有的 dict 兼容能力则反向领先）。
2. **normalize_case 是平台 VO 的影子副本**：case 七字段键名在脚本（双键名兼容）与平台 VO（camelCase）与仓库数据集（snake_case）三处平行，任何一方加字段（如 run_regression 的 prediction_source 对齐需求）都需人工三方同步——无共享 schema 的结构代价。

## 4. 越层调用评估

**结论：黑盒直连是评测演示的职能，但脚本承担了一层本应属于平台的职责。** 需要记录的边界事实：**数据集生命周期管理被完全外包给脚本侧默认行为**——每次 `make eval-demo` 无条件 POST 新建同名数据集 "RAG Evaluation Studio Demo"（:74-83），而平台 `EvaluationController` **没有 DELETE 端点**（:28-74 全文核实），mapper 亦无 delete（arch-EvaluationService 已记）→ 每次演示永久堆积一个含 4 case 的数据集，且 comparison（compareLatest）与 GET /datasets 列表随之膨胀。修复所需的一切平台能力都已存在（GET /datasets :33-36 可按 name 复用），脚本未用——这是「调用方旁路了服务端已有的幂等路径」的典型越层形态：不是脚本越了平台的层，而是脚本把平台该有的幂等语义自己没接上。

## 5. 平行实现核查

| 能力 | eval_demo.py | e2e_chat_flow.py | eval_live_runner.py |
| --- | --- | --- | --- |
| 凭据策略 | ❌ 兜底已吊销 key（:63） | ✅ 强制显式（:114-118） | ❌（audit-eval_live_runner） |
| 容器形态兼容（list vs {cases}） | ✅（:53） | —（无数据集） | ❌ 直接切片会 TypeError |
| done/报告载荷语义断言 | ❌ 零断言 | ❌ fallback 未消费（H-1） | ❌ fallback 未消费（头号发现） |
| 超时语义适配同步端点 | ❌ 120s 当单请求超时 | ✅ 分层 timeout（40/60s） | ✅ TimeoutError 在捕获清单 |
| 租户头 | ❌ 幽灵参数 | ❌ 幽灵参数（M-3） | — |

**结构性结论**：三脚本无任何共享模块（stdlib-only 约定），每行能力都是独立副本——本报告 §2.1/§3/§5 的每一项「❌」都能在同批找到正确实现，反之亦然。**批内不存在一个全面正确的参照实现**：凭证看 e2e_chat_flow，容器兼容看 eval_demo，超时/捕获清单看 eval_live_runner。修复策略应是按能力指认权威副本互相抄齐，而非各修各的。

## 6. 发现明细

### 6.1 H-1 默认凭据指向 V15 已吊销的种子 key，且异常翻译把后果伪装成服务不可用

- **脚本位置**：:63（`--api-key` 默认 `os.getenv("E2E_API_KEY", "dev-admin-key-2026")`）、:102-103
- **证据**：V15 全文（`UPDATE api_keys SET enabled=0 ... WHERE key_name IN ('demo-admin-key-2026', ...)`，revoked_reason 明文「publicly committed seed credential」）；Makefile:16 零参数调用 → 默认值直达；HTTPError ⊂ URLError → 401 被翻译为 "evaluation API is unavailable: HTTP Error 401"。
- **影响**：`make eval-demo` 开箱必败，且报错把用户引向排查服务可用性（health/端口）而非凭据问题。这是 audit 报告头号发现的架构定性：**脚本的可运行性依赖一个仓库已自行宣告失效的凭据**，与姊妹脚本 e2e_chat_flow 的修复（:114-118）构成同批漂移的物化现场。
- **修复建议**（脚本侧，零 API 变更）：照抄 e2e_chat_flow :114-118 的强制显式模式（去掉默认值，缺失时报错并说明吊销背景）；异常翻译层对 HTTPError 区分 401/403（"credential rejected"）与连接类（"unavailable"）。

### 6.2 M-2 执行模型错配：同步内联 run + 120s 总预算 + 零断言 = 慢败与败报都不被看见

- **脚本位置**：:10（timeout=120 默认）、:84-96（run 后零检查）
- **证据**：triggerRun 在 HTTP 线程内联逐 case 跑 LLM（EvaluationService:205-235，evaluation 包零异步设施）；run 级 status 恒 SUCCESS（:143，只表示执行完），case 级失败体现在 metrics.failureRate（:286）与 results[].status；exportReport 无状态守卫（:201-203）。
- **影响**：①dataset.json 仅 4 case 时 120s 大概率够用，但脚本参数化了 --dataset——换大集（如 dataset.large.json 240 case）后必超时，且读响应阶段的 socket.timeout 不在 :102 捕获清单 → 裸 traceback 而非友好退出；②零断言 + status 恒 SUCCESS → 全部 case 失败的 run 照样 print 三行成功文案、退出码 0——`make eval-demo` 作为平台自检入口的信号价值为零（假绿灯，与 e2e_chat_flow H-1 同族但根因是「不消费」而非「消费了弱字段」）。
- **修复建议**：run 响应后检查 `metrics.failureRate`（>0 时以非零码退出或醒目告警）；timeout 与 case 数关联（或至少 help 文案标明这是总预算）。

### 6.3 M-3 数据集生命周期旁路：每次运行永久新建同名数据集，平台无 DELETE 可回收

- **脚本位置**：:74-83（无条件 POST "RAG Evaluation Studio Demo"）
- **证据**：EvaluationController 全文无 DELETE 映射（:28-74）；GET /datasets（:33-36）与 compareLatest（:52-55）存在——前者是现成的复用路径；arch-EvaluationService 已记 mapper 无 delete。
- **影响**：每次 `make eval-demo` 堆积一个数据集 + 4 case + 一条 run 行；GET /datasets 列表与 comparison 基线候选随之膨胀；共享演示环境（同一 tenant）多人跑 demo 时同名数据集无限增殖。
- **修复建议**（脚本侧）：运行前 GET /datasets 按 name 查找复用（命中则跳过创建），这同时是 eval_live_runner 等后续脚本的样板。

### 6.4 M-4 chatIdPrefix 死参数 + 默认数据集全 case 自带 chatId → 会话记忆跨 run 污染评测分

- **脚本位置**：:87（`"chatIdPrefix": "eval-demo"`）
- **证据**：dataset.json 4/4 case 自带 chatId（-I 实测）；resolveChatId:371-373 case.chatId 优先于 request.chatIdPrefix；chatId 经 ConversationIdHelper → MysqlChatMemory（MySQL 持久化）跨 run 存活。
- **影响**：脚本的「会话隔离」意图（前缀）对默认数据集完全不生效；第二次 `make eval-demo` 的每个 case 都带着上一次 run 的对话记忆作答——评测分不可归因（audit-eval_demo 与 arch-EvaluationService 的 resolveChatId 发现在此脚本的落地现场）。
- **修复建议**：脚本侧对本仓库数据集无解（chatId 在数据集文件里）——正确修复是数据集文件去掉 chatId 字段（snake_case 化时一并处理），脚本侧可在文档/help 注明「case 自带 chatId 时前缀无效且记忆跨 run 复用」。

### 6.5 L-1 --tenant-id 幽灵参数；L-2 报告文件零下游

- **L-1 证据**：:31-32/:64 发送 X-Tenant-ID，但 API key 认证后 RequestContextFilter:33-42 认证租户权威——header 恒被忽略（与 e2e_chat_flow M-3 同型同根）。
- **L-2 证据**：输出文件 :62 每次覆盖，无 CI/脚本消费（四 workflow 零引用）；报告的下游只有人。
- **修复建议**：删除 tenant 旗标或标注恒 no-op；输出文件无动作必要（demo 定位）。

### 6.6 正面项清单（保留项）

1. 端点/方法/VO 字段/produce 类型**逐参对码全真**——三跳契约无一处虚构或漂移（对照 api-recipes 文档的方法断裂）。
2. case 双键名兼容 + 双容器兼容——脚本正确承担了「仓库数据集 snake_case ↔ 平台 VO camelCase」的形态转换，且是同批唯一做容器兼容的脚本（权威副本）。
3. 依赖面最小化：三跳、零外部依赖、零断言的 demo 定位自洽。
4. 输出落盘带父目录创建（:92），失败模式（除 M-2 的超时外）均响亮。

## 7. 修复优先级建议

| 优先级 | 项 | 动作 |
| --- | --- | --- |
| 1 | H-1 | 凭据强制显式（照抄 e2e_chat_flow :114-118）+ HTTPError 分型报错 |
| 2 | M-2 | run 后断言 metrics.failureRate + timeout 语义标注 |
| 3 | M-3 | GET /datasets 按 name 复用，停止无条件新建 |
| 4 | M-4 | 数据集文件去 chatId 或脚本侧标注前缀失效语义 |
| 5 | L-1/L-2 | tenant 旗标删除/标注；报告文件无需动作 |

全部修复为脚本与数据集侧编辑，不触碰平台代码与对外 API 签名。脚本的三跳契约本身零漂移——它的全部架构问题都在**默认值层**（凭据、超时、新建策略）与**响应语义零消费**，即「demo 定位」与「make 自检入口」两个角色的错配：作为前者它合格，作为 Makefile 收录的平台入口它需要 H-1/M-2 的补全。

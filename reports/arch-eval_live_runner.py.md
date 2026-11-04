# 架构分析报告：eval_live_runner.py 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码与脚本；不运行任何 build/mvn；全部结论来自静态交叉审查，每条结论附脚本行号与代码位置证据）
- **分析对象**：`scripts/eval_live_runner.py`（201 行：make_headers :11-19 + extract_citations :22-55 + stream_chat :58-146 + main 采集循环 :149-196）
- **核查范围**：脚本「依赖面」= 四类契约逐条对码——①采集端点：`POST /ai/react/chat/stream`（ReactController.java:35-42 + SecurityConfiguration.java:58 PERM_CHAT_WRITE/ROLE_ADMIN）+ SSE 线格式（ReactResponseFormatter.java:47-49 formatSse + ReactAgentService.java:172/216/227/232 trace/token/done/error 发射面）+ done 载荷全字段（ReactChatResponseVO：ok/msg/answer/fallback/citations/trace…十五字段）；②输出契约：predictions.live.json 八字段对 `run_regression.normalize_prediction`（run_regression.py:41-66 全读）+ `--require-live-predictions` 的 `prediction_source=="live_api"` 校验（:268/:286-290）；③门禁接线：operations.md:68（quality gate runbook）+ generate_eval_contract_fixture.py:5（docstring 指认）+ 四个 GitHub workflow 零引用 grep；④平台侧对照管线：EvaluationService.triggerRun（:219-225 走 HybridRagAnswerService 非流式 RAG 链）与本脚本（走 ReactAgentService 流式 ReAct 链）的分野；同批脚本横向（e2e_chat_flow.py / eval_demo.py）
- **约束遵守**：未改源码与脚本、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（所有修复建议均为脚本侧编辑）
- **关联报告**：`audit-eval_live_runner.py.md`（同文件缺陷审查一手记录：fallback 丢弃头号发现、SSE/超时做对的清单）；`arch-eval_demo.py.md`（同批姊妹脚本，容器兼容/凭据漂移互证）；`arch-e2e_chat_flow.py.md`（同批，SSE 解析器双副本另一份）；`arch-EvaluationService.java.md`（平台侧评测管线全貌、「evaluation 只测 RAG 链」的能力空白——本报告 M-2 的另一半）；`arch-ReactAgentService.java.md`（ReAct 链 fallback 聚合与 stream/chat 失败语义分叉）；`audit-generate_eval_contract_fixture.py.md`（防滥用三道防线的原文现场）

## 1. 模块定位

本脚本是**脚本侧活体质量门禁的采集端**：读本地数据集 → 逐 case 打 `/ai/react/chat/stream` → 把每 case 的 status/answer/citations/延迟写成 `predictions.live.json`，下游 `run_regression.py --require-live-predictions` 消费该文件打分出报告。它与 eval_demo 一样**不是门禁本身**——门禁逻辑全在 run_regression，脚本的职责边界是「把真实 LLM 交互采集为可打分的预测文件」。

**架构定性（本报告主线）**：平台存在**两条互不相交的评测管线**，本脚本是其中一条的唯一采集入口——

```
管线 A（平台内）：eval_demo.py → EvaluationService.triggerRun → HybridRagAnswerService（非流式 RAG 链）
                  → run 行 + markdown 报告（平台持久化、有基线/对比）
管线 B（脚本侧）：eval_live_runner.py → /ai/react/chat/stream → ReactAgentService（流式 ReAct 链）
                  → predictions.live.json（扁平文件、无 run 行/基线）→ run_regression 质量门禁
```

两条管线**测的不是同一条作答链**：A 测 RAG 混合链，B 测 ReAct 流式链——arch-EvaluationService 已记「evaluation 只测 RAG 链，ReAct 两链路无评测入口」，本脚本正是该空白的事实补位者：ReAct 链的全部质量度量都在平台评测子系统**之外**完成，产物不落平台任何表。这个定位解释了本报告的头号发现为何结构性致命：管线 B 的质量信号全靠采集端的忠实转录，而采集端恰恰丢弃了降级标记（H-1）。

## 2. 依赖全景

### 2.1 出边对码表（脚本对平台的采集契约）

| 脚本行为 | 位置 | 核实结果 |
| --- | --- | --- |
| POST /ai/react/chat/stream，Accept: text/event-stream（:12/:59-60） | ReactController:35-42（Flux\<String\>）+ SecurityConfiguration:58 | ✅ 方法/头/produce 全对；**单端点依赖**——脚本只碰这一个平台入口 |
| 请求 body={prompt,chatId,modelProfile}（:171-175） | ReactChatRequestVO 三字段 | ✅ 逐参对码；chatId 兜底 `eval-live-{idx:03d}` 但默认数据集 4/4 case 自带 chatId → 兜底不可达（6.4 M-3） |
| SSE 解析先长后短（:113-122）+ 双布局注释（:111-112） | 服务端 formatSse + WebFlux 包裹 | ✅ 与 e2e_chat_flow:87-97 逐行级同构（双副本，5 章对比表）；对 e2e_chat_flow 的差异：**事件分发移入空行边界处理**（:81-105）而非 consume_event 闭包——等价但结构不同 |
| done 载荷消费：answer + citations + trace[].observation.citations（:127-131/:22-55） | ReactChatResponseVO 全十五字段 | ❌ **消费 4/15 且恰好跳过 ok 与 fallback**——done_payload 在场即 status="ok"（:132-133），服务端 success() 恒 ok=1（ReactResponseFormatter:28-33）→ 降级回答与真实回答在输出文件中不可区分（6.1 H-1） |
| 超时/网络异常 per-case 隔离（:177-187） | — | ✅ **同批最佳设计**：单 case 失败写成 error 记录、循环继续，采集不被单点打断（e2e_chat_flow 是 fail-fast，对本场景正确）；TimeoutError 在捕获清单（:179）✅ |
| 输出八字段（:189-191） | run_regression.normalize_prediction:41-66 | ✅ **逐字段全对齐**：id/prediction_source/status/answer/error/citations/first_token_latency_ms/total_latency_ms 一一被下游消费；`live_api` 字面量与 run_regression:286 的校验值精确匹配 |
| extract_citations 读 done.citations + trace[].observation.citations（:27-46） | ReactResponseFormatter.success:26（citations=extractTraceStrings(trace,"citations")）+ ReactAgentService observation map | ⚠️ 采集端直读 trace 内部观测结构——比端点契约深一层的耦合（6.5 L-2）；isinstance 防御使漂移时静默得空列表而非报错 |
| X-API-Key / Bearer 双通道（:13-16） | ApiKeyOrJwtAuthFilter:25/:79-81 | ✅ 同 e2e_chat_flow 的双通道互斥；**eval_demo 无 bearer 支持**——本脚本在认证面上是权威副本 |
| X-Tenant-ID 旗标（:17-18） | RequestContextFilter:33-42 | ❌ 幽灵参数（强制/默认凭据下认证租户权威，header 恒被忽略）——三脚本同款（6.6 L-1） |
| 凭据默认 `dev-admin-key-2026`（:157） | V15__revoke_seeded_demo_api_keys.sql | ❌ 默认凭据已被吊销——与 eval_demo 同款 H 级问题（6.2 M-1） |

### 2.2 入边（谁消费这个脚本的产物）

| 消费方 | 实况 |
| --- | --- |
| `run_regression.py --require-live-predictions`（operations.md:68 记载的门禁命令） | ✅ 唯一结构化下游；:286-290 校验 prediction_source==live_api，缺 live 预测即拒绝出报告——**防「拿夹具数据冒充实测」的三道防线之一**（audit-generate_eval_contract_fixture 已记） |
| `generate_eval_contract_fixture.py:5` docstring | ✅ 指认本脚本为 live 质量门禁的采集端（文档级锚链） |
| CI | ❌ 四 workflow 零引用——quality gate 是 runbook 手动链路（与 e2e_chat_flow M-2、eval_demo L-2 同型：平台三个脚本侧检查器全部无自动化消费方） |

## 3. 循环依赖评估

**结论：不适用且比同批更干净。** 脚本 → 单端点 → 扁平文件 → run_regression，全链单向无回边。与 eval_demo 的结构性差异：本脚本**不创建任何服务端资源**（无数据集 POST）——它对平台是纯只读式采集（唯一副作用是 chatId 写入 MysqlChatMemory 的会话记忆，见 M-3），无生命周期管理责任，也就无 eval_demo M-3 那类堆积问题。等价风险面仍是横向复制：SSE 解析器与 e2e_chat_flow 双副本（当前语义一致），normalize_case 式的 schema 影子在本脚本不存在（它不做键名归一，直接 `case.get(...)` 原样转发——把形态转换责任留给了数据集文件与平台默认值，是同批最薄的一层，代价见 M-4）。

## 4. 越层调用评估

**结论：无越层，但耦合深度是同批之最——采集端钻进了 trace 内部结构。** 三层递进的耦合事实：

1. 端点层（正常）：`/ai/react/chat/stream` 是对外 API，黑盒消费合法。
2. **done 载荷层（边界）**：ReactChatResponseVO 的 trace 字段是**诊断结构**（ReactTraceStepVO 列表，服务端观测用），extract_citations 直读 `trace[].observation.citations`（:34-46）——observation 是 ReactAgentService 的内部观测 map（:81 `observation.put("fallback", ...)` 等），其 schema 无对外稳定性承诺。对照 e2e_chat_flow（刻意忽略 trace）与 client.ts 前端（经 CitationService 收敛的 citationCards 消费），本脚本是全库唯一钻到 observation 层的消费方。
3. **静默降级形态**：耦合无 isinstance 之外的版本协商——服务端若重命名 observation 键或改 trace 结构，脚本不报错，citations 静默变空列表 → run_regression 的 citation 命中分静默归零。越层不成立（无更浅的替代路径——done.citations 与 trace 提取本就同源，ReactResponseFormatter:26），但**失败模式是静默的**，与脚本其余部分的响亮失败纪律相悖。

## 5. 平行实现核查

三脚本能力矩阵（收束 arch-eval_demo §5 的结论）：

| 能力 | eval_live_runner.py | e2e_chat_flow.py | eval_demo.py |
| --- | --- | --- | --- |
| 凭据策略 | ❌ 兜底已吊销 key（:157） | ✅ 强制显式 | ❌ 同左 |
| Bearer + API key 双通道 | ✅（:13-16） | ✅ | ❌ 仅 API key |
| SSE 双布局解析 | ✅（:113-122，副本 2） | ✅（:87-97，副本 1） | — |
| TimeoutError 捕获 | ✅（:179） | ✅ | ❌ |
| per-case 失败隔离 | ✅（:177-187） | ❌ fail-fast | ❌ 单跳即整链断 |
| 数据集容器兼容 | ❌ 直接切片/迭代（:162-169，dict 包装即 TypeError） | — | ✅（:53，权威副本） |
| done 载荷 fallback/ok 消费 | ❌（H-1） | ❌（H-1） | —（不碰流式） |
| 与下游的字段契约对齐 | ✅ normalize_prediction 八字段全对 | —（无下游） | —（报告无下游） |

**结构性结论（本报告对同批矩阵的最终裁定）**：批内依旧不存在全面正确的参照实现，但本脚本承担的管线（B）对**忠实转录**的要求最高——它的每个输出字段都是质量门禁的打分输入——因此「采集端丢字段」在本脚本的危害等级高于姊妹脚本的同款欠消费（eval_demo 的报告至少把全量 case 结果渲染给人看，人眼能发现异常；predictions.live.json 后面只有算术）。fallback 丢弃定 H 而非 M 的依据即在此。

## 6. 发现明细

### 6.1 H-1 采集端丢弃 done 载荷的 fallback/ok 字段：质量门禁的假绿灯通道在采集端

- **脚本位置**：:127-135（answer 提取 + status 判定）、:189-191（输出装配）
- **证据**：服务端降级链路全程聚合 usedFallback 并落 VO（ReactAgentService:61/:65/:77-78/:95/:111 → ReactResponseFormatter.success:33），且 success() 恒 ok=1；done_payload.isinstance 判定通过即 status="ok"（:132-133），fallback 与 ok 两个字段在 :127-146 全程无读取；下游 run_regression.normalize_prediction（:41-66）消费的字段集里同样没有 fallback——**丢弃发生在采集端，下游结构上无从补救**。
- **影响**：LLM 全挂时每 case 的降级回答（非空兜底文案 + citations 可能为空但 answer 非空）被采集为 status=ok 的 live 预测 → run_regression 照常打分出报告 → 质量门禁假绿。predictions 文件里没有 fallback 维度，事后审计也无法区分真绿假绿——**这是管线 B 特有的结构性弱点**：管线 A 的 markdown 报告至少逐 case 可见，管线 B 的中间产物只保留打分所需字段，丢弃即永久丢失。
- **修复建议**（脚本侧，零 API 变更）：result 字典增加 `"fallback": bool(done_payload.get("fallback"))` 一字段采集（run_regression 侧同步透传可选，不接也能先落文件留证）——与 arch-e2e_chat_flow H-1 的断言补全是同一字段、两侧防线。

### 6.2 M-1 凭据默认值指向 V15 已吊销种子 key（同批漂移第三处物化点）

- **脚本位置**：:157
- **证据**：V15 SQL（key_name='demo-admin-key-2026' 置 enabled=0）；e2e_chat_flow:114-118 已修复，eval_demo:63 与本脚本未跟进。
- **影响**：默认路径逐 case 401 → 全部采集为 error 记录 → predictions.live.json 全错记录 → run_regression 出全红报告。与 eval_demo 不同（401 被伪装成 unavailable），本脚本 401 是 HTTPError ⊂ URLError 被 :179 捕获进 result.error——**失败被如实采集但同样全程静默跑完**（print 每行 `[n/m] id: error`），运行者看到的是「评测全失败」而非「凭据失效」，诊断指向偏移。
- **修复建议**：照抄 e2e_chat_flow 强制显式模式；或启动前先打一次低成本探测（如 GET /ai/evaluation/datasets）把 401 提前为启动失败。

### 6.3 M-2 两条评测管线测两条不同作答链：ReAct 链的质量度量完全在平台子系统之外

- **证据**：管线 A 测 HybridRagAnswerService（EvaluationService:220），管线 B 测 ReactAgentService（本脚本 :59）；ReAct 链无平台内评测入口（arch-EvaluationService 能力空白发现）；predictions.live.json 无平台持久化（无 run 行、无 baseline、无 comparison）。
- **影响**：①两套质量数字（平台报告 vs 质量门禁报告）**不可比也不应比**，但文档层未声明分野——operations.md §6 把两者并置于同一节，读者会当作同一质量的两种视图；②ReAct 链是生产主链路（/ai/react/chat + /chat/stream），其质量门禁却依赖手动 runbook + 扁平 JSON，无历史趋势、无基线回归对比（管线 A 的 markBaseline/compareLatest 能力对 B 侧为零）。
- **修复建议**（记录项）：短期在 operations.md §6 明写「两条管线测两条链，数字不可互比」；长期方向是平台评测子系统开放 ReAct 链入口，让 run_regression 消费平台 run 而非扁平文件——此为未来项，不要求本次改动。

### 6.4 M-3 会话记忆跨 run 污染：默认数据集全 case 自带 chatId，质量门禁分数不可归因

- **脚本位置**：:173（case.get("chatId", 兜底)）
- **证据**：dataset.json 4/4 case 自带 chatId（-I 实测）；chatId 经 MysqlChatMemory（MySQL 持久化）跨 run 存活；管线 B 场景下同一数据集反复跑门禁 = 每 case 带着全部历史 run 的对话记忆作答。
- **影响**：比 eval_demo（M-4）更重——eval_demo 污染的是演示分，本脚本污染的是**门禁回归分**：分数波动无法区分「模型/提示词变化」与「会话记忆累积」，门禁的判定力被结构性削弱。
- **修复建议**：脚本侧可做而数据集侧更根本——脚本把 chatId 重写为 `f"{case_chat_id}-r{int(time.time())}"`（保序保隔离）；数据集侧去 chatId 字段是根治（与 arch-eval_demo M-4 同一修复现场）。

### 6.5 M-4 数据集容器形态不兼容：dict 包装即 TypeError（同批唯一未做兼容的采集端）

- **脚本位置**：:162-164（`dataset[: args.limit]` 直接切片）、:169（直接迭代）
- **证据**：eval_demo.py:53 有双容器兼容（权威副本）；仓库当前 dataset.json 是顶层 list 所以默认路径可用，但 `{cases:[...]}` 形态的数据集（eval_demo 明确支持的输入）传给本脚本：带 --limit 时 TypeError，不带时迭代出字符串键再 AttributeError。
- **影响**：两个脚本对「合法数据集」的定义不一致——同一文件 eval_demo 能跑、本脚本崩；且崩在采集第 0 步，报错形态是 traceback 而非指引。
- **修复建议**：从 eval_demo 抄 `data if isinstance(data, list) else data.get("cases", [])` 一行级修复，顺带对齐 case 归一（本脚本现不区分 expected/forbidden 字段，只取 question/chatId/id——这是合理的采集极简，无需对齐）。

### 6.6 L 级项

- **L-1 X-Tenant-ID 幽灵参数**（:17-18）：与两个姊妹脚本同款同根（RequestContextFilter:33-42 认证租户权威），修复同批统一处理。
- **L-2 trace 内部结构耦合 + 服务端提取逻辑影子**（:22-55）：extract_citations 是 ReactResponseFormatter.extractTraceStrings 的消费侧影子副本，且耦合 observation 内部键名；漂移时静默空列表（§4）。缓解：done.citations 直读分支（:27-33）是主路径且来自 VO 稳定字段，trace 分支实际是兜底补充——风险有界。
- **L-3 case_id 可为空串**（:170，数据集无 id 字段时 `""`）：下游 normalize_prediction 按 id 映射（:286 pred_map 键），空 id 会让多 case 互相覆盖——当前数据集全有 id，记录为边界。

### 6.7 正面项清单（保留项）

1. **per-case 失败隔离**（:177-187）——同批最佳设计：采集循环对单点故障免疫，error 也入档（信息不丢）。
2. 输出契约与 run_regression.normalize_prediction **八字段逐一对齐**——同批唯一有结构化下游且契约精确的脚本。
3. SSE 双布局先长后短正确 + 注释说明包裹成因；TimeoutError 在捕获清单。
4. `ensure_ascii=False`（:195）——中文答案在预测文件中保真。
5. 依赖面最小：单端点、零服务端资源创建、纯采集职责——三脚本中职责边界最清晰的一份。

## 7. 修复优先级建议

| 优先级 | 项 | 动作 |
| --- | --- | --- |
| 1 | H-1 | result 增加 fallback 字段采集（一行级；run_regression 侧透传为可选加固） |
| 2 | M-1 | 凭据强制显式（照抄 e2e_chat_flow）+ 启动前 401 前置探测 |
| 3 | M-3 | chatId 加 run 后缀防跨 run 记忆污染（或数据集去 chatId） |
| 4 | M-4 | 数据集容器兼容（抄 eval_demo :53） |
| 5 | M-2 | operations.md §6 声明两管线两链不可比（文档侧） |
| 6 | L-1/2/3 | tenant 旗标批内统一处理；trace 耦合加注释指认脆弱键；空 id 防御 |

全部修复为脚本/文档侧编辑，不触碰平台代码与对外 API 签名。本脚本是同批三份中职责最纯、与下游契约最齐的一份——它的 H 级问题（fallback 丢弃）不是实现疏忽而是**采集 schema 设计缺失**：predictions.live.json 从定稿起就没有降级维度，修 H-1 实质是给管线 B 的中间产物补第一个质量语义字段。

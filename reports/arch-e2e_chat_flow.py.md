# 架构分析报告：e2e_chat_flow.py 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码与脚本；不运行任何 build/mvn；全部结论来自静态交叉审查，每条结论附脚本行号与代码位置证据）
- **分析对象**：`scripts/e2e_chat_flow.py`（167 行：headers 构造 :10-20 + http_json :23-29 + stream_sse :32-102 + main :105-159 + 异常翻译层 :162-166）
- **核查范围**：脚本「依赖面」= 三类契约逐条对码——①端点契约：`/actuator/health`（SecurityConfiguration.java:51 permitAll）、`POST /ai/react/chat` 与 `/ai/react/chat/stream`（ReactController.java:28-42 + SecurityConfiguration.java:58 PERM_CHAT_WRITE/ROLE_ADMIN）+ 请求 VO 三字段（ReactChatRequestVO prompt/chatId/modelProfile）+ 响应 VO 十五字段（ReactChatResponseVO 全文）+ modelProfile 默认值（ModelRouterProperties.java:13 defaultProfile="balanced"）；②认证契约：X-API-Key/Authorization Bearer（ApiKeyOrJwtAuthFilter.java:25/:79-81）+ 租户头权威序（RequestContextFilter.java:33-42）；③SSE 线格式：formatSse（ReactResponseFormatter.java:47-49）+ 事件发射面（ReactAgentService.java:172/216/227/232 trace/token/done/error）+ done 载荷聚合（ReactAgentService.java:95/:111 usedFallback）；④接线面：全仓引用 grep（demo.sh:142、java-parity-report.md:33、.github/workflows 四文件零命中）+ 凭证供给链（demo.sh DEMO_ENV_FILE :8/:25、.env.example 全文）+ 同批脚本横向（eval_demo.py / eval_live_runner.py SSE 解析对比）
- **约束遵守**：未改源码与脚本、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（所有修复建议均为脚本/调用方侧编辑）
- **关联报告**：`audit-e2e_chat_flow.py.md`（同文件缺陷审查一手记录：假绿灯、urllib 陷阱清单、SSE 双布局）；`audit-eval_live_runner.py.md`（同批脚本，头号发现「采集端丢弃 done payload 的 fallback 字段」与本报告 H-1 互为印证）；`arch-client.ts.md`（响应信封三形态 {ok,msg}/裸 VO/markdown 的全貌）；`arch-api-recipes.md.md`（凭证链断裂合流模式）；`audit-api-recipes.md.md`（V15 吊销种子 key 的事实来源）

## 1. 模块定位

本脚本是**平台的端到端冒烟门禁（黑盒消费端）**：以 stdlib-only 单文件（167 行）串起 health → 非流式 chat → 流式 chat/stream 三跳，产出「平台核心对话链路可用」的布尔绿灯。

**架构定性（本报告主线）**：脚本与平台之间**没有任何代码级依赖**——纯 HTTP 黑盒，依赖方向严格单向（脚本 → HTTP 面 → 平台），零循环、零项目内 import（`requirements.txt` 明文「No external dependencies」，是刻意约定而非疏漏）。它的全部架构问题都在**依赖面的完整性**上：门禁的「依赖面」不只是端点存在性（这部分全真，是脚本质量最好的部分），还包括**响应语义的充分消费**与**自身绿灯的被消费链**——后者两处缺口（H-1 欠消费 fallback 字段、M-2 无任何自动化消费其绿灯）使「门禁」实为「可手动触发的检查器」。

## 2. 依赖全景

### 2.1 出边对码表（脚本对平台的三跳契约）

| 脚本行为 | 位置 | 核实结果 |
| --- | --- | --- |
| GET /actuator/health，断言 status==UP（:122-126） | SecurityConfiguration:51 permitAll + actuator 标准返回 | ✅ 方法/路径/响应形态全对 |
| POST /ai/react/chat，body={chatId,prompt,modelProfile}（:128-136） | ReactController:28-33 + ReactChatRequestVO 三字段 + SecurityConfiguration:58（PERM_CHAT_WRITE/ROLE_ADMIN） | ✅ 逐参对码成立；modelProfile 默认 "balanced" = ModelRouterProperties:13 出厂默认 ✅ |
| 断言 ok==1 + answer 非空（:137-140） | ReactController 返回**裸 ReactChatResponseVO**（非 Result 信封） | ⚠️ 字段消费 2/15：ok/answer 检查正确，**fallback 字段（VO 真实存在，ReactResponseFormatter.success:33 赋值）未消费**（6.1 H-1） |
| POST /ai/react/chat/stream，Accept: text/event-stream（:143-146） | ReactController:35-42 产 Flux\<String\>，元素 = formatSse（"event: X\ndata: Y\n\n"，ReactResponseFormatter:47-49） | ✅ 方法/头/produce 类型全对 |
| SSE 解析：先判 data:event:/data:data: 再判 event:/data:（:87-97） | 服务端原始格式 + Spring WebFlux 逐行再包裹产生双布局 | ✅ **前缀先长后短正确**（与 eval_live_runner:113-118 同序）；注释 :83-86 说明了包裹成因，是脚本内最好的自文档段 |
| 消费 token/done/error 三事件（:49-59） | ReactAgentService 发射面：trace :172 / token :216 / done :227 / error :232 | ✅ 四事件消费三、刻意忽略 trace 合理；done 载荷 = 完整 ReactChatResponseVO JSON |
| 凭证强制：bearer 或 api-key 二选一（:114-118） | ApiKeyOrJwtAuthFilter:25（X-API-Key）/:79-81（Bearer 前缀） | ✅ 头名与语义逐字对码；elif 互斥正确；报错文案自带 V15 吊销背景——**凭证契约在脚本自身是闭环的**（对照 eval_demo.py 兜底默认值未跟进） |
| --tenant-id 旗标发 X-Tenant-ID（:18-19） | RequestContextFilter:33-42 | ❌ **幽灵参数**：:114 强制凭证 → 必经认证 → 认证身份租户权威，header 恒被忽略（6.4 M-3） |

### 2.2 入边（谁消费这个脚本的绿灯）

| 声称/预期 | 实况 |
| --- | --- |
| `scripts/demo.sh:142`（run_e2e，verify_stack 链路 :140-148） | ✅ 唯一仓库内调用方——但**不供凭据**（6.2 M-1） |
| `docs/java-parity-report.md:33`「E2E smoke \| Done」 | ⚠️ 文档声称完成，实况是**四个 GitHub workflow（ci/baseline-ci/nightly-regression/publish-image）零引用本脚本**——门禁未接入任何自动化（6.3 M-2） |
| nightly-regression.yml | 走 eval 契约链（generate_eval_dataset → contract fixture → run_regression :21-28），与本脚本无交集——平台的「自动绿灯」由另一条链路承担，两者断言强度均受 fallback 假绿灯影响（audit-eval_live_runner 头号发现） |

## 3. 循环依赖评估

**结论：不适用且无等价风险。** 脚本 → 平台 HTTP 单向，平台无任何反向引用（grep 全仓仅 demo.sh 与文档两处非代码引用）。真正的「环状风险」在**同批脚本横向**：SSE 双布局解析器在 `e2e_chat_flow.py:87-97` 与 `eval_live_runner.py:113-118` 构成逐行级双副本（6.5 L-1）——无共享库约束下的复制不是循环依赖，但改动需双处同步，性质上是「两份实现互为漂移面」。

## 4. 越层调用评估

**结论：黑盒直连是职能而非违规，但绑定了一个未文档化的信封形态。** e2e 冒烟的本质就是绕过前端 `client.ts` API 层直连后端——这正是其存在意义（client.ts 层的 401 恢复/错误归一逻辑不参与被测路径）。需要记录的边界事实是：脚本把 `ReactChatResponseVO` 的**混合信封**（Result 字段 ok/msg + 裸 VO 字段 answer/fallback/citations 并存于同一对象）硬编码为断言依据。该混合形态是 client.ts arch 报告所记「信封三形态并存」中的一种且无文档声明。后果评估：若后端归一信封，脚本会**响亮失败**（`ok != 1` → SystemExit）而非静默错判——失败模式安全，故记为边界注记而非缺陷。

## 5. 平行实现核查

同批脚本横向对表（`requirements.txt` 零依赖约定 ⇒ 无共享库 ⇒ 复制是常态）：

| 能力 | e2e_chat_flow.py | eval_live_runner.py | eval_demo.py |
| --- | --- | --- | --- |
| SSE 双布局解析（先长后短） | ✅ :87-97 | ✅ :113-118（副本） | ❌ 无 SSE |
| 凭证默认值 | ✅ 强制显式（:114-118） | ✅ | ❌ 兜底已吊销的 dev-admin-key-2026（audit-eval_demo） |
| done 载荷 fallback 字段 | ❌ 未消费（H-1） | ❌ 未消费（audit-eval_live_runner 头号发现） | —（无流式） |
| 超时异常入捕获清单 | ✅ TimeoutError :165 | ✅ | ❌ 同步端点 except 接不住（audit-eval_demo） |

**结构性结论**：脚本批内呈「两个先进一个落后」的漂移谱，且**两份先进脚本共享同一个盲区**（fallback 欠消费）——这排除了「单文件笔误」解释，指向响应语义消费标准的系统性缺失：全批脚本都以「ok==1 + answer 非空」为充分条件，而服务端降级链路（ReactAgentService:95/:111 聚合 usedFallback 后仍 success() 恒 ok=1）恰恰让这两个条件在 LLM 全挂时同时成立。

## 6. 发现明细

### 6.1 H-1 门禁依赖面欠消费：fallback 字段是降级与真实的唯一区分信号，脚本跳过它

- **脚本位置**：:137-140（非流式断言）、:152-155（流式断言）
- **证据**：ReactChatResponseVO.fallback 真实存在且被服务端全程聚合（ReactAgentService:61/:65/:77-78/:95/:111 usedFallback 链 → ReactResponseFormatter.success:33 落 VO）；服务端失败语义 = 恒 ok=1 + fallback=true + answer=兜底文案（ReactResponseFormatter.success:28-33 无条件 ok(1)）。
- **影响**：LLM 全挂时脚本三跳全绿——health UP、ok==1、answer 非空全部满足，打印 "e2e chat flow success"。冒烟门禁声称守护的「核心对话链路可用」退化为「HTTP 面 + 序列化可用」。demo.sh 的 verify_stack 以此绿灯作为整栈就绪的最终判据（:148 print_success）。
- **修复建议**（脚本侧，零 API 变更）：两处断言各加一行 `payload.get("fallback") is True → SystemExit`；done 载荷同理（:152 后）。与服务端无任何耦合改动，是纯消费端补全。

### 6.2 M-1 唯一仓库内调用方不供凭据：demo.sh 的冒烟门槛依赖环境外变量

- **脚本位置**：:114-118（凭证强制）；**调用方**：demo.sh:142
- **证据**：demo.sh 的 DEMO_ENV_FILE=.env.demo（:8）仅以 `docker compose --env-file` 传入容器（:25），**不导出到执行 run_e2e 的 shell**；demo.sh 全文无 E2E_API_KEY；`.env.example` 全文无 E2E_API_KEY（仅有 OPENAI_API_KEY 等容器侧变量）。
- **影响**：默认 `./scripts/demo.sh` 路径下 run_e2e 必然 SystemExit("an explicit credential is required")，verify_stack 在 run_e2e 一步中断（:147），print_success 不可达——除非用户在 shell 里预先 `export E2E_API_KEY=...`，而这一要求**仓库内无任何文件声明**。与 api-recipes 审查的「凭证链三段断裂」同型但更轻：脚本侧报错显式不静默，断裂点在调用方供给而非契约本身。
- **修复建议**（调用方/文档侧）：demo.sh run_e2e 前检查 E2E_API_KEY 并给出 actionable 报错；或 .env.example 增加 E2E_API_KEY 注释行说明其作用域是宿主 shell 而非 compose。

### 6.3 M-2 门禁地位与接线不符：绿灯无自动化消费方

- **证据**：`grep e2e_chat_flow .github/workflows/*` 零命中（四 workflow 全查）；java-parity-report.md:33 声称 "E2E smoke | Done"。
- **影响**：脚本的全部价值在「有东西自动消费它的退出码」，而现实中它只在 demo.sh 手动链路运行。平台 nightly 的自动质量门（nightly-regression）走 eval 契约链且不覆盖 /ai/react/chat 端到端路径——两条链路各自留白：本脚本覆盖端到端但无人自动跑，契约链自动跑但不覆盖此路径。
- **修复建议**：ci.yml 或 nightly 增加一步：起 compose 后跑本脚本（凭据用 APP_BOOTSTRAP_API_KEY 注入链，参照 lite compose 的既有先例）。M-1 修复是其前置。

### 6.4 M-3 --tenant-id 是幽灵参数：强制凭证使租户头恒被服务端忽略

- **脚本位置**：:18-19 / :110
- **证据**：:114-118 强制 bearer/api-key → ApiKeyOrJwtAuthFilter 必解析出认证身份 → RequestContextFilter:33-42 认证租户权威、X-Tenant-Id 仅在无认证租户时生效。
- **影响**：旗标提供假能力——用户传 --tenant-id tenant-b 以为在测 tenant-b 的隔离，实际租户永远来自凭据。冒烟脚本本不测多租户，此参数属「向 API 面对齐的仪式性复制」。
- **修复建议**：删除旗标或 help 文案标注「仅在无认证部署下生效，本脚本强制认证故恒 no-op」。

### 6.5 L-1 SSE 双布局解析器双副本（与 eval_live_runner）

- **证据**：:87-97 与 eval_live_runner.py:113-118 逐行级同构，当前语义一致（前缀先长后短均正确）。
- **影响**：服务端线格式若演进（如新增事件类型或包裹变化），需双处同步；漂移已在凭证默认值一项上于批内物化（eval_demo 未跟进 V15 吊销）。
- **修复建议**：维持零依赖约定不变，优先方案是把解析器收敛进 eval_live_runner 已有的公共形态后由本脚本注释指认权威副本；或接受双副本但增加一行交叉引用注释。

### 6.6 L-2 JSON 失败双策略：内层宽松、外层裸奔

- **证据**：consume_event 内层 JSONDecodeError → 宽松兜底 `{"raw": joined}`（:46-47）；外层 main guard（:165）不含 JSONDecodeError（ValueError 子类）→ http_json 的 `json.loads`（:29）失败时裸 traceback 而非友好 SystemExit。
- **影响**：低——200 + 非 JSON 响应（代理/SPA 回退页）会以 traceback 而非 "e2e chat flow failed: ..." 呈现，诊断信息反而更多，仅形态不专业。
- **修复建议**：main guard 追加 `json.JSONDecodeError`（或 ValueError）。

### 6.7 正面项清单（保留项）

1. 三跳端点契约（方法/路径/请求 VO 三字段/produce 类型/权限前提）**全部对码成立**——同批脚本中端点对齐度最高。
2. SSE 双布局解析先长后短正确，且注释（:83-86）写明了 WebFlux 包裹成因。
3. 凭证强制 + 报错文案自带吊销背景——V15 之后的正确姿势，eval_demo 的对照反面。
4. 失败模式全部响亮（SystemExit 非零退出 + 明确文案），无静默吞错路径。
5. 依赖面零代码耦合（stdlib-only），黑盒契约清晰可迁移。

## 7. 修复优先级建议

| 优先级 | 项 | 动作 |
| --- | --- | --- |
| 1 | H-1 | 两处断言补 fallback 检查（脚本侧一行级，消除假绿灯） |
| 2 | M-1 | demo.sh 供凭据或前置校验 E2E_API_KEY + .env.example 注释行 |
| 3 | M-2 | CI 接入本脚本（起 compose → 跑冒烟 → 消费退出码） |
| 4 | M-3 | 删除 --tenant-id 或标注恒 no-op |
| 5 | L-1/L-2 | 解析器交叉引用注释 / main guard 补 JSONDecodeError |

全部修复为脚本与调用方侧编辑，不触碰平台代码与对外 API 签名。脚本的依赖方向本身无任何需修复项——单向、零耦合、契约对码全真；需要补全的是依赖面的**语义深度**（H-1）与**绿灯的被消费链**（M-1/M-2），即从「检查器」升级为真正闭环的「门禁」。

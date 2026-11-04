# 架构分析报告：architecture-agent-harness.md 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码与文档；不运行任何 build/mvn；全部结论来自静态交叉审查，每条结论附文档行号与代码位置证据）
- **分析对象**：`docs/architecture-agent-harness.md`（161 行：流程图 :1-13 + 当前范围 :15-18 + 组件表 :20-32 + 内置工具 :34-43 + Action schema :45-63 + Policy/config :65-98 + 审计回放 :100-109 + MCP runtime :111-125 + Workspace runtime :127-147 + Evaluation :149-161）
- **核查范围**：文档「依赖面」逐条对码——组件表 11 项逐一确认存在与职责（`src/main/java/com/enterprise/iqk/agent/harness/` 全包 19 文件通读，含文档未提及的 `HarnessEventRecorder`/`UnifiedDiffService`/`ActionPolicyDecision`/`ActionSchema`/`TrustedActionRequest/PreviewResponse`）；流程图 8 节点对 `AgentHarnessService.execute()`（:16-53）实际编排顺序；schema 表逐行对 `ActionSchemaRegistry`（:15-44）；「按顺序检查」声明逐行对 `ActionPolicyGuard.evaluate()` if 链（:23-63）；YAML 样例逐键对 `AgentHarnessProperties.java` 与 `application.yml:175-184`、`application-prod.yml:48-52`；事件契约对 `HarnessEventRecorder.java` + `AgentWorkflowEngine.emitEvent()`（:135-149）；MCP 接口代码块逐参对 `McpToolAdapter.java`；权限声明对 `AgentHarnessController.java:33-53` + `V14__agent_harness_permissions.sql:2/:8`；trusted 标志的全部来源 grep（`new AgentAction(` 全仓 3 处 + 8 参/9 参构造器区分）
- **约束遵守**：未改源码与文档、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（所有修复建议均为文档侧编辑）
- **关联报告**：`audit-architecture-agent-harness.md.md`（同文件缺陷审查：配置样例布尔值失实、MCP allowed-hosts 缺键、token 生命周期等发现的一手记录——本报告从边界角度归位引用不重审）；`arch-WorkspaceRuntime.java.md`（SPI 分发 findFirst、三个 runtime 私有工具方法副本、SPI 携 tenantId 未消费）；`arch-AgentWorkflowEngine.java.md`（emitEvent best-effort 定性、workflow⇄harness 包级环的 workflow 侧）；`arch-WorkflowReactAgentService.java.md`/`arch-ReactAgentService.java.md`（包级环两侧证据、端口下沉方案）

## 1. 模块定位

本文档是**harness 子系统的架构声明（组件清单类文档）**：交付五类依赖契约——①执行流水线图（:5-13），②组件职责表（:20-32），③action schema 注册表（:47-61），④运行时边界配置样例（:69-94），⑤审计事件契约（:102-109）。它是 arch 系列审查的架构文档中**与代码对齐度最高的一份之一**：11 个组件全部真实存在且职责描述无误（对照 arch-HybridRetrieval 记录的「端点整体虚构」——本文档无任何编造成分），内置工具清单、schema 表、事件字段、权限声明、生产 profile 断言全部与代码吻合（详见 2.1）。

**漂移集中在三层**：配置样例层（:69-94 三处与出厂默认相反或不可用，4.1）、行为规则层（:96 检查顺序两处倒置 + 漏一条拒绝规则，4.2）、结构层（流程图与组件表遗漏主编排器与两个真实组件，4.3）。三层漂移都不涉及安全边界本身——边界实现是对的，文档教边界的方式会误导操作者。

## 2. 依赖全景

### 2.1 出边（文档对代码的声称清单）

| 文档声称 | 位置 | 核实结果 |
| --- | --- | --- |
| 流程图 `AgentAction → ActionPolicyGuard → AgentRuntime`（:5-13） | AgentHarnessService.java:16-53 | ⚠️ 中间缺主编排器：policy guard 从不被直接调用，真实路径是 `AgentHarnessService.execute()` 统一入口（:18 evaluate → :29 runtime 选择 → :50 sanitize → :51 记事件）；图上也缺 `HarnessPayloadSanitizer` 一环（4.3） |
| 组件表 11 项（:20-32） | harness 包全量 | ✅ 全部存在、职责描述与实现一致（`AgentAction` 三上下文归一 AgentAction.java:10-20；`ActionPolicyGuard` 三类校验 :23-63；`TrustedActionService` preview/token/execute :23-63） |
| 表外遗漏 | 包内实况 | ❌ `HarnessEventRecorder`（事件真实写入者）与 `UnifiedDiffService`（workspace diff 引擎）两个组件完全未被文档提及（4.3） |
| 内置工具 4 项 + finish 不属 runtime（:36-43） | BuiltinToolRuntime.java:21-26 + ActionSchemaRegistry.java:16-24 | ✅ 逐一吻合；finish 由 ReAct service 处理属实（两 service 各自处理循环结束，且 `AgentAction` 空 action 归一为 "finish" 而该名未注册 schema，天然被 policy 拒绝——AgentAction.java:45-49，防御一致） |
| schema 表 11 行 Action/Runtime/Trust/Required（:47-61） | ActionSchemaRegistry.java:15-44 | ✅ 逐行吻合（required 字段集合、trustedOnly 全对；optional/sensitive 列是合理省略） |
| 「不符合 schema 在 runtime 之前拒绝，返回 source=policy」（:63） | AgentHarnessService.java:18-27 | ✅ 精确（含 no-runtime 分支返回 source=runtime :33-41 也未夸大） |
| YAML 样例（:69-94） | AgentHarnessProperties.java + application.yml:175-184 | ❌ 三处失实：`trusted-runtime-enabled: true` vs 出厂生效值 **false**（yml:176 显式 env 兜底 false，Java 默认 true 在 :14 被覆盖为死值）；`write-enabled/shell-enabled: true` vs yml:179-180 默认 **false**；MCP 样例指向 `http://localhost:7331` 但漏掉 `mcp.allowed-hosts` 键——默认 allowedHosts=[]（AgentHarnessProperties.java:37-43 注释明言「Default empty: all private/loopback hosts are refused」），**照抄样例的 localhost 配置必被拒**（4.1） |
| 「Policy guard 按顺序检查：注册→全局禁用→租户→required input→trusted 开启→trustedRuntimeAccess=true」（:96） | ActionPolicyGuard.java:27-61 | ❌ 两处倒置 + 漏一条：真实顺序是 注册:27 → 禁用:31 → 租户:34 → **trustedRuntimeAccess:39 → trustedRuntimeEnabled:43** → required:47 → content/patch:52 → **unknown fields:57**（文档把 required 提到 trusted 检查之前、把两个 trusted 检查次序写反、漏掉 unknown-fields 拒绝规则）（4.2） |
| 「生产 profile 默认关闭 trusted runtime、workspace 写入和 shell」（:96） | application-prod.yml:48-52 | ✅ 三键全部显式 false（正面：文档对部署面的唯一断言是对的） |
| `/ai/harness/**` 需 PERM_AGENT_TRUSTED 或 ROLE_ADMIN；迁移创建 `agent:trusted` 授 ADMIN（:98） | AgentHarnessController.java:34/:41/:49 + V14__agent_harness_permissions.sql:2/:8 | ✅ 精确（三个端点逐一 @PreAuthorize，V14 种子权限 + ADMIN 授予） |
| 审计：taskId+stepId 才写事件；ACTION_STARTED 含脱敏 actionInput；完成事件含 source/status/latencyMs/payload keys；与 STEP_* 共享 agent_event 表（:102-109） | HarnessEventRecorder.java:17-51 + AgentWorkflowEngine.java:135-148 | ✅ 全部吻合（shouldRecord :47-51、sanitizeActionInput :26、summarizeObservation :34-40、emitEvent→eventMapper.insert 同表）；但「还原执行过程」是 best-effort 承诺——emitEvent 整体 try/catch 吞异常（:146-148），insert 失败该动作事件永久失踪（4.5） |
| MCP：不接受模型指定网络目标、只调显式配置启用的 server/tool、非 2xx/超时/解析异常转 source=mcp status=error observation（:113-125） | McpToolRuntime.java:31-46 + HttpMcpToolAdapter.java:81-96 | ✅ 全对（adapter 错误 Map 契约 :81-96 → runtime 转 observation :41-46）；接口代码块 :116-123 五个方法签名逐参正确，但未标注 supports/execute(server,tool,args) 是 **default** 方法、仅 execute(args) 抽象（McpToolAdapter.java:10-18）——实现者会误以为五个都要实现（4.4） |
| Workspace 六动作 + 七条安全边界（:129-147） | WorkspaceRuntime.java + AgentHarnessProperties.Workspace | ✅ 与 arch-WorkspaceRuntime.java.md 的全量核查一致（路径 root 限内、ProcessBuilder 免 shell、命令族白名单、限额全来自配置）；「下一步移独立 sandbox」是合理前瞻 |
| Evaluation 覆盖清单（:149-161） | src/test/.../harness/ 10 个测试类 | ✅ 全部有对应测试类（HarnessEvaluationTest 等四类路径声明属实） |
| 「受信动作必须先 preview 生成一次性 token 再 execute」（:18） | TrustedActionService.java + 全仓 grep | ✅ 且是**代码强制而非约定**：`new AgentAction(` 全仓仅 3 处，trusted=true 的 9 参构造只有 TrustedActionService（:67/:92-102）；ReactAgentService.java:334-343 与 WorkflowReactAgentService.java:245-254 都走 8 参构造（trusted 恒 false）。正面项 |

### 2.2 入边（谁依赖文档所描述的子系统）

- `service.ReactAgentService.java:4/:41/:334` 与 `agent.workflow.WorkflowReactAgentService.java:3-4/:44/:245` → `AgentHarnessService`：文档 :3 声称「从业务 service 中抽出」与入边证据吻合——恰好两个 ReAct 服务是仅有的业务消费方，抽取承诺兑现。
- `controller.AgentHarnessController` → `TrustedActionService` + `ActionSchemaRegistry`：文档提到的两个受信端点之外还有第三个 `GET /ai/harness/actions`（schema 列表，:33-37），文档未列——轻度遗漏（对读者无害，对 API 面完整性有损）。
- `harness.BuiltinToolRuntime` → `rag.RagAnswerService` / `tools.CourseTools`：文档完全未描述 harness 的**下游依赖方向**（组件表只列了自己人），rag_search 一个 action 的实际延迟是整条 RAG 管线（含 LLM），与「工具调用」的直觉量级不同（4.6）。

### 2.3 隐式依赖：文档的结构立场

:11 把 `AgentObservation -> AgentWorkflowEngine events` 画成流水线的一环——这实际上是把 **harness→workflow 的跨包依赖边**当作架构本身来陈述（见 3.1）。文档没有意识到（或没有声明）这条边是一个已被三份 arch 报告定位过的环边，反而将其升格为规范流程图。架构文档对「哪条边是设计、哪条边是债务」沉默时，读者会把债务当设计固化。

## 3. 循环依赖评估

- **Bean 级无环（正面）**：`AgentWorkflowEngine` 的依赖只到自持 mapper，不回指任何 harness Bean；`AgentHarnessService` → runtimes/policy/recorder/sanitizer 全是包内单向；`TrustedActionService` → `AgentHarnessService` 无回边。Spring 容器内不存在启动环。
- **包级环 workflow⇄harness 存在，且被本文档「转正」**：`harness.HarnessEventRecorder.java:3` → `agent.workflow.AgentWorkflowEngine`（出边），`workflow.WorkflowReactAgentService.java:3-4` → `agent.harness.AgentHarnessService/AgentAction`（回边）。环的两端证据与「端口下沉」修复方案已在 arch-WorkflowReactAgentService / arch-ReactAgentService / arch-AgentWorkflowEngine 三份报告中定型，本报告不再展开。与本文档相关的增量结论是：**修复环时本文档 :11 必须同步改**——`AgentObservation` 之后真实经过的是 `HarnessEventRecorder`（文档未命名的类），环消解后（Recorder 依赖本地 `HarnessEventSink` 端口）流程图若仍直连 `AgentWorkflowEngine` 就与实现第二次脱节。文档侧修复应一步到位：流程图与组件表补 Recorder，并把事件边标注为「经端口」。
- **无 harness→rag/tools→harness 回环（正面）**：BuiltinToolRuntime 对 RagAnswerService/CourseTools 的依赖方向向下，rag 与 tools 包零回指（arch-RagAnswerService 已证 rag 是全库最干净汇点）——harness 的业务出边是干净的单向扇出。

## 4. 越层评估

（文档类对象的「越层」=文档越过真实边界教错了东西，或文档遗漏的代码侧真实越层。）

### 4.1 配置样例三处失实：照抄即与出厂行为相反或不可用（核心）

逐键对 `AgentHarnessProperties` + `application.yml:175-184`：

1. **`trusted-runtime-enabled: true`（:72）vs 生效默认 false**：yml:176 `${APP_AGENT_HARNESS_TRUSTED_ENABLED:false}` 显式占位，Java 默认 true（Properties:14）在该键下永不可达。样例展示的是一个「需要显式开启才存在」的形态却不加任何说明——对照 audit 系列定性的「样例布尔与出厂默认相反」三连例，这是该模式的 harness 版；
2. **`write-enabled/shell-enabled: true`（:77-78）vs 默认 false**（yml:179-180，Properties:23-24）：同型反向 ×2；
3. **MCP 样例（:85-93）照抄即不可用**：`base-url: http://localhost:7331` 指向 loopback，而安全默认 `mcp.allowed-hosts` 为空列表（Properties:37-43，HttpMcpToolAdapter 默认拒 loopback/私网）——样例里**没有这个键**，且全文档对 allowed-hosts 机制零提及。操作者按样例配好 MCP 后第一次 `mcp_call` 必被拒，且拒绝原因（私网防护）在任何文档里都查不到。

另外样例中 `workspace.allowed-commands/allowed-git-subcommands`（:83-84）两键在 application.yml 中不存在（仅 Java 默认 :29-31，数值与样例一致）——属于「展示可调面」的合理画法，不算失实，但与上三条叠加后，读者无法分辨样例里哪些值是出厂值、哪些是作者假设。修复位：样例值对齐出厂默认（true→false）或显式标注「示例为开启态，出厂默认见 application.yml」；MCP 样例补 `allowed-hosts` 键并加一句私网防护语义说明。纯文档编辑。

### 4.2 「按顺序检查」声明与 evaluate() if 链两处倒置、漏一条规则

文档 :96 明确承诺顺序（「按顺序检查」），因此顺序漂移是实体缺陷而非措辞问题：

- 文档把「required input 齐全」排在 trusted 两查之前，实际 required 检查在 :47，两个 trusted 检查在 :39/:43 **之前**——后果是操作者根据拒绝 reason 推断配置问题时会排错因（例如 trusted 未开启时缺 required 字段的 action，真实拒绝是 `trusted_runtime_disabled` 而非 `invalid_action_input`）；
- 文档把「trusted runtime 是否开启」排在「trustedRuntimeAccess=true」之前，实际相反（:39 先查 action 标志、:43 后查全局开关）——同理，`trusted_runtime_required` 与 `trusted_runtime_disabled` 两种拒绝的真实优先级与文档相反；
- 完全遗漏 **unknown-fields 拒绝规则**（:57-61）：模型输出任何 schema 外字段（LLM 场景的高频事件）会导致 `invalid_action_input: unknown field(s)` 拒绝。这是对 ReAct 稳定性影响最直接的策略规则，文档的 schema 章只字未提。

修复位：:96 重写为与代码一致的真实顺序（注册 → 全局禁用 → 租户 → trustedRuntimeAccess → trustedRuntimeEnabled → required → content/patch → unknown fields），并在 schema 章补「schema 外字段同样拒绝」。纯文档编辑。

### 4.3 流程图与组件表遗漏主编排器和两个真实组件

- 流程图（:5-13）从 `AgentAction` 直跳 `ActionPolicyGuard`——但 guard 从不被直接调用，真实下一跳是 `AgentHarnessService.execute()`（包内唯一编排器：选 runtime、异常兜底 :47-48、脱敏 :50、记事件 :51 全在这一个方法里）。图也缺 `HarnessPayloadSanitizer` 环节；
- 组件表（:20-32）11 项里没有 `HarnessEventRecorder`——它是审计事件的真实写入者（:17-51），文档 :25 说「AgentHarnessService … 写入 action 事件」、:102 说「通过 AgentWorkflowEngine.emitEvent() 写入」，两处描述的真实主体都是这个未被命名的类：**文档内部两处事件写入表述靠一个未提及的类才能自洽**。排查审计问题时按 :25/:102 找 AgentHarnessService/引擎都找不到 `ACTION_STARTED` 的装配点；
- 组件表同样没有 `UnifiedDiffService`（workspace_propose/apply_patch 的 diff 引擎，WorkspaceRuntime.java:38 注入）——workspace 章声称的「生成统一 diff/应用统一 diff」能力（:142）实际由它承担。

修复位：流程图补 `AgentHarnessService`（主编排）与 `HarnessPayloadSanitizer`（observation 限流）；组件表补 `HarnessEventRecorder`（注明条件写入 taskId+stepId）与 `UnifiedDiffService`；:25 的「写入 action 事件」改为「经 HarnessEventRecorder 写入」。纯文档编辑。

### 4.4 MCP 接口代码块未区分 default 方法

:116-123 五个方法平铺，实际 `supports` 与 `execute(server,tool,args)` 是带合理默认实现的 default 方法（McpToolAdapter.java:10-16：supports 精确匹配 server()/tool()，execute 双参委托单参），只有 `execute(args)` 是抽象的——即**新适配器最少只需实现 3 个方法**。文档把接口画成五实现契约，会劝退本可以很薄的自定义 adapter。另注：`McpToolRuntime` 对 adapter 列表同样 findFirst 取首个 supports 者（:31-34），多 adapter 同名 server/tool 时注册顺序敏感——与 AgentHarnessService 的 runtime 分发同型（arch-WorkspaceRuntime 已记），单 adapter 现状下无实害。修复位：代码块标注 default 与最少实现面。

### 4.5 审计「可还原执行过程」是 best-effort 承诺

:109 声称事件流「可以还原执行过程」，但 `emitEvent` 整体吞异常（AgentWorkflowEngine.java:146-148 仅 log.error）——DB 抖动期间的动作事件静默丢失，且无补偿/重放通道（arch-AgentWorkflowEngine 已定性为 best-effort 审计日志而非 event sourcing）。对 harness 场景的增量风险是：受信动作（文件写入、shell 执行）的 ACTION_FAILED 丢失意味着**高危操作的发生痕迹可能不存在于事件表**，审计承诺与实现强度不匹配。另有一处流形态未声明：policy 拒绝与 no-runtime 路径只写 ACTION_FAILED/COMPLETED 而无 ACTION_STARTED（AgentHarnessService.java:25/:39 跳过 :43），消费「还原执行过程」的下游要容忍有 FAILED 无 STARTED 的流。修复位：:109 补「事件写入为 best-effort，丢失不阻断执行；拒绝路径无 STARTED 事件」。

### 4.6 token 生命周期与 preview 副作用未声明

- 「一次性确认 token」（:18）的完整语义是：**进程内存态** ConcurrentHashMap（TrustedActionService.java:21）、TTL 10 分钟（:16）、重启即全部失效、多实例部署下 preview 与 execute 落不同节点必失败——架构文档对安全机制的生命周期/部署约束沉默（audit 报告已记，本报告归位为架构声明缺失：这不是实现细节，是「能否水平扩展」的边界条件）；
- preview 并非无副作用：`workspace_apply_patch` 的 preview 会**真实执行** `workspace_propose_patch`（TrustedActionService.java:66-78，经完整 harness 链）——propose 虽不写文件，但要真实读文件算 diff。「preview=只看不执行」的直觉对 apply_patch 不成立。修复位：:18 补 token 生命周期与多实例约束一句；MCP/Workspace 章补 preview 副作用说明。

### 4.7 存量正面项（无修复）

- 受信标志是代码强制（2.1 末条）：唯一 trusted=true 来源是 TrustedActionService，两 ReAct 服务 8 参构造恒 false——「不会因为模型输出伪造而执行文件、命令或外部工具」（:17-18）的声明有 grep 级证据；
- 生产 profile 三键显式 false（:96）与 prod yml 逐键吻合；
- 错误处理边界纪律好：runtime 异常转 observation 而非外抛（AgentHarnessService.java:47-49）、adapter 三类失败全部转错误 Map（HttpMcpToolAdapter.java:81-96）——「不会抛出到 ReAct 循环外」（:125）属实；
- 权限声明（:98）、事件字段（:108）、schema 表（:47-61）、Evaluation 清单（:149-161）全部精确对码。

## 5. 平行实现

1. **stringVal/intVal/elapsedMs 工具方法副本族**（本系列已两度记录，本次在 harness 内闭环证实第三处）：`AgentHarnessService.elapsedMs`（:55-57）、`BuiltinToolRuntime.stringVal(带 fallback)/intVal/elapsedMs`（:140-162）、`McpToolRuntime.stringVal(无 fallback，签名已漂移)/elapsedMs`（:63-70）、`WorkspaceRuntime` 自有一套（arch-WorkspaceRuntime 已记：Mcp 版无 fallback、Builtin 版返回 Integer 的漂移模式成立）。同一个包内 4 个类各养一套 3-5 行私有工具，修复成本最低的平行项；
2. **status=error 字符串契约三处复制**：HttpMcpToolAdapter 造 `Map.of("status","error",…)`（:81-96）、BuiltinToolRuntime 按同字符串约定判错（:51-55）、McpToolRuntime 再判一次并转发 message（:41-46）——「Map 里 status 字符串等于 error」是横跨三类的口头契约，无共享常量/类型，任何一处拼写漂移即静默失效（message 丢失降级为 "mcp tool failed" 的路径已存在：McpToolRuntime.java:42-45）；
3. **死 API 两枚**：`ActionPolicyGuard.isAllowed()`（:19-21）与 `ActionSchemaRegistry.actions()`（:50-52）在 main 源码零调用（grep 证实）——均为 registry/guard 建成时预留的便利门面，已被 `evaluate()`/`list()` 的真实消费面绕开。与 saveTaskMemory 同型的「死 API 与语义漂移并列报告」项，属低危清理；
4. **文档层平行**：:25（AgentHarnessService 写事件）与 :102（经 emitEvent 写事件）是同一职责的两处表述，真实主体 HarnessEventRecorder 两处都未出现——文档自身的「同事实多表述无单点」微型样本（对照 arch-api-recipes 5.1 的五文档平行，此处是文件内平行）；
5. **引用存量结论（不重审）**：ReAct 决策解析三重实现、react.stream.* Meter 合并、三个 ChatClient 无 @Primary 绑定等 harness 相邻平行项已在 arch-WorkflowReactAgentService / arch-ReactAgentService 定型，其主刀面在 workflow/service 侧，本报告不展开。

## 6. 修复优先级建议（均为文档侧编辑，不改任何对外 API 签名；改动仅限本文件）

1. **P1** 配置样例对齐出厂默认或显式标注开启态（4.1：trusted-runtime-enabled/write/shell 三布尔 + MCP 样例补 `allowed-hosts` 键与私网防护说明）——样例是操作者唯一直接粘贴的面，三处失实叠加 = 照抄即错；
2. **P2** 重写 :96 检查顺序为真实 if 链顺序，补 unknown-fields 拒绝规则（4.2）；
3. **P2** 流程图/组件表补 `AgentHarnessService`、`HarnessPayloadSanitizer`、`HarnessEventRecorder`、`UnifiedDiffService`，:25 与 :102 的事件写入表述收敛到 Recorder 单点（4.3；若未来做端口下沉消环，本条与 3.1 的流程图修正是同一批文档改动）；
4. **P2** 补 token 生命周期（内存态/TTL 10min/重启失效/多实例不可用）与 preview 对 apply_patch 的真实执行副作用（4.6）；补 MCP 接口 default 方法标注（4.4）；补审计 best-effort 与「拒绝路径无 ACTION_STARTED」说明（4.5）；
5. **记录项**：补 `GET /ai/harness/actions` 端点（2.2）；BuiltinToolRuntime 的 rag_search 延迟量级与自建 conversationId 行为可在 builtin 工具章注明（2.2/4.6 邻接）；死 API isAllowed/actions() 清理属代码侧低危项，与本文档无关可单列 backlog（5.3）；保留 2.1/4.7 的全部精确表述作为改写锚点。

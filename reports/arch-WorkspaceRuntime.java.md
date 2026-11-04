# 架构分析报告：WorkspaceRuntime 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/agent/harness/WorkspaceRuntime.java`（499 行）
- **核查范围**：该类的全部出边（3 个协作者，逐个下钻其依赖清单）与全部入边（grep 全库证实 main 源码零直接引用）；SPI 接口 `AgentRuntime` 与全部三个实现类；`AgentHarnessService` 的 runtime 分发流程（:16-53）；`AgentHarnessProperties.Workspace` 全部字段默认值与 `application.yml:175-184` 的覆盖值比对；两个测试类的构造方式
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-WorkspaceRuntime.java.md`（同文件缺陷审查，威胁模型/算术类发现以该报告为准）、`arch-ReactAgentService.java.md`、`arch-WorkflowReactAgentService.java.md`（harness⇄workflow 包级环的完整论证）

## 1. 模块定位

该类是 agent 工具执行底座（agent.harness 包）中负责**文件工作区**的 runtime，实现 SPI 接口 `AgentRuntime`（`source/supports/execute` 三方法，`AgentRuntime.java:3-9`），承载 6 个 `workspace_*` 动作（:28-35）：列目录、读文件、搜文本、提案补丁、应用补丁、受限 shell。

它**没有任何直接调用方**：grep 全库证实 main 源码中零引用（`AgentHarnessService` 不 import 它），唯一生产接线是 Spring 把三个 `@Component` runtime（`BuiltinToolRuntime`、`McpToolRuntime`、本类）收集进 `AgentHarnessService.java:11` 的 `List<AgentRuntime>`，运行时按 `supports(action)` 分发（`AgentHarnessService.java:28-31`）。调用链上它之前有两道边界：`ActionPolicyGuard` 策略裁决（:17-24，含租户动作白名单）与 `HarnessPayloadSanitizer` 出参消毒（:44）。main 之外仅两个测试直接构造它（`WorkspaceRuntimeTest`、`HarnessEvaluationTest.java:24`）。

**这是本包边界质量最高的类**：SPI 抽象 + 容器收集 + 策略前置，本类完全不知道"谁在调用、为哪个租户调用"。

## 2. 依赖全景

### 2.1 出边（注入的 2 个协作者 + 同包 SPI 类型）

| 依赖 | 所在包 | 该协作者自身的依赖（已下钻核实） | 方向评价 |
| --- | --- | --- | --- |
| `AgentHarnessProperties` | config.properties | 零 iqk 依赖（纯 `@ConfigurationProperties` 数据类，经 `App.java:10` `@ConfigurationPropertiesScan` 注册） | ✅ 叶子 |
| `UnifiedDiffService` | agent.harness（同包） | **零 iqk 依赖**（仅 spring `@Component` + JDK regex）；且本类经 :47 的包私有构造器 `new` 出实例，非注入 | ✅ 纯函数式叶子 |
| `AgentRuntime` / `AgentAction` / `AgentObservation` | agent.harness（同包） | SPI 契约类型，零外部依赖 | ✅ |

二跳可达的更深层：**没有**。两个协作者都是叶子，本类是三个 runtime 中唯一不触及任何特性服务的——对比 `BuiltinToolRuntime` → `rag.RagAnswerService`、`tools.CourseTools`，`McpToolRuntime` → `HttpMcpToolAdapter`。本类的"下游"就是 JDK NIO/Process API 与 `workspaceRoot` 这条部署期边界，理论上可脱离整个 Spring 上下文独立测试（测试也确实这么用）。

### 2.2 入边

- **main 源码零直接引用**（grep 证实）；生产接线仅经 `List<AgentRuntime>` 抽象。
- 测试：`WorkspaceRuntimeTest.java`（4 处 `new WorkspaceRuntime(workspace)`，走 :46 包私有构造器）、`HarnessEvaluationTest.java:24`（同构造器，拼进完整 `AgentHarnessService` 做策略-分发集成验证）。
- 没有任何包反向依赖本类；它对 harness⇄workflow 包级环（见 arch-WorkflowReactAgentService 3.1）**零参与**。

### 2.3 横切依赖

- **无**。不读 MDC、不用 `TenantContext`、不注册 Meter、不发事件——事件由 `AgentHarnessService` 统一记录，租户裁决由 `ActionPolicyGuard` 在它之前完成。`AgentAction` 携带的 `tenantId/chatId/taskId/stepId`（`AgentAction.java:10-15`）本类一概未消费（见 4.3）。

## 3. 循环依赖评估

### 3.1 结论：类级与包级均无环可能；本类是全库依赖图中的纯汇点

```
WorkspaceRuntime ─→ AgentHarnessProperties（叶子）
WorkspaceRuntime ─→ UnifiedDiffService（叶子）
WorkspaceRuntime ←─ AgentHarnessService（经 List<AgentRuntime> 抽象，非直接引用）
```

- **类级**：入边（Spring 收集 + 测试）与出边（两个叶子）无任何交汇，环在结构上不可能形成。
- **包级**：本类为 agent.harness 包贡献的对外依赖只有 `config.properties` 一条向下边。harness 包当前唯一的越包回边是 `HarnessEventRecorder → agent.workflow.AgentWorkflowEngine`（三角环论证见 arch-ReactAgentService 3.1），与本类无关；即使未来按端口下沉方案消环，本类零改动。
- **Spring 侧**：构造器注入 + 零回指，无循环依赖风险，也无 Bean 顺序依赖。

### 3.2 一个结构敏感性备注

`AgentHarnessService.java:28-31` 的分发是 `runtimes.stream().filter(supports).findFirst()`——`List<AgentRuntime>` 的元素顺序来自 Spring Bean 收集顺序，**无保证**。当前三个 runtime 的动作命名空间不相交（`workspace_*` / 内置工具名 / MCP 工具名），顺序无关紧要；但这个"按 supports 首个命中"的契约靠命名约定维持而非强制。若未来某 runtime 的 `supports` 改为宽松匹配（如前缀/通配），就会与 `workspace_search_text` 这类跨 runtime 重名动作产生顺序敏感的行为漂移。属记录性观察，无需动作。

## 4. 越层调用与边界问题

### 4.1 workspaceRoot 默认值 "." 使工作区边界与应用自身代码边界重合——低（部署边界，记录性）

三层默认值链：Java 兜底 `root = "."`（`AgentHarnessProperties.Workspace`），yml 显式覆盖 `root: ${APP_AGENT_HARNESS_WORKSPACE_ROOT:.}`（`application.yml:177`）——**兜底仍是 "."**，即服务器进程当前工作目录（开发环境通常是仓库根）。`resolvePath`（:295-301）防逃逸是相对这个根而言的：默认部署下，"工作区沙箱"与"应用自身源码/配置"是同一个目录，`workspace_read_file`、`rg`、`git log`（均在默认 allow-list 内，:345-360）读到的就是宿主应用自己的文件。缓解因素：`write-enabled`/`shell-enabled` 默认 false（Java 与 yml :178-179 双层一致），读侧上限 `maxFileBytes=20000`。**修复方向**（不改签名）：生产部署显式设置 `APP_AGENT_HARNESS_WORKSPACE_ROOT` 指向专用目录；或 Java 兜底改为要求显式配置、缺省时禁用工作区动作（fail closed）。

### 4.2 测试便利构造器把能力开关反转为 true——低（潜在后门形态）

包私有构造器 `WorkspaceRuntime(Path)`（:46-48）经 `defaultProperties`（:489-495）把 `writeEnabled`/`shellEnabled` 设为 **true**——与生产默认（false/false）方向相反。当前安全：构造器包私有，grep 证实仅两个同包测试使用。风险形态：若有人为复用把它放宽为 public/加 `@Autowired`，就得到一个绕过配置体系的"全开"runtime Bean。**修复方向**：`defaultProperties` 的开关默认值改为与生产一致（false），需要开能力的测试显式 set——测试改动一处，签名不变；或至少在该构造器 javadoc 标注"仅测试、能力全开"。

### 4.3 SPI 携带租户上下文而本类未消费：工作区全租户共享——低（产品边界，记录性）

`AgentAction` 是带租户的（`tenantId` 字段，`AgentAction.java:12`，由 `ReactAgentService.java:337` 等调用方显式传入），租户维度的动作管控在 `ActionPolicyGuard` 的 `tenantAllowedActions` 完成；但本类收到的 `action.tenantId()` 完全未读——文件读写、补丁、shell 的**对象与结果均不按租户隔离**，多租户部署下所有 agent 共享同一 `workspaceRoot`。这不违层（runtime 无状态、无租户耦合反而干净），但意味着"租户隔离"这条安全边界在 harness 内止步于策略守卫，工作区是全局单例资源。是否需按租户分目录属产品决策；若决定隔离，`resolvePath` 前插入 tenant 段即可，签名不变。

### 4.4 配置真值以 yml 显式覆盖为准，一处 Java/yml 默认反向——已核实无影响

`trusted-runtime-enabled`：Java 默认 `true`（`AgentHarnessProperties:11`）而 yml 显式 `false`（`application.yml:176`，env 兜底 false）——沿用 ModelRouter 审查确立的"以 yml 显式覆盖为准"原则，实际生效值是 false，无隐患；但这是全文件唯一一处两层默认反向，若有人删掉 yml 该行，行为会静默翻转。其余 `workspace.*` 五个键两层默认一致（逐项比对核实）。

### 4.5 无越层调用

本类不触及 mapper、不触及 service/rag/tools 特性层、不读传输层状态——**无任何越层调用**。这是与 `BuiltinToolRuntime`（下探 rag/tools，见 arch-WorkflowReactAgentService 4.3）的显著差异。

## 5. 同层平行实现与隐性耦合

### 5.1 三个 runtime 各养一套 `stringVal`/`intVal`/`elapsedMs` 私有副本——低

grep 证实：`stringVal` 三份（`BuiltinToolRuntime.java:140`、`McpToolRuntime.java:63`、本类 :465，签名/语义已有漂移——Mcp 版无 fallback 参数）、`intVal` 两份（`BuiltinToolRuntime.java:149` 返回 `Integer` vs 本类 :474 返回 `int`+fallback，失败语义不同）、`elapsedMs` 四份（含 `AgentHarnessService.java:55`）。与本库 ReAct 内核三重实现（arch-WorkflowReactAgentService 5.3）同型的轻度漂移，量级小得多。**修复方向**：抽包私有工具类（如 `ActionInputs`/`Timing`，harness 包内，不改任何公开签名），或先只统一 `elapsedMs`。

### 5.2 与策略层/消毒层的关系——健康的分层样板（记录性）

本类的安全防护与 `ActionPolicyGuard`（动作级：禁用清单、租户白名单、trusted 运行时）分层清晰：守卫拦"哪个租户能做什么"，本类拦"怎么做才安全"（路径逃逸 :295-301、大小上限、命令 allow-list :340-367、git/mvn 参数审查 :369-458）。两层各自 fail closed，职责不重叠。这是本包值得保持的样板，未来新增 runtime 应照此切分。

## 6. 结论与建议优先级

**总评**：全库依赖图中的纯汇点——两个叶子协作者、零直接调用方、零横切依赖、零环参与、零越层调用，SPI + 容器收集 + 策略前置的接线方式是本项目的边界样板。发现均为低危记录项，无一需要紧急处理。

| 优先级 | 事项 | 章节 | 改动范围 |
| --- | --- | --- | --- |
| 1 | 生产部署显式设置 workspace root（或缺省 fail closed） | 4.1 | 配置/部署，代码零改动 |
| 2 | `defaultProperties` 开关默认值与生产对齐（false） | 4.2 | 仅本类 + 受影响测试 |
| 3 | harness 包内抽 `ActionInputs`/`Timing` 工具收敛 helper 副本 | 5.1 | harness 包内 4 文件 |
| 4 | 租户隔离、分发顺序契约——记录性，待产品/演化决策 | 4.3/3.2 | 搁置 |

以上各项均不改变任何对外 API 签名；第 2 项会改变包私有测试构造器的行为，需同步调整 `WorkspaceRuntimeTest`/`HarnessEvaluationTest` 中依赖"默认全开"的用例（显式 set 开启）。

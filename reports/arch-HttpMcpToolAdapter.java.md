# 架构分析报告：HttpMcpToolAdapter 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/agent/harness/HttpMcpToolAdapter.java`（179 行）
- **核查范围**：该类的全部出边（2 个协作者 + SPI 接口，逐个下钻依赖清单）与全部入边（grep 全库证实 main 源码零直接引用，生产接线为 `McpToolRuntime:13` 的 `List<McpToolAdapter>` 容器收集，`McpToolRuntime` 全文通读含 supports→execute 分发与 error Map 约定）；SPI 接口 `McpToolAdapter` 全文（default 方法语义与本类的覆写关系）；`AgentHarnessProperties.Mcp/McpServer/McpTool` 全部字段默认值与三个 yml 的覆盖情况比对；测试构造方式（`HttpMcpToolAdapterTest`、`McpToolRuntimeTest`、`HarnessEvaluationTest`）
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-HttpMcpToolAdapter.java.md`（同文件缺陷审查——H-1 缓冲后检查、H-2 黑名单缺口、M-1 DNS rebinding、M-2 JSON-RPC 信封等以该报告为准，本报告不重复展开）、`arch-WorkspaceRuntime.java.md`（同包 SPI 样板与本报告 3.2/4.1 的对照）、`arch-ModelRouter.java.md`、`arch-TenantCostService.java.md`（词汇/契约复制问题的库级模式）

## 1. 模块定位

该类是 agent 工具执行底座（agent.harness 包）中 **MCP 工具的出站 HTTP 适配器**，实现同包 SPI 接口 `McpToolAdapter`（`server/tool/supports/execute` 四方法，`McpToolAdapter.java:3-17`）。它是整个 iqk 代码库中唯一一个**由 LLM 决策间接触发出站 HTTP 调用**的组件（`mcp_call` 动作 → JSON-RPC `tools/call` POST，:66-78）——这一身份使它的出站网络边界同时是 SSRF 防线（`isSafeBaseUrl`，:121-155）。

它**没有任何直接调用方**：生产接线是两级 SPI 收集——`McpToolRuntime`（同为 `@Component`）经 `List<McpToolAdapter>` 收集本类（:13），每个 `mcp_call` 动作按 `supports(server, tool)` 首个命中分发（:31-34）；`McpToolRuntime` 自身再被 `AgentHarnessService` 的 `List<AgentRuntime>` 收集。调用链上它之前有两道边界：`ActionPolicyGuard` 策略裁决（`mcp_call` 注册为 `trustedOnly=true`，`ActionSchemaRegistry:25-27`）与配置门控（server/tool 双层 enabled，:52-58）。main 之外仅 `HttpMcpToolAdapterTest:40` 直接构造（测的其实是静态 `isSafeBaseUrl`）。

**默认部署下完全惰性**：三个 yml 均无 `mcp:` 配置段，`servers` 默认空 Map（`AgentHarnessProperties.Mcp:36`）→ `supports` 恒 false → 本类在生产配置下不可达，属"待命组件"。

## 2. 依赖全景

### 2.1 出边（注入的 2 个协作者 + 同包 SPI 类型 + JDK）

| 依赖 | 所在包 | 该协作者自身的依赖（已下钻核实） | 方向评价 |
| --- | --- | --- | --- |
| `AgentHarnessProperties` | config.properties | 零 iqk 依赖（纯 `@ConfigurationProperties` 数据类，经 `App.java:10` 注册）；`Mcp`/`McpServer`/`McpTool` 嵌套类默认值：servers 空 Map、allowedHosts 空列表、timeoutMs=5000、path="/mcp/tools/call" | ✅ 叶子 |
| `ObjectMapper` | Spring Boot 自动装配 | 零 iqk 依赖（Jackson databind） | ✅ 叶子 |
| `McpToolAdapter` | agent.harness（同包） | SPI 契约接口，零依赖 | ✅ |
| JDK `HttpClient`/`InetAddress` | JDK | 字段级硬编码 `HttpClient.newHttpClient()`（:33），非注入 | ✅ 但见 4.3 |

二跳可达的更深层：**没有**。两个协作者都是叶子。本类的真实"下游"是 JDK 网络栈与 `harnessProperties.getMcp().getServers()` 这条**运营者配置面**——不触及任何特性 service（对比 `BuiltinToolRuntime` → `rag.RagAnswerService`/`tools.CourseTools`）。它是三个 runtime/adapter 中依赖面最窄的之一。

### 2.2 入边

- **main 源码零直接引用**（grep 证实）；生产接线仅经 `List<McpToolAdapter>` 抽象，分发契约在 `McpToolRuntime:31-34`。
- 测试：`HttpMcpToolAdapterTest:40`（直接 `new`，验证静态 `isSafeBaseUrl` 的地址判定）、`McpToolRuntimeTest:14` 与 `HarnessEvaluationTest:66`（匿名实现 `McpToolAdapter` 替身，不涉及本类）。
- 没有任何包反向依赖本类；对 harness⇄workflow 包级环（arch-WorkflowReactAgentService 3.1）**零参与**。

### 2.3 横切依赖

- **无**。不读 MDC/租户上下文、不注册 Meter、不发事件。值得注意：`@Slf4j` 已声明但**全类零日志调用**——错误信息全部走返回值通道（audit L-1 建议补 `log.warn`，现成设施闲置）。出站请求也不携带租户标识——MCP 调用是全租户共享的出站通道，租户维度在 `ActionPolicyGuard` 止步（与 `WorkspaceRuntime` 的工作区共享形态同类，arch-WorkspaceRuntime 4.3）。

## 3. 循环依赖评估

### 3.1 结论：类级与包级均无环；SPI + 容器收集，结构与 WorkspaceRuntime 同型

```
HttpMcpToolAdapter ─→ AgentHarnessProperties（叶子）
HttpMcpToolAdapter ─→ ObjectMapper（Jackson 叶子）
HttpMcpToolAdapter ←── McpToolRuntime（经 List<McpToolAdapter> 抽象，非直接引用）
McpToolRuntime ←────── AgentHarnessService（经 List<AgentRuntime> 抽象）
```

- **类级**：入边（容器收集 + 测试）与出边（两个叶子）无任何交汇，环结构上不可能。构造器注入 + 零回指，无 Bean 顺序依赖。
- **包级**：本类为 harness 包贡献的对外依赖只有 `config.properties` 一条向下边。harness 包唯一的越包回边（`HarnessEventRecorder → agent.workflow.AgentWorkflowEngine`）与本类无关。
- **Spring 侧**：`@Component` 单例 + 字段级 final HttpClient，无循环风险；HttpClient 非容器管理（见 4.3），连 Bean 图都不进入。

### 3.2 结构敏感性备注：两级"首个命中"分发契约（与 arch-WorkspaceRuntime 3.2 同型，风险翻倍）

`McpToolRuntime:31-34` 的 `adapters.stream().filter(supports).findFirst()` 依赖 Spring Bean 收集顺序——**无保证**。当前生产仅本类一个 `McpToolAdapter` 实现，且它的 `supports` 是"任意已配置 server"语义（:51-58），与接口 default 的恒等语义（`McpToolAdapter.java:9-11`）不同：**只要 server 名在配置里，本类就声称支持**。若未来新增第二个 adapter（如 stdio/本地进程型 MCP、或针对特定 server 的专用 adapter），本类的宽匹配 `supports` 会在 `findFirst` 中**优先截胡**所有 server 名——新 adapter 永远不可达，且无任何报错。这个契约靠"只有一个实现"的巧合维持而非强制。属记录性观察；若扩展 adapter 阵容，需先把 supports 的匹配范围契约化（如按 server 名段划分或在 supports 内声明排他性）。

## 4. 越层调用与边界问题

### 4.1 SPI 身份语义错位：多服务器适配器披着单服务器恒等接口——低（API 形态，记录性）

`McpToolAdapter` 接口按"一个 adapter 对应一个 server/tool"设计（`server()`/`tool()` 恒等 + default `supports`），本类实际是**配置驱动的多服务器适配器**：`server()`/`tool()` 返回占位哨兵 `"configured-http"`（:42/:47），`supports` 被覆写为任意已配置 server（:51-58）。后果：① `server()`/`tool()` 的返回值在生产路径零消费（三参 `execute` 已覆写，default 恒等 `supports` 被覆盖）——接口的两个身份方法在本实现中是**死 API 面**；② `"configured-http"` 哨兵一旦出现在日志/trace 里无诊断价值。接口语义与实现形态的错位是 3.2 风险的根源。修复方向：接口拆分（恒等型 adapter 与配置查找型 adapter 分接口），或至少 javadoc 声明 supports 的宽匹配语义；不改现有签名可达。

### 4.2 错误契约是字符串键 Map：adapter 与 runtime 之间的隐性协议——低

本类与 `McpToolRuntime` 之间的错误约定是 `Map.of("status","error","message",…)`（:81/:86/:90/:96），runtime 侧按 `"error".equals(resultMap.get("status"))` 识别（`McpToolRuntime:41-45`）——**跨组件契约靠两个文件里各自手写的字符串键维持**，无共享类型。同时 `AgentObservation.error` 是另一条结构化错误通道，同一调用链上存在两套错误表达（Map 约定 → runtime 转译 → Observation）。漂移风险：任何一侧改动键名/取值即静默破坏错误分类（错误会被当成功送入 LLM 上下文——恰好是 audit M-2 的失效形态）。修复方向：以本类返回小型结果 record（`McpCallResult(status, message, payload)`）替代 Map，或至少提取共享常量；不改 `execute` 对外签名不可达（返回类型是接口签名的一部分），属需接口演化的记录项。

### 4.3 `HttpClient` 字段硬编码：网络边界不可注入、不可测试、不可配置——低

`private final HttpClient httpClient = HttpClient.newHttpClient()`（:33）绕过容器：① 测试只能验证静态 `isSafeBaseUrl`，HTTP 路径（重定向策略、超时行为、响应处理）无替身可测——与同包 `WorkspaceRuntime` 的"可脱离 Spring 独立测试"样板相比，本类的网络路径测试覆盖因此缺席；② 客户端级配置（connect timeout、executor、版本策略）无入口，仅 per-request timeout（:74）；③ 安全关键的默认 redirect=NEVER 依赖 JDK 默认值而非显式声明（audit 已提醒固化）。修复方向：构造器注入 `HttpClient`（或包私有测试构造器），生产装配在 `@Configuration` 中显式 `HttpClient.newBuilder().connectTimeout(…).followRedirects(NEVER).build()`——防护显式化 + 可测性一并解决，`McpToolAdapter` 接口签名不变。

### 4.4 出站网络边界的防护分层——健康的样板（记录性，弱点以 audit 为准）

本类的安全分层值得保持：策略门控（`ActionPolicyGuard` trustedOnly + disabledActions）→ 配置门控（server/tool 双层 enabled，:52-58）→ 地址校验（`supports` 与 `resolveUri:100` **双重**调用 `isSafeBaseUrl`，即使跳过 supports 门控 execute 内仍拦截）→ 传输限制（2 MiB 上限、per-request timeout、异常统一转结构化 error 不外抛）。四层各自独立，职责不重叠。层次位置也正确：SSRF 防线落在**出站边界的唯一咽喉**（本类是全库唯一 LLM 间接触发的出站 HTTP 组件），而非分散在调用方。防线的具体缺口（缓冲后检查、IPv6 ULA/CGNAT、DNS rebinding TOCTOU、JSON-RPC 信封）见 audit H-1/H-2/M-1/M-2——本报告只确认分层结构本身无需重排。

### 4.5 配置面即信任边界，且校验全部发生在调用时——低（记录性）

`baseUrl` 来自运营者静态配置而非 LLM 输入（`supports` 只在预配置 Map 里查找，agent 无法创建新配置）——这是正确的信任边界划分；本类仍对配置做地址校验，属"防运营失误"的纵深。但所有校验都发生在**使用时**而非启动时：配置错误（非法 baseUrl、被 enabled 的敌意地址）只在第一个 `mcp_call` 到达时才暴露，且错误以 Map 消息形式静默进入 agent 观察（:101 抛 IAE → :89-91 转错误 Map）。库内已有启动期校验设施（`config/AppStartupValidator`），MCP 配置未纳入。修复方向：启动时对 enabled server 预跑 `isSafeBaseUrl`，非法配置 fail fast——不改本类任何签名。

### 4.6 无越层调用

本类不触及 mapper、不触及 service/rag/tools 特性层、不读传输层状态——与 `WorkspaceRuntime` 同为 harness 包中不越层的组件（对比 `BuiltinToolRuntime` 下探 rag/tools）。

## 5. 同层平行实现与隐性耦合

### 5.1 helper 副本——本类无新增（对照记录）

grep 复核：本类无私有输入解析/计时 helper（`stringVal`/`mapVal`/`elapsedMs` 的三份副本在 `BuiltinToolRuntime`/`McpToolRuntime`/`WorkspaceRuntime`，见 arch-WorkspaceRuntime 5.1——本类不在复制者之列，唯一调用方 McpToolRuntime 自带的那份属 runtime 侧）。本类自身的重复只有 `isSafeBaseUrl` 的双重调用（supports 与 resolveUri，属有意纵深而非漂移）。

### 5.2 错误 Map 约定与 JSON-RPC 协议知识的分散——低

"MCP 协议"知识在本类内是半套：请求侧知道 JSON-RPC 信封构造（:66-71），响应侧不做信封校验（id 不核对、`error` 对象不识别，audit M-2）——协议知识只实现了一半，另一半（错误语义）事实上留给了 `McpToolRuntime` 的 `status` 键约定，而该约定并不是 JSON-RPC 语义。协议边界不完整 + 错误通道字符串化（4.2）叠加，使"工具失败"与"工具成功但结果怪异"在下游不可区分。与 4.2 的结果 record 化方案一并解决。

### 5.3 与 WorkspaceRuntime 的边界对照组——正面样板（记录性）

同为 harness 包的边界组件，两者同型点：SPI 实现、容器收集、零特性层依赖、四层防护、注释写明威胁模型（:25-28/:34-37/:108-120——注释质量在库内属最高档）。差异点：WorkspaceRuntime 可整体独立测试（JDK NIO 可指临时目录），本类网络路径因 4.3 不可替身。若以两者为样板沉淀 harness 包的"新 runtime/adapter 检查清单"（SPI 收集、策略前置、出参消毒、可注入基础设施、威胁模型注释），本类补齐 4.3 即完全达标。

## 6. 结论与建议优先级

**总评**：依赖方向极干净——两个叶子协作者、零直接调用方、零横切、零环参与，两级 SPI + 容器收集的接线是 `WorkspaceRuntime` 同型样板；默认部署下惰性（无 mcp 配置），生产风险处于待命状态。发现集中在**契约形态**而非依赖方向：SPI 身份语义错位埋下 3.2 的首命中截胡隐患（扩展时爆发）、错误契约字符串化（4.2/5.2）、HttpClient 不可注入使安全关键的传输行为不可测（4.3）。防护分层结构本身健康，具体防线缺口以 audit 为准。

| 优先级 | 事项 | 章节 | 改动范围 |
| --- | --- | --- | --- |
| 1 | HttpClient 改构造器注入 + 显式 followRedirects(NEVER)/connectTimeout（联动 audit H-1 的传输层修复同批做） | 4.3 | 本类构造器 + 新 @Configuration 装配；接口签名不变 |
| 2 | 错误契约 record 化（`McpCallResult`），消灭 status 字符串键约定；顺带补 JSON-RPC 信封校验（audit M-2） | 4.2/5.2 | 本类 + McpToolRuntime + 接口返回类型（接口演化，需两文件同步） |
| 3 | 启动期校验 enabled MCP server 的 baseUrl（纳入 AppStartupValidator） | 4.5 | config 包 + 本类零改动 |
| 4 | SPI 身份语义契约化（supports 排他性声明/接口拆分）——扩展 adapter 阵容前处理 | 3.2/4.1 | 接口 + 本类 javadoc/实现 |
| 5 | 补 log.warn（@Slf4j 已就位，联动 audit L-1 错误消息白名单化） | 2.3 | 仅本类 |

以上各项除第 2 项（返回类型属接口签名，需接口演化）外均不改变对外 API 签名；第 1 项新增的注入构造器与既有 `@RequiredArgsConstructor` 生成构造器需协调（改显式构造器，测试 `HttpMcpToolAdapterTest:40` 的两参构造调用同步调整）。

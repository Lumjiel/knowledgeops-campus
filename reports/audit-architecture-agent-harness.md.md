# 审查报告：docs/architecture-agent-harness.md

- **日期**：2026-11-04
- **审查方式**：只读通读 + 把文档的每条声明（组件清单、schema 表、配置键、检查顺序、事件契约、安全边界）与后端源码逐一比对
- **被审对象**：`docs/architecture-agent-harness.md`（162 行）：Agent Harness 架构文档（范围、组件、schema、策略配置、审计、MCP/Workspace runtime、测试覆盖）
- **约束遵守**：未改任何源码；仅新增本报告文件；无 git commit/push；无删除文件；无 npm install / mvn build
- **关联报告**：`reports/audit-WorkspaceRuntime.java.md`、`reports/audit-HttpMcpToolAdapter.java.md`、`reports/audit-AgentWorkflowEngine.java.md`、`reports/audit-ReactDecisionParser.java.md`（同类/相邻组件的深度审查，本报告引用其结论时不重复展开）

## 协作者清单（均已核实）

| 协作者 | 用途 | 关键证据 |
| --- | --- | --- |
| `agent/harness/` 包（19 个类） | 组件清单与职责 | find 结果 + 各类源码 |
| `ActionSchemaRegistry.java` | 注册的 11 个 action | :19-49 |
| `ActionPolicyGuard.java` | 检查顺序与拒绝规则 | :24-79 |
| `AgentHarnessService.java` | source=policy observation、事件写入 | :18-21, :43, :48 |
| `AgentHarnessProperties.java` | `app.agent-harness` 全部键与 Java 默认值 | 全文 |
| `application.yml` / `application-prod.yml` | 出厂配置默认值 | application.yml:175-184；prod:48-52 |
| `AgentHarnessController.java` | preview/execute 端点 | :40, :48 |
| `TrustedActionService.java` | 一次性 token 生命周期 | :16, :31-51 |
| `HarnessEventRecorder.java` / `HarnessPayloadSanitizer.java` | 事件契约 | Recorder 全文；Sanitizer:37-46 |
| `V10__agent_workflow_tables.sql` / `V14__agent_harness_permissions.sql` | agent_event 表与 agent:trusted 权限 | V10:46-57；V14:2,8 |
| `WorkflowController.java` | 任务事件回放 API | :59 |
| `McpToolAdapter.java` / `HttpMcpToolAdapter.java` | 接口签名与 SSRF 边界 | Adapter:9-15；HttpAdapter:121-146 |
| `WorkspaceRuntime.java` | shell/patch 边界 | :245-251, :333, :161-186 |
| `AgentAction.java` | 空 action 归一化 | :44-46 |
| `src/test/.../harness/`（10 个测试类） | Evaluation 章声明 | find 结果 |

---

## 概述：先说做对的部分（本文档与实现吻合度很高）

1. **组件清单准确**：列出的 11 个组件全部存在，职责描述与实现一致；`/ai/harness/actions/preview` → `/ai/harness/actions/execute/{token}` 两步流程与 AgentHarnessController.java:40,48 吻合。
2. **内置工具与 finish 归属正确**：`BuiltinToolRuntime` 恰好支持 query_school/query_course/add_course_reservation/rag_search 四个（BuiltinToolRuntime.java:22-25）；`finish` 未注册进 schema、由 ReAct service 处理（ReactAgentService.java:334 构造 action，registry 无 finish）。
3. **Schema 表 11 行全部正确**：required/trust 与 ActionSchemaRegistry.java:19-49 逐项一致；`workspace_propose_patch`/`apply_patch` 的「`path` plus `content` or `patch`」二选一确实被强制执行（ActionPolicyGuard.java:73-78 `requiresPatchPayload`）。
4. **普通 ReAct 无受信权限的声明正确**：ReactAgentService.java:334 与 WorkflowReactAgentService.java:245 都走 8 参构造器，`trustedRuntimeAccess` 默认 false（AgentAction.java:33-40）。
5. **审计与回放章节准确**：仅 taskId+stepId 齐全才记录（HarnessEventRecorder.shouldRecord）；ACTION_STARTED/COMPLETED/FAILED 三类事件存在，STARTED 含脱敏 actionInput，完成事件含 source/status/latencyMs/observation 摘要（含 payloadKeys，HarnessPayloadSanitizer.java:46）；共享 `agent_event` 表（V10:46）；回放 API `GET /ai/workflow/tasks/{taskId}/events` 存在（WorkflowController.java:59）。
6. **权限与迁移描述正确**：V14 迁移创建 `agent:trusted` 并授予 ADMIN（V14:2,8）；`/ai/harness/**` 要求 `PERM_AGENT_TRUSTED` 或 `ROLE_ADMIN`（SecurityConfiguration.java:56-57）。
7. **Workspace 安全边界描述与实现一致**：路径限制在 root 内、`ProcessBuilder` 直接执行 tokenized command 不经系统 shell（WorkspaceRuntime.java:245-251,333）、命令族与 git 子命令白名单、限额全部来自配置；propose 生成统一 diff / apply 可应用 diff 或写 content（经 UnifiedDiffService，WorkspaceRuntime.java:161-186）。
8. **「生产 profile 默认关闭 trusted runtime、workspace 写入和 shell」正确**（application-prod.yml:48-52）。
9. **Evaluation 章与测试文件一一对应**：10 个测试类覆盖所列全部条目，含 `HarnessEvaluationTest` 固定评估与 `HttpMcpToolAdapterTest`。

---

## 高严重度

### H-1 配置样例把三个安全开关展示为 `true`，与所有出厂部署面（全 `false`）相反，且与本文第 96 行自相矛盾

**位置**：architecture-agent-harness.md:72（`trusted-runtime-enabled: true`）、:77-78（`write-enabled: true`、`shell-enabled: true`）。

**对照**：application.yml:176/179/180 出厂默认全部为 `false`（`${APP_AGENT_HARNESS_TRUSTED_ENABLED:false}` 等）；application-prod.yml:49-52 显式再压一层 `false`；没有任何 compose/profile 把它们置 true。Java 兜底里仅 `trustedRuntimeEnabled=true`（AgentHarnessProperties），但被 yml 显式覆盖——即**任何按仓库启动的栈，三个开关都是关的**。

**触发条件与影响**：文档 :67 说「`app.agent-harness` 控制 runtime 边界」，随后给出的样例即三开状态且无任何「非默认值」标注；读者把它当默认配置或推荐配置复制，等于自行打开受信 runtime + workspace 写入 + shell 执行。文档 :96 自己写「生产 profile 默认关闭」，前后矛盾会让读者无法判断哪个是真默认值。

**修复建议**：样例改为出厂默认（三处 `false`），并以注释标出「开启受信能力的完整前置：显式置 true + 调用方持有 `PERM_AGENT_TRUSTED` + 走 preview/execute 两步」；或保留 true 但显式标注「⚠ 示意为开启态，非默认值」。

### H-2 MCP 样例配置照抄不可用：base-url 指向 loopback，而默认 SSRF 防护拒绝一切 loopback/私网地址，放行所需的 `mcp.allowed-hosts` 全文未提

**位置**：architecture-agent-harness.md:85-93（`mcp.servers.local-tools`，`base-url: http://localhost:7331`）。

**描述**：三层失实叠加：
1. `HttpMcpToolAdapter` 默认拒绝解析到 loopback/私网/link-local/云元数据地址的一切 base-url，放行只认 `mcp.allowed-hosts` 显式白名单（HttpMcpToolAdapter.java:121-146；AgentHarnessProperties.Mcp.allowedHosts 默认空）——样例的 `localhost:7331` 恰好落在默认拒绝集里，**照文档配置的 MCP 集成调用时必然失败**，而能使它工作的那个键（`allowed-hosts`）在文档任何位置都没有出现；
2. application.yml 全文没有 `mcp:` 配置块——`local-tools` 这个 server 名与 7331 端口是文档虚构的，易被理解为出厂自带；
3. 文档 :125 「只会调用 app.agent-harness.mcp.servers 中显式配置且启用的 server/tool」与此呼应，但读者无从知道「显式配置」还必须包含 `allowed-hosts` 才对 loopback 生效。

**触发条件**：运维按 :85-93 配置 MCP 并从 `mcp_call` 调用 → observation `source=mcp, status=error`（base-url 被拒）。

**修复建议**：样例补上 `allowed-hosts: ["localhost", "127.0.0.1", "::1"]`（与 AgentHarnessProperties 注释一致）并加一句安全提示「allowed-hosts 用于放行内网/loopback 的受控例外，默认为空、生产保持为空」；同时注明 `local-tools` 为示意、application.yml 未预置任何 MCP server。

---

## 中严重度

### M-1 Policy guard 检查顺序描述与实现错位，且漏掉两条拒绝规则

**位置**：architecture-agent-harness.md:96（「按顺序检查：…④required input 是否齐全 ⑤trusted runtime 是否开启 ⑥受信 action 是否真的带有 trustedRuntimeAccess=true」）。

**对照**：ActionPolicyGuard.evaluate 的实际顺序是：①注册 ②全局禁用 ③租户白名单 ④**trustedRuntimeAccess 标记**（trusted_runtime_required）⑤**trusted-runtime-enabled 开关**（trusted_runtime_disabled）⑥required input ⑦content-or-patch 二选一 ⑧unknown fields（ActionPolicyGuard.java:24-79）。文档的 ④⑤⑥ 三项全部错位：实际的信任标记检查（⑥）先于开关检查（⑤）先于 required 检查（④）。文档还完全漏掉第 ⑧ 条「未知字段拒绝」与第 ⑦ 条「content-or-patch」（后者仅由 schema 表间接暗示）。

**触发条件与影响**：对拒绝顺序的推理失真——例如受信 action 缺 required 字段且 trusted runtime 关闭时，实际先报 `trusted_runtime_disabled` 而非 `invalid_action_input`；排障者按文档顺序会预期到错误的 denial code。安全边界文档对「哪个检查先生效」的描述应与代码逐项对齐。

**修复建议**：按实现重排为六+二条：注册 → 全局禁用 → 租户白名单 → 受信标记 → 受信开关 → required → content-or-patch → 未知字段，并给每条标注对应 denial code（missing_action/unsupported_action/disabled_action/tenant_action_denied/trusted_runtime_required/trusted_runtime_disabled/invalid_action_input）。

### M-2 一次性确认 token 的生命周期语义未记录：10 分钟 TTL、纯内存态、重启即全部失效

**位置**：architecture-agent-harness.md:18（「生成一次性确认 token，再走 execute」）、:30（组件表 TrustedActionService 行）。

**描述**：TrustedActionService 的 token 有四个架构级事实文档只字未提：TTL 10 分钟（:16 `TOKEN_TTL`）；存于进程内 `pendingActions` Map（**未持久化**，服务重启后所有已 preview 未 execute 的 token 全部失效）；过期 token 靠 preview/execute 时机会性清扫（:33-35, :47-51），不保证及时回收；「一次性」语义在 execute 后移除条目实现。preview→execute 两步是这个安全模型的核心，token 的存储介质与失效边界（尤其「重启即作废」）属于架构文档必须覆盖的契约——多实例部署下内存态 token 不共享，更是直接决定该机制能否横向扩容的前提。

**触发条件**：读者基于本文档做容量规划或多实例部署评估时。

**修复建议**：在「当前范围」或组件表 TrustedActionService 行补充：TTL、内存态、单实例约束（多副本需粘性路由或外置存储）、重启失效。

---

## 低严重度

### L-1 `McpToolAdapter` 接口代码块省略 `default` 修饰符

**位置**：architecture-agent-harness.md:116-122。

**描述**：`supports(server, tool)` 与 `execute(server, tool, arguments)` 实为 default 方法（McpToolAdapter.java:9-15，前者默认按 server()/tool() 相等判断，后者默认转发到单参 execute）。文档把它们展示为抽象方法，照文档实现适配器会被迫多写两个本可选的方法；签名集合本身一致。另外 `server()`/`tool()` 的约定语义（适配器声明自己服务的单一 server/tool 对）文档未说明。

**修复建议**：代码块补 `default` 关键字，或加一句「supports 与双参 execute 有默认实现，通常只需实现 server()/tool()/单参 execute」。

### L-2 组件表缺 `HarnessEventRecorder` 与 `UnifiedDiffService`

**位置**：architecture-agent-harness.md:20-32（组件表）；harness 包实际 19 个类。

**描述**：`HarnessEventRecorder` 是「审计与回放」整章的事件写入主角（脱敏、摘要、三类事件都经它），`UnifiedDiffService` 承担 propose/apply 的统一 diff 语义，两者都只在正文被动提及、不在组件表中。同表却收录了更外围的 `AgentObservation`。架构文档的组件索引应完整覆盖有独立职责的协作类。

**修复建议**：组件表补两行（Recorder：事件写入与脱敏摘要；UnifiedDiffService：统一 diff 生成与应用）。

### L-3 「空 action 归一化为 finish」的语义未记录

**位置**：architecture-agent-harness.md:43（finish 说明处）。

**描述**：AgentAction.normalize 会把空白 action 名归一化为 `"finish"`（AgentAction.java:44-46）——即模型输出空/缺失 action 等价于结束循环，而不是报 unsupported_action。这是一个容错语义而非显而易见的行为，文档只说「finish 不属于 runtime action」，未覆盖这条边界。

**修复建议**：:43 处补一句「模型输出的空/空白 action 会被归一化为 finish，视为循环结束」。

### L-4 流程图的事件时序粒度有误导

**位置**：architecture-agent-harness.md:5-12（`Guard -> Runtime -> Observation -> events -> trace`）。

**描述**：流程图暗示事件在 observation 之后统一写入；实际 `ACTION_STARTED` 在 runtime 执行**之前**就已写入（AgentHarnessService.java:43 先于 runtime 调用），observation 之后才写 COMPLETED/FAILED。粗粒度图可接受，但对「用事件流还原执行过程」的读者，事件与执行阶段的先后关系是回放语义的一部分。

**修复建议**：图或正文补一句「ACTION_STARTED 先于 runtime 执行写入，COMPLETED/FAILED 随 observation 写入」。

---

## 修复优先级建议

1. **H-1 / H-2 同一批修**：都集中在「Policy and configuration」的样例块——三处布尔值改回默认 + MCP 样例补 `allowed-hosts` 并注明示意性质。这是读者唯一会复制粘贴的部分，也是安全边界所在。
2. **M-1** 按 ActionPolicyGuard.evaluate 逐行重写检查顺序并标注 denial code，成本半小时以内。
3. **M-2** 补 token 生命周期四行说明（TTL/内存态/单实例/重启失效）。
4. 低严重度随手带上：接口块补 `default`、组件表补两行、finish 归一化一句话、流程图时序一句话。

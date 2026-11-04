# Agent Harness 架构

<!--
  Document header: Agent Harness 子系统的架构说明。读者面向后端维护者与安全评审者；
  正文按「执行链路 → 组件职责 → 各 runtime 边界 → 配置与审计 → 测试覆盖」展开。
  表格与代码块描述的组件名、action 名、配置键均与源码/配置文件一一对应，勿在此处
  修改语义；更新接口或 action 列表时请同步源码与本表。
-->

Agent Harness 将 ReAct 循环中的工具执行从业务 service 中抽出，形成统一的动作执行层：

```text
model decision
  -> AgentAction
  -> ActionPolicyGuard
  -> AgentRuntime
  -> AgentObservation
  -> AgentWorkflowEngine events
  -> trace / final answer
```

## 当前范围

本节界定 MVP 的信任模型：哪些能力默认可用、哪些需要受信确认通道。

当前实现是 Harness MVP：内置业务工具默认可用；MCP 与 Workspace runtime 已接入，但都要求
`AgentAction.trustedRuntimeAccess=true`。普通 ReAct 对话创建的 action 默认没有该权限，因此不会因为模型输出伪造而执行文件、命令或外部工具。受信动作必须先走 `/ai/harness/actions/preview` 生成一次性确认 token，再走 `/ai/harness/actions/execute/{token}` 执行。

| Component | Responsibility |
|---|---|
| `AgentAction` | 标准化模型输出的 action 名称、参数、tenant、chat、task、step 上下文 |
| `ActionSchemaRegistry` | 固化 action schema：runtime、必填字段、可选字段、敏感字段、是否要求 trusted runtime |
| `ActionPolicyGuard` | 对模型请求的 action 做白名单、schema、trusted runtime 校验 |
| `AgentHarnessService` | 选择 runtime、执行 action、生成 observation、写入 action 事件 |
| `AgentRuntime` | runtime 扩展接口 |
| `BuiltinToolRuntime` | 执行当前内置工具：课程、预约、RAG 检索 |
| `McpToolRuntime` | 通过 `McpToolAdapter` 白名单适配器调用 MCP 工具 |
| `WorkspaceRuntime` | 在项目根目录内执行受限的文件、搜索、写入和命令动作 |
| `TrustedActionService` | 生成受信 runtime 的 preview、一次性确认 token 与 execute 入口 |
| `AgentObservation` | 将工具结果标准化为 trace 可消费的 Map |
| `HarnessPayloadSanitizer` | 对审计 payload 做脱敏、截断和摘要 |

## 内置工具

无副作用的查询工具与一个业务写入（预约）直接可用；与 ReAct 循环的分工见 `finish` 的说明。

`BuiltinToolRuntime` 当前支持：

- `query_school`
- `query_course`
- `add_course_reservation`
- `rag_search`

`finish` 不属于 runtime action，它仍由 ReAct service 处理，因为它代表循环结束而不是工具调用。

## Action schema

schema 是模型输出与 runtime 之间的契约：Trust 列决定是否需要 preview/execute 确认通道。

Harness 当前注册的 action：

| Action | Runtime | Trust | Required input |
|---|---|---|---|
| `query_school` | builtin | default | - |
| `query_course` | builtin | default | - |
| `add_course_reservation` | builtin | default | `course`, `studentName`, `contactInfo`, `school` |
| `rag_search` | builtin | default | - |
| `mcp_call` | mcp | trusted | `server`, `tool`, `arguments` |
| `workspace_list_files` | workspace | trusted | - |
| `workspace_read_file` | workspace | trusted | `path` |
| `workspace_search_text` | workspace | trusted | `query` |
| `workspace_propose_patch` | workspace | trusted | `path` plus `content` or `patch` |
| `workspace_apply_patch` | workspace | trusted | `path` plus `content` or `patch` |
| `workspace_run_shell` | workspace | trusted | `command` |

不符合 schema 的 action 会在 runtime 之前被拒绝，并返回 `source=policy` 的 observation。

## Policy and configuration

运行时开关与资源限额的唯一定义点；生产 profile 的默认值是「全关」，开放项需显式配置。

`app.agent-harness` 控制 runtime 边界：

```yaml
app:
  agent-harness:
    trusted-runtime-enabled: true
    disabled-actions: []
    tenant-allowed-actions: {}
    workspace:
      root: .
      write-enabled: true
      shell-enabled: true
      command-timeout-seconds: 10
      max-command-output-bytes: 12000
      max-file-bytes: 20000
      max-search-files: 1000
      allowed-commands: [pwd, ls, rg, git, mvn]
      allowed-git-subcommands: [status, diff, show, log, rev-parse, branch]
    mcp:
      servers:
        local-tools:
          enabled: true
          base-url: http://localhost:7331
          tools:
            search:
              path: /mcp/tools/call
              timeout-ms: 5000
```

Policy guard 会按顺序检查：action 是否注册、是否被全局禁用、租户是否允许、required input 是否齐全、trusted runtime 是否开启、受信 action 是否真的带有 `trustedRuntimeAccess=true`。生产 profile 默认关闭 trusted runtime、workspace 写入和 shell 执行，避免部署后自动暴露本地执行能力。

`/ai/harness/**` 需要 `PERM_AGENT_TRUSTED` 或 `ROLE_ADMIN`。迁移脚本会创建 `agent:trusted` 权限并授予 ADMIN。

## 审计与回放

action 事件的产出规则与消费方式：同一张 `agent_event` 表既支撑 UI 回放，也支撑安全审计。

当 action 带有 `taskId` 与 `stepId` 时，harness 会通过 `AgentWorkflowEngine.emitEvent()` 写入：

- `ACTION_STARTED`
- `ACTION_COMPLETED`
- `ACTION_FAILED`

`ACTION_STARTED` 会包含脱敏后的 `actionInput`；完成事件会包含 observation 摘要，包括 `source`、`status`、`latencyMs` 和 payload keys。
这些事件与 `STEP_STARTED` / `STEP_COMPLETED` 共享同一个 `agent_event` 表，可以通过 workflow task events API 还原执行过程。

## MCP runtime

核心设计：网络目标由服务端白名单（`McpToolAdapter` bean）决定，模型只能选择已适配的 server/tool 组合。

`McpToolRuntime` 不接受模型指定的任意网络目标，而是查找 Spring 容器中的 `McpToolAdapter`：

```java
public interface McpToolAdapter {
    String server();
    String tool();
    boolean supports(String server, String tool);
    Object execute(String server, String tool, Map<String, Object> arguments);
    Object execute(Map<String, Object> arguments);
}
```

默认提供 `HttpMcpToolAdapter`：只会调用 `app.agent-harness.mcp.servers` 中显式配置且启用的 server/tool，按 JSON-RPC `tools/call` 形式发送请求。HTTP 非 2xx、超时或解析异常都会变成 `source=mcp`、`status=error` 的 observation，而不是抛出到 ReAct 循环外。

## Workspace runtime

六个动作覆盖读/搜/写/命令四类能力；下面的「安全边界」清单是本节的重点，逐条对应 `WorkspaceRuntime` 的实现约束。

`WorkspaceRuntime` 的第一版能力：

- `workspace_list_files`
- `workspace_read_file`
- `workspace_search_text`
- `workspace_propose_patch`
- `workspace_apply_patch`
- `workspace_run_shell`

安全边界：

- 所有路径都必须留在配置的 workspace root 内。
- 所有 workspace action 都要求 `trustedRuntimeAccess=true`。
- `workspace_propose_patch` 生成统一 diff，`workspace_apply_patch` 可以应用统一 diff 或写入完整 content。
- shell 不经过系统 shell，而是 `ProcessBuilder` 直接执行 tokenized command。
- shell 仅允许配置中的命令族与 `git` 子命令；默认允许 `pwd`、`ls`、`rg`、`git`、`mvn`。
- shell timeout、输出截断、文件大小、搜索文件数都来自 `app.agent-harness.workspace`。

生产环境如果要开放更强的文件或 shell 能力，下一步应把 `WorkspaceRuntime` 移到独立 sandbox 服务中。

## Evaluation

测试矩阵与风险面的对应关系：每一类 runtime/边界都有对应用例，末条的 harness evaluation 是四类路径的端到端固定评估。

Harness 测试覆盖：

- schema 校验与 policy 拒绝
- 内置工具 runtime
- MCP adapter dispatch
- workspace 读/搜/写/命令边界
- trusted action preview / execute API
- unified diff propose / apply
- HTTP MCP adapter
- 审计事件脱敏和 observation 摘要
- 固定 harness evaluation：builtin、MCP、workspace、policy 四类路径

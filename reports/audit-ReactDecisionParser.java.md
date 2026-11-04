# 缺陷审查报告：ReactDecisionParser.java

- **审查对象**：`src/main/java/com/enterprise/iqk/service/ReactDecisionParser.java`（216 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`ReasonDecision` record（本文件内定义，字段与两个调用方的用法对齐）；调用方核实全部 3 处：`ReactAgentService`（`:323` parse / `:326` fallback / `:73`、`:197` extractAnswerFromRaw，**关键上下文**：`:322-326` 用 `catch (RuntimeException)` 包裹整个模型调用 + parse）、`WorkflowReactAgentService`（**未使用本类**，自带第二份实现 `parseDecision:355-379`/`fallbackDecision:381-405`/私有 `ReasonDecision:496`）；下游动作执行面 `BuiltinToolRuntime`（`SUPPORTED_ACTIONS:24-28`、`executeRagSearch` 的 `"query"` 键约定与兜底决策一致）、`ActionSchemaRegistry`；模型输出格式契约以 `ReactAgentService.reason` 的 planning prompt（`:290-298`）为基准
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm/mvn 操作
- **关联报告**：`reports/audit-ReactAgentService.java.md`、`reports/audit-WorkflowReactAgentService.java.md`（M-3/L-2 涉及其自研解析器）、`reports/audit-AgentWorkflowEngine.java.md`

---

## 概览

该类是 ReAct 决策解析与降级兜底的单一组件：从模型原始输出提取 JSON → 白名单校验动作 → 构造 `ReasonDecision`；planner 不可用时按关键词给出"确定性"兜底决策；另暴露 `extractAnswerFromRaw` 供最终答案消毒。做对的部分：

- 动作白名单收敛：未知/缺失动作一律强制 `finish`（`:32-35`），幻觉出的动作名不会进 executor。
- 解析失败有两级挽救：先从 raw 提取 `answer` 字段（含 `action_input.answer` 嵌套与正则兜底，`:144-177`），避免把决策 JSON 原样抛给用户——意图明确且有注释。
- 兜底决策全部置 `fallback=true`（`:115-125`），调用方可追踪降级状态并计入指标。
- 无共享可变状态，线程安全；兜底 `rag_search` 的 `Map.of("query", prompt)` 与 `BuiltinToolRuntime.executeRagSearch` 的 `stringVal("query", prompt)` 键约定一致。
- `extractJson` 提不到内容时返回空串触发结构化 fallback，而非抛异常。

但解析健壮性（大括号截取、`convertValue` 异常、反转义）与兜底规则质量（关键词误路由、双实现漂移）存在实际问题。以下发现均不需变更任何对外方法签名即可修复。

---

## 中严重度

### M-1 `convertValue` 抛出的 `IllegalArgumentException` 未捕获，被迫依赖调用方粗粒度 catch，挽救链被跳过

- **位置**：`ReactDecisionParser.java:36-39`（`objectMapper.convertValue(node.path("action_input"), ...)`）、`:49`（只 `catch (JsonProcessingException)`）；调用方证据：`ReactAgentService.java:322-326`（`catch (RuntimeException)` 包住模型调用与 parse，异常统一落到 `decisionParser.fallback(request.getPrompt())`）
- **描述**：`convertValue` 对不可兼容的目标类型抛 `IllegalArgumentException`——模型输出 `"action_input": "查校区"`（标量）或数组时，`readTree` 成功但 `:36` 抛 IAE，**不属于** `:49` 捕获的 `JsonProcessingException`，parse 整体抛出。该异常被调用方的粗粒度 catch 兜住后走的关键词 fallback，而不是 parser 自己的挽救链（`finishWithAnswer` → `extractAnswerField`）——一个只坏了 `action_input` 字段的决策 JSON 本可以救出 thought/answer，现在直接降级为关键词匹配。同一输出在 `WorkflowReactAgentService` 的自研解析器（`:355-379`，catch 更宽）下行为又不同，两条服务路径的降级语义已经分叉。
- **触发条件**：planner 输出的 `action_input` 为标量/数组（LLM 不守 JSON schema 是常态，planning prompt 只写了示例没有强约束）。
- **影响**：可挽救的输出被过度降级，用户收到与问题无关的关键词兜底答案；两条 ReAct 链路行为不一致。
- **修复建议**：parse 内补 `catch (IllegalArgumentException)`——将 `actionInput` 置空 Map 继续走白名单判断（action 与 answer 仍可用），或转 `finishWithAnswer`；不必依赖调用方兜底。

### M-2 `extractJson` 首尾大括号截取过于天真，双 JSON/前后杂讯时整段 raw 回显给用户

- **位置**：`ReactDecisionParser.java:183-185`（`indexOf('{')` .. `lastIndexOf('}')`）；后果链 `:27-29` → `:132-138`（`finishWithAnswer` 提取不到 answer 时 `answer = rawModelOutput` 原样返回）
- **描述**：`start = 第一个 '{'`、`end = 最后一个 '}'` 的截取在两类常见输出上失效：① 输出含两个及以上 JSON 块（决策 JSON + 模型附带的示例/说明，或 markdown 里前后各一段 JSON）——截取跨度是"第一块的开头到末块结尾"，整体不是合法 JSON → parse 失败 → `extractAnswerField` 对同一错误跨度再次失败 → **整段 raw 输出（含两块 JSON 与说明文字）被当作用户答案返回**，这正是 `:141-143` 注释宣称要避免的事；② 前置文字含 `{`（如中文括号混排、代码示例）时起点错位。调用方的二次消毒（`ReactAgentService:71-77`）只在答案以 `"{"` 开头时触发，这类"文字前缀 + JSON"的 raw 不以 `{` 开头，不会被拦截。
- **触发条件**：planner 输出任何"JSON 之外还有内容"的形态；随模型与温度配置不同属高频形态。
- **影响**：用户直接看到模型内部决策 JSON/杂讯；若 raw 含提示注入文本则原样透传。
- **修复建议**：`extractJson` 改为字符串感知的深度计数扫描（跳过字符串字面量内的花括号，提取**第一个**完整平衡的 JSON 对象）；同时 `finishWithAnswer` 提取失败时不应回显 raw，改为返回空 answer（调用方 `:74` 已有 summarize 兜底路径会接手生成回答）。

### M-3 `fallback()` 关键词规则误路由、含死关键字，且全项目存在第二份漂移实现

- **位置**：`ReactDecisionParser.java:64-112`（规则链）；死关键字证据：`"校区"` 同时出现在 `:64`（首条规则）与 `:72`（第二条），首条短路后 `:72` 的 `"校区"` **永不可达**；漂移证据：`WorkflowReactAgentService.fallbackDecision:381-405`（同套业务关键词的第二份实现，已缺 heat 规则、话术不同）
- **描述**：两个复合问题。其一，规则匹配用极宽的通用词：`"姓名"`、`"联系方式"`（`:72`）命中即返回课程预约字段模板——用户问"授课老师的姓名怎么称呼"也会拿到"预约需包含课程、姓名、联系方式、校区"的答非所问；`"heat"`（`:80`）会命中 "heating"/"heart" 等英文子串。其二，`:72` 里的 `"校区"` 是死关键字，说明规则间已有重叠冲突而无人发现——规则按书写顺序短路、无优先级说明，任何人增删规则都可能静默改变全组行为。第三，这套业务关键词在 `WorkflowReactAgentService` 已复制了一份且开始漂移（少一条规则），同一 planner 故障在两条链路给出不同的兜底答案。
- **触发条件**：planner 模型调用失败（超时/限流/配置错误）+ 用户问题恰好包含宽泛关键词。
- **影响**：降级场景下给出与问题无关的"确定性"中文答案，用户感知为答非所问；两链路兜底行为不一致排障困难。
- **修复建议**：关键词收窄为高区分度词组（"课程预约"、"预约课程"而非"姓名"）；删除死关键字并加注释说明规则顺序即优先级；将 `WorkflowReactAgentService.fallbackDecision` 删除、改为复用本类（本类已是 `@Component`，注入即可）——这是收敛而非扩散。

---

## 低严重度

### L-1 正则兜底的反转义不完整且顺序错误，可破坏含转义反斜杠的答案

- **位置**：`ReactDecisionParser.java:168-171`（依次 `replace("\\n",...)`、`replace("\\\"",...)`、`replace("\\\\",...)`）、`:164-166`（`m.find()` 在整个 raw 上找任意位置的 `"answer"` 键）
- **描述**：三处问题：① 只反转义 `\n`、`\"`、`\\` 三种，`\t`、`\uXXXX`、`\/` 原样保留；② 替换顺序错误——raw 中的 `\\n`（字面反斜杠 + n）会先被 `replace("\\n","\n")` 命中后半段，产出"反斜杠 + 换行"的错误结果，正确的顺序应先处理 `\\\\`；③ `m.find()` 扫描全文本，`thought` 等字符串值内若含 `"answer": "..."` 的示例文字会提取到错误内容。
- **触发条件**：JSON 解析失败 + 答案文本含转义反斜杠（代码/路径类答案）或嵌套示例。
- **影响**：本就罕见的兜底路径产出轻度损坏的文本；影响面小但每次都静默。
- **修复建议**：对正则捕获的 value 片段两侧补引号后交给 Jackson `readValue(String.class)` 反转义（复用标准转义表）；匹配范围先经 `extractJson` 收窄。

### L-2 未知动作强制 finish 时保留无关 `actionInput`，且不尝试从 `action_input.answer` 回填答案

- **位置**：`ReactDecisionParser.java:32-35`（动作强制 finish，但 `:36-39` 仍原样转换并携带 `actionInput`）、`:44`（answer 只取顶层 `answer` 字段）
- **描述**：模型把答案写进 `action_input.answer` 而顶层 `answer` 缺失是常见输出形态（prompt 只要求 finish 时给 answer，模型常不一致）：action=finish 且 `action_input.answer` 有值时，本类返回空 answer → 调用方触发额外的 summarize LLM 调用（`ReactAgentService:70-72`）。同时幻觉动作被强制 finish 后，无关的 actionInput 进入 trace 并随 `completeStep` 落库，污染轨迹数据。
- **触发条件**：finish 决策答案字段错位；或动作名拼写偏差。
- **影响**：额外的模型调用成本；轨迹数据混入无关输入。
- **修复建议**：`action == "finish"` 时若顶层 answer 为空则回填 `action_input.answer`（`extractAnswerField:154-160` 已有同样的嵌套逻辑，可复用）；非法动作的 actionInput 置空。

### L-3 动作白名单三处重复定义，新增动作漏改任意一处即静默强制 finish

- **位置**：`ReactDecisionParser.java:19-21`；对照 `BuiltinToolRuntime.java:24-28`（`SUPPORTED_ACTIONS`）、`WorkflowReactAgentService.java:369`（又一份 `List.of(...)`）
- **描述**：同一动作集在三处硬编码。新增动作时只改 executor 不改本类 → 该动作被 `:33-35` 静默强制为 finish（无日志、无告警），planner 明明输出了合法决策却总是直接结束。`ActionSchemaRegistry`（harness 包）已经是动作 schema 的注册中心，白名单应源于它。
- **修复建议**：本类注入 `ActionSchemaRegistry`（或共享常量类）派生 `ALLOWED_ACTIONS`；至少加 log.warn 记录被强制降级的动作名。
- **补充**：M-3 已述 `fallbackDecision` 的重复——`WorkflowReactAgentService` 与本类合计存在 3 份"决策解析 + 动作白名单 + 关键词兜底"的实现，是同一重构对象。

### L-4 `finishWithAnswer` 的 raw 回显是提示注入直通面

- **位置**：`ReactDecisionParser.java:132-138`（answer 兜底为整段 rawModelOutput）；对照调用方消毒 `ReactAgentService:71-74`（仅拦 `"{"` 开头的疑似 JSON）
- **描述**：非 JSON 且提取不到 answer 的模型输出原样成为用户答案：模型被检索内容/用户输入诱导输出的指令性文本、英文内部推理等直接展示。调用方消毒只覆盖以 `{` 开头的形态，"文字前缀 + JSON"（M-2 的产物）不在此列。
- **触发条件**：planner 输出畸形 + 输出内容含注入文本。
- **影响**：低概率但直接的注入展示面；与 M-2 的修复（提取失败返回空 answer）一并解决。
- **修复建议**：与 M-2 合并处理——提取失败统一返回空串/固定话术，由调用方 summarize 兜底；如保留 raw 回显，至少剥离控制字符并截断。

---

## 修复优先级建议

1. **P0**：M-2（extractJson 深度扫描 + 取消 raw 回显——直接决定用户可见输出质量与注入面）；M-1（补 catch IAE，一行级）。
2. **P1**：M-3（关键词收窄 + 删死关键字；连同删除 `WorkflowReactAgentService.fallbackDecision` 收敛双实现）。
3. **P2**：L-1~L-4 随下次触碰相关代码时清理（L-3 与 M-3 的收敛重构宜一并做）。

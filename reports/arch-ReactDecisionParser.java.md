# 架构分析报告：ReactDecisionParser 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/service/ReactDecisionParser.java`（216 行）
- **核查范围**：该类的全部出边（唯一协作者 `ObjectMapper` 的装配方式）与全部入边（grep 全库核实生产调用方与测试构造点，含 `decisionParser.` 四个消费点逐一定位）；`ReasonDecision` 嵌套 record 的全部引用方；`WorkflowReactAgentService` 自研解析器 `parseDecision`/`fallbackDecision`/私有 record 全文比对；动作白名单的全库平行定义（`BuiltinToolRuntime`、`ActionSchemaRegistry`、workflow 内联 List）逐处核实
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-ReactDecisionParser.java.md`（同文件缺陷审查——IAE 漏捕、extractJson 截取、关键词误路由等以该报告为准，本报告不重复展开）、`arch-ReactAgentService.java.md`、`arch-WorkflowReactAgentService.java.md`（双实现收敛的三处论证之一）

## 1. 模块定位

该类是 ReAct 链路的**决策解析与降级兜底组件**（service 包，`@Component`），三段职责：① `parse`——从模型原始输出提取 JSON → 动作白名单校验 → 构造 `ReasonDecision`（:25-52）；② `fallback`——planner 不可用时按业务关键词给出"确定性"兜底决策（:54-113）；③ `extractAnswerFromRaw`——供调用方做最终答案消毒的 answer 字段抢救（:128-130，含嵌套提取与正则兜底 :144-177）。

它在调用链中的位置是 ReAct 循环的"咽喉"：`ReactAgentService.reason` 的每一步规划输出都经 `parse` 变成可执行决策（`ReactAgentService.java:323`），解析失败/模型异常经 `fallback` 降级（:326）。对外类型面只有两个公开方法加一个嵌套 record `ReasonDecision`（7 字段，:208-215）——该 record 是 service 内 ReAct 引擎各步骤间的流转契约。

## 2. 依赖全景

### 2.1 出边（唯一的注入协作者 + JDK）

| 依赖 | 来源 | 该协作者自身的依赖（已下钻核实） | 方向评价 |
| --- | --- | --- | --- |
| `ObjectMapper` | Spring Boot `JacksonAutoConfiguration` 自动装配 | 零 iqk 依赖（第三方 Jackson databind） | ✅ 纯基础设施叶子 |
| `java.util.regex` | JDK（`extractAnswerField` 内联使用 :164-166） | — | ✅ |

**iqk 包出边：零**。本类不 import 任何 `com.enterprise.iqk.*` 类型（除自身包），不注入 properties、不触及 mapper、不调用任何 service/rag/harness 组件——比 `ModelRouter`（尚有 mapper/domain 两条出边）更纯粹，是全库依赖图中**完全孤立的叶子**。白名单里的动作名（:19-21）虽指向 harness 层的执行词汇，但只是字符串字面量，不构成编译期依赖——这恰是 4.1 要论证的问题所在。

### 2.2 入边（生产入边仅 1 处 + 3 个测试构造点）

- **生产**：`ReactAgentService`（同包）是唯一调用方——`:46` 注入字段，四个消费点：`parse`（:323，包裹在 `catch (RuntimeException)` 内）、`fallback`（:326，异常统一落点）、`extractAnswerFromRaw`（:73 流式消毒、:197 聚合消毒），另 `:31` import `ReasonDecision` 类型。
- **关键否定性证据**：`WorkflowReactAgentService` **不是**调用方——它自带第二份完整实现（见 5.1），未注入本类。
- 测试：`ReactDecisionParserTest`（直接 `new ReactDecisionParser(new ObjectMapper())`，6 个 fallback 用例 + 6 个 parse 用例）、`ReactAgentServiceTest`（:29/:60 两处直接构造后拼进 service）。
- 没有任何包反向依赖本类；harness⇄workflow 包级环（arch-WorkflowReactAgentService 3.1）与本类**零参与**。

### 2.3 横切依赖

- **无**。不读 MDC/租户上下文、不注册 Meter、不发事件、无状态（两个字段：常量白名单 + final mapper），线程安全。`parse`/`fallback` 都是纯函数（仅读入参与配置-free 的字面量），可脱离 Spring 上下文独立测试（测试也正是这么做的）。

## 3. 循环依赖评估

### 3.1 结论：类级与包级均无环；本类是零 iqk 出边的孤立叶子

```
ReactAgentService ─→ ReactDecisionParser ─→ ObjectMapper（Jackson 自动装配，非 iqk）
（WorkflowReactAgentService ─✗ 不引用本类，自带平行实现）
```

- **类级**：入边（1 个同包调用方 + 测试）与出边（Jackson 叶子）无任何交汇，环在结构上不可能形成。Spring 侧构造器注入 + 单一无回指依赖，无 Bean 顺序问题。
- **包级**：本类为 service 包贡献的对外依赖只有 Jackson 一条（且非 iqk 边）。ReactAgentService 所在的 service 包对 agent.workflow 的引用（如 ReactAgentService→harness 类型）与本类无关。
- **Spring 侧**：`ObjectMapper` 由 Boot 自动配置提供，无循环风险。

### 3.2 一个语义耦合备注（非循环，记录性）

`parse` 的异常契约**部分外包给了调用方**：`convertValue` 对标量/数组形态的 `action_input` 抛 `IllegalArgumentException`，不在本类 `:49` 的 `catch (JsonProcessingException)` 内（audit M-1），实际靠 `ReactAgentService.java:322-326` 的 `catch (RuntimeException)` 兜住后转 `fallback`。这不是依赖环，但是一条**隐式的控制耦合边**：本类的降级语义完整性依赖调用方保留那个粗粒度 catch——若调用方收紧异常处理，本类的挽救链（`finishWithAnswer`→`extractAnswerField`）会被静默跳过。修复（本类补 catch IAE）不需要改签名，且能解除这条隐式边。

## 4. 越层调用与边界问题

### 4.1 动作词汇表跨层复制：service 层硬编码 harness 层的执行词汇——中（本报告最重要发现）

`ALLOWED_ACTIONS`（:19-21）是 service 层对 harness 层执行面词汇的**越层知识复制**，grep 证实同一动作集至少四处平行定义：

| 位置 | 形态 |
| --- | --- |
| 本类 :19-21 | `List.of("query_school","query_course","add_course_reservation","rag_search","finish")` |
| `BuiltinToolRuntime.java:24-28` | `Set.of(...)` 四动作（真正的执行方，harness 包） |
| `WorkflowReactAgentService.java:369` | 内联 `List.of(...)` 又一份 |
| `ActionSchemaRegistry.java`（构造器） | 四个 builtin 动作的 schema 注册（含必填/可选字段、read/write 分级）——**已是注册中心** |

后果与方向评价：编译期这不算越层（无 import），但**词汇所有权**属于 harness 执行方，本类作为解析方通过复制与之对齐——新增动作时漏改本类，`parse` 会把合法决策静默强制为 `finish`（:33-35，无日志）；漏改 executor 则出现解析放行、执行不认的相反分叉。`ActionSchemaRegistry` 已经持有每个动作的 schema（连 `rag_search` 的可选字段 `query` 都注册了，与本类 `fallback` 里 `Map.of("query", prompt)` 的键约定、`BuiltinToolRuntime.executeRagSearch` 的 `stringVal("query",…)` 键约定三处对齐），解析层却完全没消费它。**修复方向**（不改 `parse`/`fallback` 签名）：本类注入 `ActionSchemaRegistry`，白名单从 `schemas.keySet()` 派生（或至少提取共享常量 + log.warn 记录被强制的未知动作）。

### 4.2 `fallback` 承载业务话术：解析组件身兼三职——低（职责边界，记录性）

`fallback`（:54-113）里硬编码了业务 FAQ 内容：校区查询引导（:64-71）、课程预约字段模板（:72-79）、高温健康风险要点（:80-87）、无上下文防幻觉话术（:88-95）。一个"决策解析器"同时承担**解析、降级策略、业务话术库**三种职责：话术修订（如预约字段变更）需要改解析组件并越过其测试面；且这套话术在 `WorkflowReactAgentService.fallbackDecision:381-405` 已复制了一份并开始漂移（缺 heat 规则与防幻觉规则、evidence 话术简化为占位句）。这不违层（话术不依赖任何业务 service，是纯字面量），但组件边界被撑宽。**修复方向**：话术外置到配置/资源文件，或把 `fallback` 拆为独立的 `FallbackDecisionProvider`（本类保持解析单一职责，`ReactAgentService` 注入两个组件，`fallback` 方法可保留为转发以不破坏签名）。

### 4.3 异常契约依赖调用方粗粒度 catch——低（见 3.2）

隐式控制耦合：本类挽救链的可达性由 `ReactAgentService:322-326` 的 `catch (RuntimeException)` 保证。两条 ReAct 链路的降级语义已因此分叉——`WorkflowReactAgentService.parseDecision:355-379` 自研版同样只捕 `JsonProcessingException` 且无挽救链，同一畸形输出在两条链路下产物不同（audit M-1 的跨链路印证）。

### 4.4 ObjectMapper 装配语义的测试/生产分叉风险——低（记录性）

生产注入 Boot 全局 `ObjectMapper`，两个测试类各自 `new ObjectMapper()` 裸实例。`readTree`/`convertValue` 对常见全局定制（如 `FAIL_ON_UNKNOWN_PROPERTIES`）不敏感，当前无实际差异；但若全局 mapper 未来开启影响 `readTree` 的特性（如 `ALLOW_TRAILING_COMMA`、`ALLOW_COMMENTS`——这类宽松开关恰恰会改变本类对"模型输出的宽容度"），测试将无法暴露行为变化。记录性观察；若按 4.1 重构触碰本类，可顺手让测试复用 Boot 构造器（`Jackson2ObjectMapperBuilder.json().build()`）。

### 4.5 无越层调用

本类不触及 mapper、不调用任何 service/rag/tools、不读传输层状态——出边面比多数同类组件干净。4.1 的问题是"知识复制"而非调用越层，两者性质不同，修复代价也不同（前者改数据来源，后者改调用结构）。

## 5. 同层平行实现与隐性耦合

### 5.1 与 `WorkflowReactAgentService` 的整套装法重复，且已三向漂移——高（收敛优先级最高）

`WorkflowReactAgentService` 自带本类的完整平行实现，逐项比对（均已通读核实）：

| 能力 | 本类 | workflow 自研版 | 漂移点 |
| --- | --- | --- | --- |
| 决策 record | `ReasonDecision` 7 字段（含 `fallback` 标志，:208-215） | 私有 record 6 字段（:496-498） | 降级标志缺失 → workflow 链路的 trace 无法区分兜底决策 |
| JSON 解析 | `parse` + 挽救链（提取 answer/嵌套/正则，:144-177） | `parseDecision` :355-379，解析失败回显 raw | 无挽救链；raw 回显正是 audit M-2/L-4 要消除的形态 |
| 未知动作 | 强制 finish（:33-35） | 强制 finish（:369-372） | 一致（两份复制） |
| 关键词兜底 | `fallback` 6 条规则（:54-112） | `fallbackDecision` 4 条规则（:381-405） | 缺 heat、缺防幻觉规则；话术已分叉 |
| extractJson | :179-186 | workflow 内另有同名私有方法 | 复制 |

同一 planner 故障在两条链路产生不同的降级答案、不同的 trace 语义，且三处（record/白名单/兜底规则）各自独立漂移中——这是 arch-WorkflowReactAgentService 5.x 论证的 harness⇄workflow 平行实现问题的又一实例，且是其中**收敛代价最低**的一个：本类已是 `@Component`，workflow 侧注入并删除自研三件套即可（record 字段差异可由 `decision.fallback()` 派生补齐）。**修复方向**：`WorkflowReactAgentService` 注入本类，删除 `parseDecision`/`fallbackDecision`/私有 record/`extractJson`/`normalizeAction`/`containsAny` 等私有副本——只改 workflow 文件，本类签名零变化。

### 5.2 动作白名单 + rag_search 键约定的多处复制——见 4.1

四处白名单 + `"query"` 键的执行/解析双端约定（`BuiltinToolRuntime.executeRagSearch`、本类 :100），与 5.1 的组件级重复叠加，构成"决策词汇"这个概念在全库 5+ 处的物理分散。

### 5.3 测试平行构造——低

`ReactAgentServiceTest` 两处 `new ReactDecisionParser(new ObjectMapper())` 而非注入 mock/service：与 4.4 的 mapper 分叉风险同根，收敛方式同 4.4。

## 6. 结论与建议优先级

**总评**：依赖方向是全库最干净的一档——零 iqk 出边的孤立叶子、单一生产调用方、零横切、零环参与，纯函数组件可独立测试。所有问题集中在**知识复制**而非依赖方向：动作词汇表越层复制四份（4.1）、与 workflow 自研解析器的整套装法平行且已三向漂移（5.1）、业务话术内嵌（4.2）。

| 优先级 | 事项 | 章节 | 改动范围 |
| --- | --- | --- | --- |
| 1 | `WorkflowReactAgentService` 删自研三件套，注入本类收敛双实现 | 5.1 | 仅 workflow 文件；本类签名零变化 |
| 2 | 白名单从 `ActionSchemaRegistry` 派生（或共享常量 + 强制降级 log.warn） | 4.1/5.2 | 本类注入 1 个依赖 + 消费方核对 |
| 3 | 本类补 `catch (IllegalArgumentException)`（audit M-1，解除 3.2 隐式耦合边） | 3.2 | 本类一处 catch |
| 4 | fallback 业务话术外置/拆组件 | 4.2 | 本类 + 新资源/组件；`fallback` 保留转发签名 |
| 5 | 测试复用 Boot 构造器消除 mapper 分叉 | 4.4/5.3 | 2 个测试类 |

以上各项均不改变 `parse`/`fallback`/`extractAnswerFromRaw`/`ReasonDecision` 的对外 API 签名；第 1 项删除的是 workflow 侧私有方法（非对外 API），收敛后两条 ReAct 链路的降级语义、trace 的 fallback 标志、挽救链行为自然对齐。

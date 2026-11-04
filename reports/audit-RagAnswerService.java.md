# 缺陷审查报告：RagAnswerService.java

- **审查对象**：`src/main/java/com/enterprise/iqk/rag/RagAnswerService.java`（429 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`RagProperties`（默认值）、`RerankPipeline`（rerank 语义与 `PipelineResult` 字段、degraded 语义）、`MemoryService` 与 `MemoryItemMapper`（全部 SQL，确认 `ORDER BY created_at DESC`）、`ModelRouter`/`TenantCostService` 调用面、`SlotExtractor` 接口；**全部 4 个调用方**——`RetrievalDebugController`（全文）、`PdfController`（chat 调用点）、`BuiltinToolRuntime`（agent 工具调用点）、`EvaluationService`；以及 `SecurityConfiguration`（确认 `/ai/retrieval/**` 的权限落点）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件
- **关联报告**：`reports/audit-EvaluationService.java.md`、`reports/audit-AgentSessionService.java.md`、`reports/audit-WorkspaceRuntime.java.md`

---

## 概览

该类是 RAG 问答主管线（召回 → 两段精排 → LLM 生成 → 引用/证据/轨迹组装 → 三层记忆读写），另含一个不进 LLM 的调试入口 `debugRetrieve`。做得好的部分：租户过滤表达式统一拼接并转义了反斜杠与双引号；记忆读失败/写失败均不阻断主链路（有明确注释）；指标埋点齐全（管线/召回/精排三级 latency）。但存在 **1 个高危跨租户泄露**（调试入口信任调用方传入的 tenantId，且该端点无专属权限规则）与 4 个中危——其中 `topK` 参数被完全忽略会直接破坏它宣称服务的 Hit@K/MRR 离线评测。所有修复均不需变更对外 API 签名。

---

## 高严重度

### H-1 `debugRetrieve` 信任调用方传入的 tenantId，配合无专属权限规则的调试端点 → 任意已认证用户可跨租户拉取语料全文

- **位置**：`RagAnswerService.java:53-57`（`debugRetrieve(prompt, tenantId, …)` 直接使用**调用方传入**的 tenantId 构造过滤表达式，不与 `TenantContext.currentTenantId()` 比对）、`:76-81`（结果 rows 放入 `d.getText()` **全文**，非摘要）；调用链证据：`RetrievalDebugController.java:30`（`tenantId` 取自**请求体**而非当前身份）、`:33`（原样传入）；`SecurityConfiguration.java:72`（`/ai/retrieval/**` 无 `requestMatchers` 规则，落入 `anyRequest().authenticated()`——任何已认证用户均可调，无权限点校验）；`SecurityConfiguration.java:48`（安全关闭的演示模式下 `permitAll`——**完全未认证**即可调用）
- **描述**：`answer()` 的租户来自各调用方传入的当前租户（`PdfController`/`BuiltinToolRuntime`/`EvaluationService` 均传 `currentTenantId()`），而 `debugRetrieve` 唯一的直接调用方从请求体取 tenantId。两层防线同时缺失：service 不校验入参租户 == 当前身份租户，端点不要求任何权限点（对比 `/ai/evaluation/**`、`/ai/sessions/**` 均有专属权限）。返回的还是文档**全文**（`:80` `content` 字段），比 `answer()` 路径 180 字 snippet（`:263-266`）的暴露面大得多。
- **触发条件**：租户 A 的任一已认证用户（或有任意有效 API key 的调用方）：`POST /ai/retrieval/search`，body `{"tenantId":"<受害租户ID>","prompt":"合同 金额","topK":50}` → 返回受害租户最多 `retrievalTopN=50` 条文档全文。tenantId 若非公开可先从报错差异/共享语料探测。演示模式下无需任何凭证。
- **影响**：跨租户机密数据泄露（全文级、可分页式批量枚举）；多租户 SaaS 的核心隔离承诺被一个"调试端点"击穿。
- **修复建议**（按层兜底，建议全做）：① `debugRetrieve` 入口处强制 `tenantId = TenantContext.currentTenantId()`（或入参与 current 不一致即抛 `IllegalArgumentException`）——这是对**未来新增调用方**的永久防线；② `RetrievalDebugController` 改用 `TenantContext.currentTenantId()`，删除 body 透传；③ `SecurityConfiguration` 为 `/ai/retrieval/**` 加专属权限（如 `PERM_EVAL_READ` + `ROLE_ADMIN`）；④ 返回内容降为 metadata + snippet（复用 `evidenceText` 的 180 字裁剪）。

---

## 中严重度

### M-1 `debugRetrieve` 的 `topK` 参数被完全忽略——Hit@K/MRR 离线评测的有效性被静默破坏

- **位置**：`RagAnswerService.java:53`（参数 `topK` 声明后**全程未使用**）、`:58-63`（检索固定用 `ragProperties.getRetrievalTopN()`）、`:196-200`（`rerankWithMetrics` 固定用 `ragProperties.getRerankTopK()`）；调用方证据：`RetrievalDebugController.java:29,33`（从 body 解析 topK 并传入，用户以为在生效）
- **描述**：该入口的注释自述用途是"离线评测（Hit@K / MRR）"（`:50-51`），而这两个指标的定义就是"前 K 命中"——K 由调用方指定。现在无论传什么 K，返回的恒是全局配置 K'=rerankTopK(6) 的结果：评 Hit@10、Hit@20 全部评的是 Hit@6，**指标数值错误且无任何报错**。评测脚本拿到的 MRR/Hit@K 系统性偏差，可能直接误导检索调优决策。
- **触发条件**：任何 `POST /ai/retrieval/search` 带 `topK != 6` 的请求——即评测 harness 的常规用法，必现。
- **影响**：以该端点为数据源的所有离线检索评测结论不可信。
- **修复建议**：`rerankWithMetrics` 增加 finalTopK 入参（内部签名调整，不影响对外 API），传 `Math.min(Math.max(1, topK), 检索上限)`；同时在 javadoc 标注 topK 与 retrievalTopN 的约束关系（topK > 召回量时只能得到召回量条）。

### M-2 rerank 过滤后为空无兜底 → "无引用的裸回答"伪装成 RAG 结果

- **位置**：`RagAnswerService.java:110`（空判断只针对一级召回 `retrieved`）、`:120-123`（`selected = pipeline.documents()...limit(...)` 可为空列表）、`:125`（context 拼成空串）、`:152`（outcome 记 `"success"`）
- **描述**：`RerankPipeline.rerank` 的粗排/过滤可将候选全弃（其 degraded 语义只覆盖"精排超时降级"，不保证非空）。此时 `selected` 为空 → LLM 在无上下文条件下凭参数记忆作答，citations/evidence 为空列表但接口语义仍是成功。用户看到的是一段没有引用来源的流畅回答，而一级召回明明有文档。空召回路径（`:110-118`）有固定话术兜底，空精排路径没有。
- **触发条件**：候选文档与查询相关性均低于粗排阈值（冷门问法、共享语料占比高的会话）。
- **影响**：幻觉回答以 RAG 名义输出且无引用可核；评测侧这类 case 会被计为 citationCoverage=0 的"成功"，拉低指标却查不到原因。
- **修复建议**：`selected.isEmpty()` 时回退使用 `retrieved` 的前 `rerankTopK` 条（保底引用），或与空召回同样返回固定话术；trace 中记录 `postRerankEmpty=true` 便于定位。

### M-3 token 计量与预算断言漏算 system 提示、knownInfo 与对话历史——成本少记、预算闸门有盲区

- **位置**：`RagAnswerService.java:127`（`estimateTokens(prompt + "\n" + context)` 只计用户问题+上下文）、`:132`（实际 system 还拼入 `RAG_ANSWER_SYSTEM + knownInfo`）、`:134`（`CONVERSATION_ID` advisor 会向请求注入**整段对话历史**）、`:128`（输出预估硬编码 600）
- **描述**：实际发送给 LLM 的 input = system+knownInfo+对话历史+user 消息，计量只覆盖最后一部分。对话历史随会话轮数线性增长，长会话下实际 input tokens 可达计量值的数倍：`assertBudget`（`:128`）放行了本应拦截的请求，`recordUsage`（`:138`）少记成本 → 租户预算与成本报表双双失真。输出侧 600 的魔法数与模型实际 maxOutputTokens 无关联。
- **触发条件**：多轮长会话 + 预算临近阈值的租户——预算闸门最该生效的场景恰是它最失准的场景。
- **修复建议**：计量输入改为 system+knownInfo+context+prompt 的完整拼接（advisor 注入的历史无法精确预估时，至少按会话轮数加经验系数并注明）；输出预估值提为配置项。

### M-4 long 记忆"读-合并-写"无并发控制，同会话并发请求互相覆盖丢槽位

- **位置**：`RagAnswerService.java:346-354`（`queryLongMemory` → `slotExtractor.merge` → `saveLongMemory`，典型 check-then-act，无版本号/锁）
- **描述**：同一用户同一时刻两条并发消息（前端连发、双开标签页、agent 与 chat 并行调用）：两个线程都读到旧槽位快照，各自合并自己的新槽位后保存——后写覆盖先写，先到的槽位**静默丢失**。long 记忆是后续所有对话的【已知信息】来源（`:315-321`），丢失后用户需反复重述偏好。
- **触发条件**：同 (tenant, userId) 的并发问答；流式 UI 快速连发消息时常见。
- **影响**：长期记忆慢性失真，无报错无痕迹。
- **修复建议**：save 走条件更新（`WHERE memory_key=… AND version=…`）失败重读重试一次；或对 (tenant,userId) 记忆写做串行化（如按 userId 的单线程 executor）；至少在 merge 后日志记录合并前后差异便于发现覆盖。

---

## 低严重度

### L-1 记忆读、写两处 catch 全静默，故障不可见

- **位置**：`RagAnswerService.java:331-334`（`catch (Exception e) return ""` 无日志）、`:377-379`（`catch (RuntimeException e) {}` 空块，注释只解释了为什么捕 RuntimeException）
- **触发条件**：记忆表故障/字段超长时，knownInfo 永远为空、记忆永不落库，运维与用户均无感知。修复建议：`log.warn` 携带 tenantId/chatId/异常摘要（限频或采样，避免反噬延迟）。

### L-2 记忆压缩"先删后存"非原子，且摘要会递归嵌套

- **位置**：`RagAnswerService.java:372-375`（先循环 `deleteMemory` 删最旧两条，**之后**才 `saveShortMemory(summary)`——save 失败/进程中断则旧记忆已删、摘要未落，两头发空）；`:370-371`（摘要条目以普通 short 身份回流，下轮压缩再次包裹"用户已确认：…此前问过：用户已确认：…"无限嵌套）；`:382-385`（`titleOf` 只保留 30 字，压缩即永久丢原文且无指向原始内容的引用）
- **触发条件**：short 记忆超过 6 条后的每轮问答。修复建议：先 save 摘要成功、再删旧条目（顺序对调即可消除丢失去）；摘要条目加代数标记防嵌套；titleOf 截断适当放宽（60 字）。

### L-3 LLM 返回 null content 时拼出字面 `"null"` 回答

- **位置**：`RagAnswerService.java:129-136`（`chatClient...call().content()` 在模型空响应时可为 null）、`:150`（`answer + formatCitationFooter(citations)` 字符串拼接 → `"null\n\n---\n引用来源:…"` 原样返回用户）；空串回答（`""`）同样不拦截、不标失败
- **触发条件**：模型偶发空响应/被安全策略拦截输出。修复建议：`answer = emptyIfBlank(answer)`，为空时按失败口径返回固定话术并在 outcome 中单独计数（如 `"empty_answer"`），与 `HybridRagAnswerService` 侧评测口径对齐。

### L-4 `currentUserId` 在无 SecurityContext 的线程里静默切换记忆身份键

- **位置**：`RagAnswerService.java:299-306`（取不到 `AuthIdentity` principal 时退化为 `"chat:" + chatId`，无日志）
- **触发条件**：`answer()` 被异步线程/调度任务调用（未来评测异步化、后台批处理）时 SecurityContext 为空 → 同一用户的记忆从"按身份"突然切到"按会话"分叉，历史【已知信息】消失。修复建议：降级路径记 `log.info`；或在 `answer` 签名不变的前提下由调用方显式传 userIdOverride（新增可选入参，不改既有签名）。

### L-5 `citationCard` 细节缺陷：`.PDF` 不剥后缀、非法 chunk_index 归 0、180 字截断可切断代理对

- **位置**：`RagAnswerService.java:238-240`（`endsWith(".pdf")` 大小写敏感，`.PDF` 文件名显示带后缀）、`:252-262`（`chunk_index` 非数值时统一落 0——多条引用 chunk 全为 0，编号歧义且掩盖元数据问题）、`:263-266` 与 `:271-275`、`:382-385`（`substring(0, 180/30)` 按 char 截断，emoji 等增补平面字符被切成孤立代理项，前端显示乱码）
- **触发条件**：元数据不规范或正文含 emoji。修复建议：`toLowerCase(Locale.ROOT).endsWith(".pdf")`；解析失败时置 null 而非 0（`chunkIndex` 改 `Integer`）；截断用 `codePoint` 感知方式（`offsetByCodePoints` 或按 `String.codePoints()` 收集）。

### L-6 过滤表达式三处复制，转义面依赖单点 `escapeFilterValue`

- **位置**：`RagAnswerService.java:55-57` 与 `:99-101`（同一段 `"tenant_id == … && (chat_id == … || chat_id == shared)"` 两份拷贝；`HybridRagAnswerService.java:55-57` 还有第三份）、`:391-394`（转义仅覆盖 `\` 与 `"`，向量库 filter DSL 的其他元字符是否安全取决于后端实现）
- **触发条件**：任一处改动（如共享语料逻辑调整）漏改其余两份 → 检索行为分叉；当前转义对主流后端基本够用，但属"一次遗漏即注入面"的脆弱结构。修复建议：抽 `buildTenantChatFilter(tenantId, chatId)` 私有方法（或工具类），三处统一；对 chatId 增加白名单字符集校验（调用方 `PdfController.sanitize` 只去单引号，`"` 之外的注入面全靠此处单点转义）。

### L-7 记忆读写同步串在回答主链路尾部，放大尾延迟

- **位置**：`RagAnswerService.java:342-380`（每次问答后同步执行：读 long → 写 long → 写 short → 读 short×1 → 条件性 delete×2+save，最多 7 次记忆库往返，全部计入用户已等待的响应时间）
- **触发条件**：记忆库慢（同库高负载）时，P99 延迟被无 LLM 关联的记忆 IO 抬高。修复建议：记忆写入移出主链路（请求完成后异步执行，需先解决 M-4 的并发模型）或合并读写次数。

### L-8 杂项：硬编码阈值与限定名写法

- **位置**：`RagAnswerService.java:128`（输出预估 600，见 M-3）、`:323`（fact 置信阈值 `0.8` 硬编码，注释口径在 spec-C4）、`:143`、`:279-280`、`:364`（`new java.util.ArrayList<>()`、`java.util.Map` 等全限定名混用，同文件其余处均用 import，风格分裂）
- **触发条件**：阈值调整需改代码重新发布；可读性问题。修复建议：0.8 提为 `RagProperties` 配置项；全限定名改 import。

---

## 修复优先级建议

1. **H-1** 立即处理：service 入口强制当前租户 + controller 删除 body 透传 + 补权限规则，三处都是小改动，且应在下一次发布前完成（演示模式 `permitAll` 下为完全未认证泄露）。
2. **M-1**（topK 忽略）直接决定离线评测结论可信度，修复成本极低，建议随 H-1 一并修——两者都落在 `debugRetrieve`。
3. **M-2**（空精排兜底）与 **L-3**（null answer）同在回答组装路径，一次改动覆盖；**M-3**（计量口径）可与 L-8 的配置化一起做。
4. **M-4 + L-7 + L-1**（记忆子系统：并发模型、异步化、可观测性）建议作为一次独立重构，动记忆就一并解决三者。
5. L-2/L-4/L-5/L-6 按顺手处理，L-6 的"三份拷贝合一"最能防止未来回归。

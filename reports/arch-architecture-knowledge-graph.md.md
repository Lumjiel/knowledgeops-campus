# 架构分析报告：architecture-knowledge-graph.md 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码与文档；不运行任何 build/mvn；全部结论来自静态交叉审查，每条结论附文档行号与代码位置证据）
- **分析对象**：`docs/architecture-knowledge-graph.md`（93 行：选型理由 :1-10 + 表结构 :12-40 + 种子数据 :41-53 + 查询能力 :55-77 + GraphService API :79-87 + 扩展路径 :88-93）
- **核查范围**：文档「依赖面」五节逐条对码——表结构字段表对 `V11__knowledge_graph_tables.sql` 全文（1-53）；种子数据 7 条关系逐条对 V11 :56-73；查询能力 3 段 SQL 样例对 `KgEntityMapper`/`KgRelationMapper`/`KgFactMapper` 全文；GraphService API 表 4 方法签名对 `GraphService.java` 全文（83 行）；扩展路径 3 条对全库写入路径 grep（`kg_entity|kg_relation|kg_fact` 全仓命中清单、graph 包 insert/save/update/delete 零命中、mapper 包外引用零命中）；消费面 grep（`GraphService`/`GraphRetriever` 全部调用方、`HybridRetrievalService` 四源编排 :95、`HybridWeights` 权重预设）；入链核查（`docs/index.md:60`、`学习手册/02:134`、`学习手册/06:190`）
- **约束遵守**：未改源码与文档、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（所有修复建议均为文档侧编辑）
- **关联报告**：`audit-architecture-knowledge-graph.md.md`（同文件缺陷审查一手记录）；`arch-HybridRetrievalService.java.md`（graph 源打分刻度 0.85/0.7 的逐源核查、completeOnTimeout 不取消任务）；`arch-architecture-hybrid-retrieval.md.md`（graph 作为第四源的编排层全貌，已反向引用本报告结论）；`arch-EvaluationService.java.md`（垂直模块自持 mapper 惯例的判定依据）；`arch-RagAnswerService.java.md`（escapeFilter 双副本问题——graph 包用共享 `SqlLikeUtils` 的正面对照）

## 1. 模块定位

本文档是**图谱子系统的域设计文档（存储选型 + 域模型类）**：交付三类契约——①MySQL 邻接表选型理由与迁移触发条件（:1-10），②三表域模型 + 种子数据（:12-53），③查询能力与 GraphService API（:55-87）。

**对齐度定性（本报告主线）**：这是同系列架构文档中**「代码事实层」对齐度最高的一份**——种子数据 7 条关系逐条精确（2.1 #2）、GraphService API 表 4 方法签名逐参全真（与 api-recipes 的方法断裂、hybrid-retrieval 的端点虚构形成对比）、三表与 DDL 一一对应、依赖方向图本身零失实。漂移集中在**「能力边界层」**：文档把一个「只有读路径、无任何写入器、数据恒为迁移种子、只对 public 租户可见」的静态演示图，呈现成了一个有 API 可查、weight 有语义、扩展路径在自动填充的活性子系统。结构侧结论则完全是正面的：**graph 包是全库最干净的依赖汇点之一**（§3/§4），零环、零越层、零平行实现。

## 2. 依赖全景

### 2.1 出边对码表（文档依赖面五节逐条）

| 文档声称 | 位置 | 核实结果 |
| --- | --- | --- |
| MySQL 邻接表，零新增基础设施；一跳/两跳查询足够（:5-7） | pom 无图库依赖 + V11 三表 | ✅ 选型与实现一致（6.6 L-1：实现上界实为一跳） |
| kg_entity 字段表：entity_id/name/type/aliases/description/source_id（:15-22） | V11:1-16 | ⚠️ 列出的 6 字段全真；**漏 tenant_id（NOT NULL DEFAULT 'public'，V11:4）、metadata_json、created_at/updated_at**——其中 tenant_id 是隔离键，缺失使整份 schema 章呈现「无租户视图」（6.2 M-1） |
| kg_relation 字段表：source/target/relation_type/weight/evidence_id（:26-31） | V11:18-33 | ⚠️ 同上漏 tenant_id/relation_id/metadata_json；**weight 声明为 (0-1) 但全库无打分消费者**（6.3 M-4） |
| kg_fact 字段表 SPO/confidence/valid_from/valid_to/source（:35-39） | V11:35-53 | ⚠️ 同上漏 tenant_id/fact_id；表本身真实存在但**全库零写入者、恒为空表**（6.1 H-1） |
| 种子数据 7 条关系（:46-53） | V11:66-73 | ✅ **逐条精确**：方向、relation_type、weight（0.8/1.0）与 7 实体清单（V11:56-63）全部一致；未声明：种子全部落 `tenant_id='public'`（V11:57-72）且 INSERT IGNORE 无幂等键语义说明（6.2 M-1） |
| 实体搜索 SQL 样例 name LIKE OR JSON_CONTAINS（:58-62） | KgEntityMapper.searchByName | ✅ 语义一致且实现更严谨（JSON_QUOTE 规范化 + 服务层 SqlLikeUtils.escapeForLike 转义，GraphService:30）；样例**无 tenant_id 谓词**，与 :83 API 签名自相矛盾（6.5 L-2） |
| 一跳邻居 SQL：单向 JOIN target 方向（:64-70） | KgRelationMapper.findRelations + GraphService:36-56 | ⚠️ 实现是**双向 OR + direction IN/OUT 标记**（GraphService:42-51）——文档展示的能力弱于实现；对种子 rel-7（Java→Spring PREREQUISITE_FOR），文档 SQL 从 Spring 侧查不到这条前置关系，实现可以（6.4 M-3） |
| 事实检索 SQL：subject/object LIKE + confidence 降序（:72-77） | KgFactMapper.searchByKeyword | ✅ 排序与谓词精确（mapper 另有 LIMIT）；但查询对象是恒空表，该能力**当前不可触发**（6.1 H-1）；样例无 tenant_id 且无 LIMIT（6.5 L-2） |
| GraphService API 4 方法签名（:83-86） | GraphService.java:28/:36/:61/:69 | ✅ **签名逐参全真**（同系列文档 API 章最佳）；但全服务无 HTTP 门面、无工具消费——唯一调用方是 GraphRetriever，且 `getEntitiesByType` 全库零调用（6.3 M-2） |
| 扩展路径 1：从 PDF chunk 抽取实体关系「自动填充」kg_entity/kg_relation（:90） | 全库写入路径 grep | ❌ **零写入者**：graph 包内无任何 insert/save/update/delete，三个 mapper 在包外零引用，IngestionService 无图谱钩子——kg_* 唯一写入是 V11 种子本身（6.1 H-1） |
| 扩展路径 2/3：课程图谱 / 多跳推理 2-3 跳（:91-92） | — | ✅ 合格的未来时表述（有明确触发条件 :10），无失实；注意两跳在现状中不存在（6.6 L-1） |

### 2.2 真实依赖链（grep 实证）

```
agent.research.DeepResearchService ─┐
rag.HybridRagAnswerService ─────────┤→ retrieval.HybridRetrievalService (:95 编排第四源)
                                    │      └→ retrieval.GraphRetriever
evaluation.EvaluationService ───────┘             └→ graph.GraphService（searchEntities :38 /
                                                      getNeighbors :42 / searchFacts :58）
                                                         ├→ KgEntityMapper / KgRelationMapper / KgFactMapper（包内自持）
                                                         │    └→ V11 三表（读）
                                                         └→ util.SqlLikeUtils.escapeForLike（:30/:63）
```

graph 包（7 文件：GraphService + 3 mapper + 3 record）出边**仅两项**：MyBatis-Plus BaseMapper 与 `util.SqlLikeUtils`。零回指、零横切。

## 3. 循环依赖评估

**结论：无环，且是依赖方向的正面样本。**

- Bean 级：GraphService ← GraphRetriever ← HybridRetrievalService ← {DeepResearchService, HybridRagAnswerService}，单链无回边。
- 包级：`graph` 不 import `retrieval`/`rag`/`agent` 任何包；反向边全部存在且单向（agent → retrieval → graph → util/persistence）。与 `agent` 子系统的 workflow⇄harness 包级环（arch-WorkflowReactAgentService / arch-ReactAgentService 已记）形成对照——**图谱域没有因为「被检索编排复用」而反向依赖编排层**，GraphRetriever 正确地放在 retrieval 侧（检索器是编排概念），graph 包因此保持纯域。
- 生命周期耦合：kg_* 表无事件、无回调、无跨域写者，不存在隐性运行时耦合（对照 arch-EvaluationService 的 resolveChatId 跨 run 污染——同库不同病的反面样本）。

## 4. 越层调用评估

**结论：无越层。** 三项正面证据：

1. **租户显式传参**：GraphService 全部 4 方法以 `tenantId` 为首参（:28/:36/:61/:69），不读 SecurityContext/TenantContext ThreadLocal——是 arch-AgentSessionService 确立的全库正确样板在图谱域的延续，与 ReactAgentService 在 Flux.defer 内读 MDC 的结构性缺陷（arch-ReactAgentService M-2）零同款。
2. **门面纪律**：GraphRetriever 不直连 mapper，一律经 GraphService（对照检索包内 VectorRetriever 直连 VectorStore 的模式——图谱域未复制该扁平化）。
3. **mapper 自持 + 租户谓词内聚**：3 个 mapper 的每条 @Select 都带 `tenant_id = #{tenantId}`，符合「垂直模块自持 mapper」惯例（arch-EvaluationService 已确立该惯例判定）；LIKE 转义用共享 `SqlLikeUtils` 而非包内副本——检索包 escapeFilter 逐字双副本问题（arch-RagAnswerService/arch-HybridRetrievalService 已记）**在图谱域没有第三副本**。

唯一可议处不是越层而是**暴露面缺位**：GraphService 无 controller、无 agent 工具注册（BuiltinToolRuntime/HttpMcpToolAdapter 均无图谱工具），图能力只作为混合检索第四源内部消费——API 章节的存在让读者预期一个不存在的查询入口（6.3 M-2）。

## 5. 平行实现核查

**结论：零平行实现。** `kg_entity|kg_relation|kg_fact` 与 `GraphService|GraphRetriever` 全仓 grep 仅命中 graph/retrieval 包与 V11 一处；学习手册 06（:189-190）对 GraphRetriever 行为的描述与代码一致且自带行号（它引用了固定分 0.85 与置信度默认 0.7 的事实）；文档 SQL 样例与 mapper SQL 是「示意 vs 实现」关系而非第二实现。与 ReactDecisionParser 三重实现、sharedCorpusChatId 5 处复制等存量漂移对照，图谱域是干净的。

## 6. 发现明细

### 6.1 H-1 「自动填充」声明的写入路径不存在——图数据生命周期无人闭环

- **文档位置**：:90（扩展路径 1）
- **证据**：graph 包 grep `\.(insert|save|update|delete)` 零命中；`Kg*Mapper` 在 graph 包外零引用；IngestionService 全文无图谱钩子。kg_* 唯一写入 = V11 :56-73 的 INSERT IGNORE 种子。
- **影响**：①kg_fact 恒空 → searchFacts（GraphService:61-64）与 GraphRetriever 事实分支（:58-69）是生产不可达代码路径，`f.getConfidence() != null ? ... : 0.7` 的兜底（:67）永不可达；②文档「扩展路径」用未来时没错，但「自动填充」的现在时读感 + 通篇无「当前无写入器」声明，读者无法判断图谱内容会不会更新——选型理由 :7「当前业务场景主要是实体搜索和一跳邻居」部分承认了这一点，但两处相距 80 行且不互相援引。
- **修复建议**（文档侧）：在扩展路径 1 加一句现状声明（「抽取写入器当前未实现，表数据 = 迁移种子」）；或在 :7 选型理由处内联说明。这是 audit 报告「kg_* 除迁移种子外零写入者」发现的架构定性：不是缺陷待修，是**读路径先行、写路径整体缺位的阶段状态**，文档有义务标明。

### 6.2 M-1 租户边界以「无租户视图」呈现：字段表漏 tenant_id + 种子锁死 public + uk 键全局化

- **文档位置**：:15-39（三张字段表均无 tenant_id）；:46-53（种子未标租户归属）
- **证据**：V11:4/:21/:38 每表 `tenant_id VARCHAR(64) NOT NULL DEFAULT 'public'`；V11:56-72 种子行全部显式 `'public'`；uk_kg_entity_id/uk_kg_relation_id/uk_kg_fact_id（V11:13/:29/:49）均**不含 tenant_id**——entity_id 是全局唯一命名空间，非租户域命名空间。
- **影响**：①GraphService API 章每个方法都带 tenantId（:83-86），schema 章却无租户列，两章拼不出真实的租户模型；②非 public 租户的图谱恒空（种子不可达），多租户部署下 GraphRetriever 第四源空转但计量上仍是 success/empty 正常出口，无告警面；③「按租户扩展图谱」不是插数据即可——需先改三个唯一键为 (tenant_id, *_id) 复合键，这是 schema 级前置，文档「可迁移」叙事（:8）未覆盖。
- **修复建议**（文档侧）：字段表补 tenant_id 行；种子节注明「种子归属 public 租户，其他租户图谱为空」；扩展路径加一条「租户化：唯一键改造为复合键」。与 audit 报告的 tenant_id 三连发现互为印证，本报告补足的是「uk 键全局化 → 租户化是 DDL 变更而非数据变更」这一边界结论。

### 6.3 M-2 GraphService API 有实现无门面：getEntitiesByType 是死 API，searchFacts 是不可触发能力

- **文档位置**：:79-87（API 章）
- **证据**：GraphService 4 方法中 3 个的唯一调用方是 GraphRetriever（:38/:42/:58）；`getEntitiesByType`（:69-71）全库零调用；全仓无任何 controller 引用 GraphService，agent 工具面（BuiltinToolRuntime 等）无图谱工具。
- **影响**：API 章呈现为一个可独立使用的能力面，实况是包内私有消费 + 一个死方法。签名全真（正面）但**暴露面未声明**，与 hybrid-retrieval 文档「对着无 HTTP 的服务写 API 章」同型、程度更轻（此处服务与方法都真实存在）。
- **修复建议**（文档侧）：API 章加一行「当前消费方：混合检索 GraphRetriever；无 HTTP 暴露」；getEntitiesByType 注明「预留，暂无调用方」（或从表中移除）。代码侧若未来接 controller，签名无需变更。

### 6.4 M-3 一跳邻居 SQL 样例是单向 JOIN，实现是双向——文档能力下界恰好查不到最有业务价值的种子关系

- **文档位置**：:64-70
- **证据**：文档 SQL `WHERE r.source_entity_id = 'ent-course-java'` 只查出边；实现 findRelations 为 `(source = ? OR target = ?)`（KgRelationMapper）+ GraphService:42-51 补 IN/OUT 方向标记。
- **影响**：对种子 rel-7（`Java编程实战 --PREREQUISITE_FOR--> Spring Boot微服务`），按文档 SQL 从 Spring 侧（学习者视角最自然的问法「学 Spring 前要学什么」）查不到该前置关系；实现可以。图类文档特有检查项（audit 已记方向语义差异），架构侧定性：**文档样例固化了「只查出边」的调用姿势**，未来照文档写新消费方会系统性丢入边。
- **修复建议**（文档侧）：样例改为双向或注明「实现含反向边（direction IN/OUT）」。

### 6.5 L-2 三段 SQL 样例均为无租户、无转义、无 LIMIT 的理想化形态

- **文档位置**：:58-77
- **证据**：实现侧每条 mapper SQL 带 `tenant_id` 谓词、服务层过 `SqlLikeUtils.escapeForLike`（GraphService:30/:63）、searchByName/searchByKeyword 带 LIMIT（:62-77）；文档样例三样皆无。
- **影响**：轻度——样例是能力示意，真实约束在 API 签名（tenantId 参数）里已可见；但与 M-1 同根，继续强化「全局图」误读。
- **修复建议**：样例 SQL 补 `tenant_id = ?` 谓词即可，转义/LIMIT 可不展开。

### 6.6 L-1 选型理由引用了不存在的两跳能力

- **文档位置**：:7（「一跳/两跳查询足够」）
- **证据**：全库无两跳查询（findRelations 单层 OR，无递归 CTE，无应用层二次遍历）；扩展路径 3（:92）确认多跳是未来项。
- **影响**：陈述选型理由时把两跳当作现有能力参照；若「一跳不够」的场景出现，读者会以为两跳已可用。轻度，因 :10 的迁移触发条件（>3 跳）是自洽的。
- **修复建议**：改为「一跳查询已覆盖当前场景（两跳为扩展路径）」。

### 6.7 正面项清单（保留项）

1. 种子数据 7 条关系与 V11 :56-73 逐字对码成立——全系列文档中种子数据对齐的最佳样本。
2. GraphService API 表 4 方法签名逐参全真（对照 api-recipes 的方法断裂、hybrid-retrieval 的端点虚构）。
3. 依赖方向图（§2.2）零失实：文档未画依赖图所以无图可错，但通篇描述与真实链路一致。
4. 选型理由可证伪（:10 给出明确迁移触发条件）、「可迁移」声明与邻接表模型相符。
5. 实现层比文档样例更严谨（JSON_QUOTE、escapeForLike、LIMIT、双向查询）——文档欠声明而非实现欠账。

## 7. 修复优先级建议

| 优先级 | 项 | 动作 |
| --- | --- | --- |
| 1 | H-1 | 扩展路径 1 补「当前无写入器，数据 = 迁移种子」现状声明（一句话修复，消除最大误读） |
| 2 | M-1 | 字段表补 tenant_id + 种子节标注 public 归属 + 扩展路径加唯一键复合化前置 |
| 3 | M-3 | 邻居查询样例改双向或注明实现含 IN/OUT |
| 4 | M-2 | API 章补暴露面声明（唯一消费方 GraphRetriever、无 HTTP） |
| 5 | L-1/L-2 | 措辞修正（两跳为扩展项、样例补租户谓词） |

全部修复均为文档编辑，不触碰任何代码与对外 API 签名。graph 包本身（结构侧）无需任何改动：零环、零越层、零平行实现、租户显式传参样板齐全——它的全部问题都在「数据无人写入」这一阶段状态，而那已由 H-1 的文档声明覆盖。

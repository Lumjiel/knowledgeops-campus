# 审查报告：docs/architecture-knowledge-graph.md

- **审查日期**：2026-11-04
- **审查方式**：只读通读全文，将文档的表结构字段、种子数据、示例 SQL、GraphService API 表逐一对码核实，未改动任何源码，未 git commit/push，未删除文件，未执行 npm install / build / mvn 构建。
- **协作者清单**（结论均 grounded 到以下文件）：
  - DDL 与种子：`src/main/resources/db/migration/V11__knowledge_graph_tables.sql`（全文 73 行）
  - 服务与 SQL：`graph/GraphService.java`、`graph/KgEntityMapper.java`、`graph/KgRelationMapper.java`、`graph/KgFactMapper.java`
  - 消费者：`retrieval/GraphRetriever.java`；kg 表全部引用方经全仓库 grep 核实（仅 graph 包 + GraphRetriever）
  - 租户语义参照：`reports/audit-api-recipes.md.md`（BootstrapProperties 默认租户 public）
- **关联报告**：`reports/audit-HybridRetrievalService.java.md`、`reports/audit-architecture-hybrid-retrieval.md.md`、`reports/audit-api-recipes.md.md`

## 概述（先说做对的部分）

这份文档整体准确度较高，是同系列架构文档里对码偏差最少的一份：

1. **GraphService API 表逐字一致**：`searchEntities(tenantId, keyword, limit)`、`getNeighbors(tenantId, entityId)`、`searchFacts(tenantId, keyword, limit)`、`getEntitiesByType(tenantId, type)` 四个方法的签名与说明与 `GraphService.java:28,36,61,69` 完全一致，无漂移。
2. **实体搜索示例的别名机制属实**：`JSON_CONTAINS(aliases, ...)` 与 `KgEntityMapper.searchByName`（`KgEntityMapper.java:11-19`）的实际 SQL 一致（实际实现还带 `tenant_id` 过滤、`LIMIT` 与 LIKE 转义，比示例更严格）。
3. **事实检索示例的 `ORDER BY confidence DESC` 属实**：`KgFactMapper.searchByKeyword` 实际 SQL 就是 `subject LIKE ... OR object LIKE ... ORDER BY confidence DESC LIMIT #{limit}`，且带 `tenant_id`。
4. **"当前业务场景主要是实体搜索和一跳邻居"如实**：全代码只有 `getNeighbors` 一跳实现，无任何两跳查询；扩展路径 3（多跳推理）确属未实现，表述正确。
5. **扩展路径 1（从 PDF 抽取实体关系）确属未实现**：graph 包无任何 `@Insert`/save 写入路径，kg 表唯一 Java 消费者 `GraphRetriever` 只读——文档把它放在"扩展路径"而非"现状"是准确的。
6. **选型理由（复用 MySQL、邻接表模型）与 DDL 一致**：三张表均在 `V11` 建于既有 MySQL，entity → relation → entity 模型成立；weight/confidence 的 0-1 语义与种子值（1.0/0.8）一致。

## 中严重度

### M1. 三张表字段表全部漏掉 tenant_id，且漏掉了它带来的两个结构性行为

- **位置**：`docs/architecture-knowledge-graph.md:14-39`（三张字段表）；对照 `V11__knowledge_graph_tables.sql:4,21,38`
- **描述**：`kg_entity/kg_relation/kg_fact` 都有 `tenant_id VARCHAR(64) NOT NULL DEFAULT 'public'`，且 mapper 里每一条查询 SQL 都以 `tenant_id = #{tenantId}` 为前置条件（`KgEntityMapper.searchByName/findByType`、`KgRelationMapper.findRelations` 等）。字段表却只列业务列，隔离键只字未提——同系列审查中已两次确认这是文档高频缺陷（`audit-architecture-agent-workflow.md.md` 同类发现）。连带两个未披露的行为：
  1. **种子数据只落在 tenant_id='public'**（`V11:57-73` 全部 7 实体 7 关系均为 'public'）：非 public 租户的图谱检索**恒为空**（所有查询带 tenant 过滤）。文档"内置课程图谱种子数据"未说明它只对默认租户可见。
  2. **entity_id 跨租户全局唯一**（`V11:13` `UNIQUE KEY uk_kg_entity_id (entity_id)`，不含 tenant_id）：文档称"全局唯一实体 ID"没错，但结合 tenant 列意味着"实体 ID 全局共享、行按租户过滤"的双键设计——同一 entity_id 无法按租户各有一份行，这对"课程图谱按租户扩展"（扩展路径 2）是实质约束。
- **触发条件**：① 读者按字段表推导导出/备份/租户数据删除方案 → 漏掉三张表；② 非 public 租户启用图谱检索 → 永远空结果；③ 扩展路径 2 落地时按租户复制课程实体 → 唯一键冲突。
- **影响**：隔离键缺失是文档级缺陷中影响最大的一类；种子可见性与全局唯一键是直接可触发的行为差异。
- **修复建议**：字段表各补一行 `tenant_id`（标注"所有查询 SQL 的强制前置过滤"）；种子数据一节注明"仅 tenant_id='public' 可见"；扩展路径 2 注明 entity_id 全局唯一约束对按租户建图的影响。

### M2. "扩展路径 1"掩盖了现状：kg 表没有任何运行时写入路径，图谱是纯静态演示数据

- **位置**：`docs/architecture-knowledge-graph.md:90`；反证在 graph 包全量 grep（无 `@Insert`/save/BaseMapper 写调用，仅 `KgEntityMapper/KgRelationMapper/KgFactMapper` 的 `@Select`）、消费者仅 `GraphRetriever`
- **描述**：文档说"从 PDF chunk 中抽取实体和关系，自动填充 kg_entity/kg_relation"是扩展路径——方向正确，但整个正文没有一句话说明**现状是：除迁移种子外 kg 表没有任何写入者**。`kg_fact` 甚至连种子都没有（V11 只种 entity + relation，fact 表恒空）。读者会把"文档图谱"理解为已在运行的能力，进而期望 `searchFacts` 返回结果——实际它永远返回空列表。
- **触发条件**：① 评测或演示时调用 `searchFacts` → 恒空；② 期望上传 PDF 后图谱自动增长 → 不发生。
- **影响**：能力现状被高估一档；`kg_fact` 的"时效性/可信度"等字段设计实际全部处于从未被填充的状态。
- **修复建议**：在"表结构"或"种子数据"一节加一句现状声明："当前 kg_* 的唯一数据来源是 V11 迁移种子（kg_fact 无种子），抽取管道属扩展路径"；`kg_fact` 处标注"表已建、无数据源"。

## 低严重度

### L1. 一跳邻居示例 SQL 是单向的，实现是双向的

- **位置**：`docs/architecture-knowledge-graph.md:64-70`；对照 `KgRelationMapper.findRelations`（`source_entity_id = ? OR target_entity_id = ?`）与 `GraphService.getNeighbors`（`:42-47` 计算 IN/OUT 方向）
- **描述**：示例 SQL 只按 `r.source_entity_id = 'ent-course-java'` JOIN target——只覆盖出边。实际实现双向查询并给 `GraphNeighbor.direction` 标注 IN/OUT。照示例 SQL 写调用方会漏掉全部入边（如对"Spring Boot微服务"查询会漏掉 `rel-7` 的 PREREQUISITE_FOR 入边）。
- **触发条件**：读者把示例 SQL 当作 getNeighbors 的等价实现复用。
- **影响**：低（文档是示例性质），但方向语义是图查询的核心差异。
- **修复建议**：示例 SQL 改为 `(source = ? OR target = ?)` 双向形式，或注明"getNeighbors 为双向，含 IN 方向邻居"。

### L2. type 与 relation_type 被呈现为封闭枚举，实际是无约束的自由字符串

- **位置**：`docs/architecture-knowledge-graph.md:19,29`；对照 `V11:6`（`type VARCHAR(64) NOT NULL DEFAULT 'CONCEPT'`）、`V11:24`（`relation_type VARCHAR(64) NOT NULL DEFAULT 'RELATED_TO'`）
- **描述**：文档列出 COURSE/SKILL_LEVEL/TOPIC/CONCEPT 与 REQUIRES_LEVEL/BELONGS_TO/PREREQUISITE_FOR，读起来像封闭枚举。DDL 无 CHECK/ENUM 约束，代码无类型校验，且 DDL 默认值 `RELATED_TO` 并不在文档列出的三个 relation_type 里。写入任意字符串都会被接受。
- **触发条件**：扩展路径 2 建课程图谱（讲师/后续路径等新类型）时，写入者无从知道约束边界。
- **影响**：轻微；文档未列的默认值 RELATED_TO 是既成事实的第四种关系类型。
- **修复建议**：注明"当前为约定枚举（无 DDL/代码强制），DDL 默认值 RELATED_TO"；扩展路径引入新类型时同步更新清单。

## 修复优先级建议

1. **M1**：字段表补 tenant_id + 种子可见性说明——改动三行文字，消除隔离键盲区。
2. **M2**：加一句"现状 = 纯种子静态数据、kg_fact 无数据源"。
3. **L1 / L2**：随下次文档维护合并（示例 SQL 双向化 + 枚举口径注明）。

## 约束遵守声明

- 未修改任何源码、配置或本文档本体；仅新增本报告文件。
- 未执行 git commit/push；未删除文件；未执行 npm install 或任何 build/mvn 构建。
- 修复建议均为文档措辞修订，不涉及任何对外 API 签名变更。

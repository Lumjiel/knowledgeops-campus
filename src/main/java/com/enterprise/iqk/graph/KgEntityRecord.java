package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * File: KgEntityRecord.java
 * <p>
 * {@code kg_entity} 表记录：知识图谱实体（对应 docs/architecture-knowledge-graph.md
 * 表结构「kg_entity」），由 MySQL 邻接表方案承载，别名以 JSON 数组存储支持别名搜索。
 */
@TableName("kg_entity")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgEntityRecord {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 全局唯一实体 ID（如 ent-course-java）。 */
    private String entityId;
    /** 租户 ID（图谱数据按租户隔离）。 */
    private String tenantId;
    /** 实体名称（LIKE 搜索目标）。 */
    private String name;
    /** 实体类型：COURSE / SKILL_LEVEL / TOPIC / CONCEPT。 */
    private String type;
    /** 别名 JSON 数组（JSON_CONTAINS 别名搜索目标）。 */
    private String aliases;
    /** 实体描述。 */
    private String description;
    /** 来源标识（PDF chunk ID、课程 ID 等）。 */
    private String sourceId;
    /** 扩展元数据 JSON，预留字段。 */
    private String metadataJson;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}

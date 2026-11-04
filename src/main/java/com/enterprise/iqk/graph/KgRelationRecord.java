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
 * File: KgRelationRecord.java
 * <p>
 * {@code kg_relation} 表记录：知识图谱的有向加权关系（邻接表行，
 * 对应 docs/architecture-knowledge-graph.md 表结构「kg_relation」）。
 */
@TableName("kg_relation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgRelationRecord {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 全局唯一关系 ID。 */
    private String relationId;
    /** 租户 ID。 */
    private String tenantId;
    /** 源实体 ID（kg_entity.entity_id）。 */
    private String sourceEntityId;
    /** 目标实体 ID（kg_entity.entity_id）。 */
    private String targetEntityId;
    /** 关系类型：REQUIRES_LEVEL / BELONGS_TO / PREREQUISITE_FOR。 */
    private String relationType;
    /** 支撑证据 ID（文档 chunk ID 等）。 */
    private String evidenceId;
    /** 关系权重（0-1）。 */
    private Double weight;
    /** 扩展元数据 JSON，预留字段。 */
    private String metadataJson;
    /** 创建时间。 */
    private LocalDateTime createdAt;
}

package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * File: KgFactRecord.java
 * <p>
 * {@code kg_fact} 表记录：知识图谱的事实三元组（SPO，对应
 * docs/architecture-knowledge-graph.md 表结构「kg_fact」），
 * 带可信度与时效窗口，供 GraphRetriever 以 confidence 参与融合评分。
 */
@TableName("kg_fact")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgFactRecord {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 全局唯一事实 ID（GraphRetriever 的 chunkId 取此值）。 */
    private String factId;
    /** 租户 ID。 */
    private String tenantId;
    /** 三元组主语（LIKE 检索目标）。 */
    private String subject;
    /** 三元组谓词（如 适用于 / 前置需要）。 */
    private String predicate;
    /** 三元组宾语（LIKE 检索目标）。 */
    private String object;
    /** 时效窗口起点。 */
    private LocalDate validFrom;
    /** 时效窗口终点。 */
    private LocalDate validTo;
    /** 可信度（0-1，检索时按此降序，缺省 0.7）。 */
    private Double confidence;
    /** 来源标注。 */
    private String source;
    /** 扩展元数据 JSON，预留字段。 */
    private String metadataJson;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}

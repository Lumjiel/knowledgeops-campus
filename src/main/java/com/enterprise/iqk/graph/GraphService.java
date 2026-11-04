package com.enterprise.iqk.graph;

import com.enterprise.iqk.util.SqlLikeUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * File: GraphService.java
 * <p>
 * 轻量知识图谱服务（docs/architecture-knowledge-graph.md「GraphService API」）：
 * 基于 MySQL 邻接表（kg_entity / kg_relation / kg_fact 三表）提供实体搜索、
 * 一跳邻居与事实三元组检索，避免引入独立图数据库。数据模型
 * （entity → relation → entity）可直接映射到 Neo4j / ArangoDB，作为后续迁移路径。
 * 所有查询均按租户隔离；LIKE 关键词经 {@code SqlLikeUtils.escapeForLike} 转义。
 */
@Service
@RequiredArgsConstructor
public class GraphService {

    private final KgEntityMapper entityMapper;
    private final KgRelationMapper relationMapper;
    private final KgFactMapper factMapper;

    /**
     * 按名称或别名搜索实体（对应文档「实体搜索」SQL：name LIKE + JSON_CONTAINS(aliases)）。
     * keyword 为空时返回空列表；LIKE 通配符已转义，limit 下限 1。
     */
    public List<KgEntityRecord> searchEntities(String tenantId, String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) return List.of();
        return entityMapper.searchByName(tenantId, SqlLikeUtils.escapeForLike(keyword.trim()), Math.max(1, limit));
    }

    /**
     * 获取实体的一跳邻居（对应文档「一跳邻居」SQL）：取出该实体的全部关系行，
     * 逐条解析对端实体（带 {@link #entityCache} 去重查询），组装为
     * {@link GraphNeighbor}（含关系类型、方向 IN/OUT 与权重）。
     * 对端实体不存在（跨租户或数据不一致）时跳过该关系。
     */
    public List<GraphNeighbor> getNeighbors(String tenantId, String entityId) {
        List<KgRelationRecord> relations = relationMapper.findRelations(tenantId, entityId);
        Map<String, KgEntityRecord> entityCache = new HashMap<>();
        List<GraphNeighbor> neighbors = new ArrayList<>();

        for (KgRelationRecord rel : relations) {
            String neighborId = rel.getSourceEntityId().equals(entityId)
                    ? rel.getTargetEntityId() : rel.getSourceEntityId();
            KgEntityRecord entity = entityCache.computeIfAbsent(neighborId,
                    id -> entityMapper.findByEntityId(tenantId, id));
            if (entity == null) continue;
            boolean outgoing = rel.getSourceEntityId().equals(entityId);
            neighbors.add(GraphNeighbor.builder()
                    .entity(entity)
                    .relationType(rel.getRelationType())
                    .direction(outgoing ? "OUT" : "IN")
                    .weight(rel.getWeight())
                    .build());
        }
        return neighbors;
    }

    /**
     * 搜索事实三元组（对应文档「事实检索」SQL：subject/object LIKE，按 confidence 降序）。
     * keyword 为空时返回空列表；LIKE 通配符已转义，limit 下限 1。
     */
    public List<KgFactRecord> searchFacts(String tenantId, String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) return List.of();
        return factMapper.searchByKeyword(tenantId, SqlLikeUtils.escapeForLike(keyword.trim()), Math.max(1, limit));
    }

    /** 按类型列出实体（如全部 COURSE 类型），用于课程图谱枚举场景。 */
    public List<KgEntityRecord> getEntitiesByType(String tenantId, String type) {
        return entityMapper.findByType(tenantId, type);
    }

    /**
     * 一跳邻居条目：对端实体 + 连接关系信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphNeighbor {
        /** 对端实体记录（关系的 source 或 target 中非查询实体的那端）。 */
        private KgEntityRecord entity;
        /** 关系类型（REQUIRES_LEVEL / BELONGS_TO / PREREQUISITE_FOR 等）。 */
        private String relationType;
        /** 方向：OUT（查询实体为 source）或 IN（查询实体为 target）。 */
        private String direction;
        /** 关系权重（0-1）。 */
        private Double weight;
    }
}

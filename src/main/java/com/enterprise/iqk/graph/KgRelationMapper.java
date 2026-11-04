package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * File: KgRelationMapper.java
 * <p>
 * {@code kg_relation} 表的 MyBatis-Plus Mapper：邻接表关系的查询入口，
 * 支撑一跳邻居（双向关系行）与后续多跳推理扩展。
 */
@Mapper
public interface KgRelationMapper extends BaseMapper<KgRelationRecord> {

    /** 查询实体参与的全部关系行（source 或 target 任一端命中），供双向一跳邻居解析。 */
    @Select("""
            SELECT r.* FROM kg_relation r
            WHERE r.tenant_id = #{tenantId}
              AND (r.source_entity_id = #{entityId} OR r.target_entity_id = #{entityId})
            """)
    List<KgRelationRecord> findRelations(@Param("tenantId") String tenantId,
                                          @Param("entityId") String entityId);

    /** 查询两实体间的直接关系（多跳推理 / 关系存在性判断用）。 */
    @Select("""
            SELECT r.* FROM kg_relation r
            WHERE r.tenant_id = #{tenantId}
              AND r.source_entity_id = #{sourceId}
              AND r.target_entity_id = #{targetId}
            """)
    List<KgRelationRecord> findDirectRelation(@Param("tenantId") String tenantId,
                                               @Param("sourceId") String sourceId,
                                               @Param("targetId") String targetId);

    /** 按关系类型列出该租户全部关系（如全部 PREREQUISITE_FOR 边）。 */
    @Select("""
            SELECT r.* FROM kg_relation r
            WHERE r.tenant_id = #{tenantId}
              AND r.relation_type = #{relationType}
            """)
    List<KgRelationRecord> findByType(@Param("tenantId") String tenantId,
                                       @Param("relationType") String relationType);
}

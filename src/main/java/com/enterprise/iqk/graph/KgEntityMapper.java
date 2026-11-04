package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * File: KgEntityMapper.java
 * <p>
 * {@code kg_entity} 表的 MyBatis-Plus Mapper：实体搜索（名称 LIKE + 别名
 * JSON_CONTAINS，对应文档「实体搜索」SQL）、按类型枚举与按 ID 精确查询。
 */
@Mapper
public interface KgEntityMapper extends BaseMapper<KgEntityRecord> {

    /** 名称 LIKE 模糊匹配或别名 JSON 精确命中（JSON_QUOTE 防注入），按租户隔离。 */
    @Select("""
            SELECT * FROM kg_entity
            WHERE tenant_id = #{tenantId}
              AND (name LIKE CONCAT('%', #{keyword}, '%')
                   OR JSON_CONTAINS(aliases, JSON_QUOTE(#{keyword})))
            LIMIT #{limit}
            """)
    List<KgEntityRecord> searchByName(@Param("tenantId") String tenantId,
                                       @Param("keyword") String keyword,
                                       @Param("limit") int limit);

    /** 按类型列出该租户全部实体（COURSE / SKILL_LEVEL / TOPIC / CONCEPT）。 */
    @Select("SELECT * FROM kg_entity WHERE tenant_id = #{tenantId} AND type = #{type}")
    List<KgEntityRecord> findByType(@Param("tenantId") String tenantId,
                                     @Param("type") String type);

    /** 按实体 ID 精确查询（租户隔离），一跳邻居解析对端实体时使用。 */
    @Select("""
            SELECT * FROM kg_entity
            WHERE entity_id = #{entityId}
              AND tenant_id = #{tenantId}
            LIMIT 1
            """)
    KgEntityRecord findByEntityId(@Param("tenantId") String tenantId,
                                  @Param("entityId") String entityId);
}

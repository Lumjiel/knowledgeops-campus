package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * File: KgFactMapper.java
 * <p>
 * {@code kg_fact} 表的 MyBatis-Plus Mapper：事实三元组的模糊检索（对应文档
 * 「事实检索」SQL）与按主语精确查询。
 */
@Mapper
public interface KgFactMapper extends BaseMapper<KgFactRecord> {

    /** subject / object LIKE 模糊匹配，按 confidence 降序，租户隔离。 */
    @Select("""
            SELECT * FROM kg_fact
            WHERE tenant_id = #{tenantId}
              AND (subject LIKE CONCAT('%', #{keyword}, '%')
                   OR object LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY confidence DESC
            LIMIT #{limit}
            """)
    List<KgFactRecord> searchByKeyword(@Param("tenantId") String tenantId,
                                        @Param("keyword") String keyword,
                                        @Param("limit") int limit);

    /** 按主语精确查询该租户的全部事实。 */
    @Select("SELECT * FROM kg_fact WHERE tenant_id = #{tenantId} AND subject = #{subject}")
    List<KgFactRecord> findBySubject(@Param("tenantId") String tenantId,
                                      @Param("subject") String subject);
}

package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.RefreshTokenRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * File: RefreshTokenMapper.java
 * <p>
 * {@code refresh_tokens} 表的 MyBatis-Plus Mapper：有效令牌查询与条件撤销。
 */
@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenRecord> {

    /** 按哈希查未撤销且未过期的令牌（一次性消费的第一步）。 */
    @Select("""
            SELECT * FROM refresh_tokens
            WHERE token_hash = #{tokenHash}
              AND revoked_at IS NULL
              AND expires_at > NOW()
            LIMIT 1
            """)
    RefreshTokenRecord findActiveByHash(@Param("tokenHash") String tokenHash);

    /**
     * 条件撤销：仅当 revoked_at 仍为空时置值（CAS）。
     *
     * @return 影响行数，0 表示令牌已被并发消费（重放拒绝）
     */
    @Update("""
            UPDATE refresh_tokens
            SET revoked_at = #{revokedAt}
            WHERE id = #{id}
              AND revoked_at IS NULL
            """)
    int revoke(@Param("id") Long id, @Param("revokedAt") LocalDateTime revokedAt);
}

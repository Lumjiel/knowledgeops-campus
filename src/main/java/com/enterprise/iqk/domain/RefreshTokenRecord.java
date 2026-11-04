package com.enterprise.iqk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * File: RefreshTokenRecord.java
 * <p>
 * {@code refresh_tokens} 表记录：刷新令牌的哈希与撤销状态，支撑
 * 一次性消费 + 轮换语义（见 {@code RefreshTokenService}）。
 */
@TableName("refresh_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenRecord {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 令牌 SHA-256 哈希（原始令牌不落库）。 */
    private String tokenHash;
    /** 主体标识。 */
    private String principal;
    /** 归属租户（归一化后）。 */
    private String tenantId;
    /** 角色 CSV（刷新时还原身份用）。 */
    private String roles;
    /** 过期时间。 */
    private LocalDateTime expiresAt;
    /** 撤销时间；非空表示已消费/已轮换，不可再用。 */
    private LocalDateTime revokedAt;
    /** 创建时间。 */
    private LocalDateTime createdAt;
}

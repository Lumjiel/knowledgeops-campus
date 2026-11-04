package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.RefreshTokenRecord;
import com.enterprise.iqk.mapper.RefreshTokenMapper;
import com.enterprise.iqk.util.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * File: RefreshTokenService.java
 * <p>
 * 刷新令牌的一次性消费与轮换（docs/architecture-enterprise.md「Refresh token
 * rotation」）：原始令牌只返回一次，库里只存 SHA-256 哈希；消费即撤销，
 * 条件 UPDATE 保证并发重放时仅第一个调用者成功。
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final SecurityProperties securityProperties;
    private final RefreshTokenMapper refreshTokenMapper;

    /**
     * 签发新刷新令牌：原始令牌为两个 UUID 拼接（仅本次返回），库中存哈希；
     * 角色以 CSV 形式随令牌持久化，供刷新时还原身份。
     *
     * @param principal 主体标识（用户名/客户端名）
     * @param roles     授予的角色列表
     * @param tenantId  归属租户（内部归一化）
     * @return 原始令牌 + 归一化租户 + 过期时间
     */
    public RefreshTokenIssueResult issue(String principal, List<String> roles, String tenantId) {
        String raw = UUID.randomUUID() + "." + UUID.randomUUID();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(Math.max(1, securityProperties.getRefreshExpireDays()));
        String roleCsv = roles == null ? "" : roles.stream()
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.joining(","));
        RefreshTokenRecord record = RefreshTokenRecord.builder()
                .tokenHash(HashUtils.sha256Hex(raw))
                .principal(principal)
                .tenantId(TenantContext.normalize(tenantId))
                .roles(roleCsv)
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.now())
                .build();
        refreshTokenMapper.insert(record);
        return new RefreshTokenIssueResult(raw, record.getTenantId(), expiresAt);
    }

    /**
     * 一次性消费刷新令牌（事务内）：按哈希查有效令牌 → 条件 UPDATE 撤销
     * （ revoked_at 为空才能置值，0 行即视为令牌已被使用/重放，拒绝）→
     * 以令牌内持久化的角色还原 {@link AuthIdentity}。调用方据此签发新 access token。
     *
     * @return 刷新出的身份；令牌无效、过期、已撤销或并发重放时返回 {@code null}
     */
    @Transactional
    public AuthIdentity consume(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return null;
        }
        String hash = HashUtils.sha256Hex(rawToken);
        RefreshTokenRecord record = refreshTokenMapper.findActiveByHash(hash);
        if (record == null) {
            return null;
        }
        // Conditional revoke guards against concurrent reuse of the same token:
        // only the first caller flips revoked_at, the rest get 0 rows and are rejected.
        if (refreshTokenMapper.revoke(record.getId(), LocalDateTime.now()) == 0) {
            return null;
        }
        String roleCsv = Objects.toString(record.getRoles(), "");
        List<String> roles = Arrays.stream(roleCsv.split(","))
                .filter(StringUtils::hasText)
                .toList();
        return AuthIdentity.builder()
                .principal(record.getPrincipal())
                .roles(roles)
                .permissions(List.of())
                .source("refresh_token")
                .tenantId(TenantContext.normalize(record.getTenantId()))
                .build();
    }

    public record RefreshTokenIssueResult(String rawToken, String tenantId, LocalDateTime expiresAt) {}
}

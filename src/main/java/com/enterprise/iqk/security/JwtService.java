package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * File: JwtService.java
 * <p>
 * JWT 签发与解析（HMAC-SHA，密钥来自 {@code app.security.jwt-secret}，不足 32 字节
 * 直接启动失败）。claims 携带 roles / permissions / tenant_id，是「JWT for
 * request-level authn/authz」（docs/architecture-enterprise.md §4）的核心实现。
 */
@Service
@RequiredArgsConstructor
public class JwtService {
    private final SecurityProperties securityProperties;

    /**
     * 解析并校验 JWT 签名与有效期，还原为 {@link AuthIdentity}。
     * roles/permissions 兼容 List、逗号分隔字符串与 {value: ...} 包装三种形态；
     * roles 为空时默认授予 USER。
     *
     * @return 身份；token 为空、签名非法、已过期或缺 subject 时返回 {@code null}
     */
    public AuthIdentity parse(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        Claims claims = Jwts.parser()
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Date expiration = claims.getExpiration();
        if (expiration != null && expiration.before(new Date())) {
            return null;
        }
        String subject = claims.getSubject();
        List<String> roles = extractRoles(claims);
        List<String> permissions = extractPermissions(claims);
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        if (roles.isEmpty()) {
            roles = List.of("USER");
        }
        return AuthIdentity.builder()
                .principal(subject)
                .roles(roles)
                .permissions(permissions)
                .source("jwt")
                .tenantId(TenantContext.normalize(claims.get("tenant_id", String.class)))
                .build();
    }

    /** 按默认有效期（{@code app.security.jwt-expire-minutes}）签发。 */
    public String issueToken(String subject, List<String> roles, List<String> permissions, String tenantId) {
        return issueToken(subject, roles, permissions, tenantId, securityProperties.getJwtExpireMinutes());
    }

    /** 带自定义有效期的签发（游客模式等短时效场景）。 */
    public String issueToken(String subject, List<String> roles, List<String> permissions, String tenantId,
                             long expireMinutes) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claim("roles", roles == null ? Collections.emptyList() : roles)
                .claim("permissions", permissions == null ? Collections.emptyList() : permissions)
                .claim("tenant_id", TenantContext.normalize(tenantId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireMinutes * 60L)))
                .signWith(secretKey())
                .compact();
    }

    /** 密钥：要求已配置且 ≥ 32 字节，否则抛 IllegalStateException（fail-fast）。 */
    private SecretKey secretKey() {
        String secret = securityProperties.getJwtSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("APP_JWT_SECRET (app.security.jwt-secret) is required");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must be at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Claims claims) {
        Object raw = claims.get("roles");
        if (raw == null) {
            return new ArrayList<>();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (raw instanceof String s && StringUtils.hasText(s)) {
            return List.of(s.split(","));
        }
        if (raw instanceof Map<?, ?> map && map.containsKey("value")) {
            return List.of(String.valueOf(map.get("value")));
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractPermissions(Claims claims) {
        Object raw = claims.get("permissions");
        if (raw == null) {
            return new ArrayList<>();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (raw instanceof String s && StringUtils.hasText(s)) {
            return List.of(s.split(","));
        }
        if (raw instanceof Map<?, ?> map && map.containsKey("value")) {
            return List.of(String.valueOf(map.get("value")));
        }
        return new ArrayList<>();
    }
}

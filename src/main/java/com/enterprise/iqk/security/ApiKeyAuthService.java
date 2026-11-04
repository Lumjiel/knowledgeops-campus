package com.enterprise.iqk.security;

import com.enterprise.iqk.domain.ApiKeyRecord;
import com.enterprise.iqk.mapper.ApiKeyMapper;
import com.enterprise.iqk.mapper.PermissionMapper;
import com.enterprise.iqk.util.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * File: ApiKeyAuthService.java
 * <p>
 * API Key 认证（机器接入引导凭证，docs/architecture-enterprise.md §4）：
 * 原始 key 仅经 SHA-256 哈希比对，命中后刷新 last_used 并组装
 * 角色 + 权限（按 roleName 从 permission 表查得）的 {@link AuthIdentity}。
 */
@Service
@RequiredArgsConstructor
public class ApiKeyAuthService {
    private final ApiKeyMapper apiKeyMapper;
    private final PermissionMapper permissionMapper;

    /**
     * 校验原始 API Key（trim 后哈希比对），并更新 key 的最近使用时间。
     *
     * @return 认证身份；key 为空或无对应有效记录时返回 {@code null}
     */
    public AuthIdentity authenticate(String rawApiKey) {
        if (!StringUtils.hasText(rawApiKey)) {
            return null;
        }
        String keyHash = HashUtils.sha256Hex(rawApiKey.trim());
        ApiKeyRecord record = apiKeyMapper.findActiveByKeyHash(keyHash);
        if (record == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        apiKeyMapper.touch(record.getId(), now, now);
        return AuthIdentity.builder()
                .principal(record.getKeyName())
                .roles(List.of(record.getRoleName()))
                .permissions(permissionMapper.findByRoleName(record.getRoleName()))
                .source("api_key")
                .tenantId(TenantContext.normalize(record.getTenantId()))
                .build();
    }
}

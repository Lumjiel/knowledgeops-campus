package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.ApiKeyRecord;
import com.enterprise.iqk.mapper.ApiKeyMapper;
import com.enterprise.iqk.util.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * File: ApiKeyLifecycleService.java
 * <p>
 * API Key 全生命周期管理（docs/architecture-enterprise.md「Tenant-scoped API key
 * lifecycle」）：签发（同名活跃键互斥）、幂等 provision、轮换（旧键撤销 + 新键
 * 关联 rotatedFromId）与撤销。所有操作均以归一化租户为作用域；
 * 原始 key 只在签发结果中出现一次，库中仅存哈希。
 */
@Service
@RequiredArgsConstructor
public class ApiKeyLifecycleService {
    private final ApiKeyMapper apiKeyMapper;
    private final SecurityProperties securityProperties;

    /**
     * 签发新 key：同一租户下已存在同名活跃键时拒绝（IllegalArgumentException）；
     * 否则生成 {@code ak-<uuid>} 随机 key 并落库。
     */
    public ApiKeyIssueResult issue(String keyName, String roleName, String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        ApiKeyRecord active = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (active != null) {
            throw new IllegalArgumentException("active api key already exists for keyName");
        }
        String raw = "ak-" + UUID.randomUUID().toString().replace("-", "");
        return provision(raw, keyName, roleName, normalizedTenant);
    }

    /**
     * Provision an operator-supplied credential (bootstrap / contract stacks).
     * Idempotent: an already-active key with the same name is left untouched;
     * a revoked row with the same hash is revived instead of colliding with
     * the UNIQUE key_hash constraint.
     */
    public ApiKeyIssueResult provision(String rawKey, String keyName, String roleName, String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        ApiKeyRecord active = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (active != null) {
            return new ApiKeyIssueResult(null, keyName, normalizedTenant, active.getExpiresAt());
        }
        String rawHash = HashUtils.sha256Hex(rawKey);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusDays(Math.max(1, securityProperties.getApiKeyExpireDays()));
        // Revive by key_hash rather than key_name: the revoked row may keep
        // the original seed key_name (V7 'demo-admin-key-2026') while the
        // operator provisions a different APP_BOOTSTRAP_KEY_NAME, and the
        // UNIQUE key_hash constraint is what forces us onto that row.
        ApiKeyRecord latest = apiKeyMapper.findByKeyHash(rawHash);
        if (latest != null) {
            // Explicit revive SQL: MyBatis-Plus updateById skips null fields,
            // so revoked_at / revoked_reason would stay set and the revived
            // key would never match findActive queries again.
            apiKeyMapper.revive(latest.getId(), keyName, normalizedTenant, roleName, expiresAt, now);
            return new ApiKeyIssueResult(rawKey, keyName, normalizedTenant, expiresAt);
        }
        ApiKeyRecord record = ApiKeyRecord.builder()
                .keyHash(rawHash)
                .keyName(keyName)
                .tenantId(normalizedTenant)
                .roleName(roleName)
                .enabled(1)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        apiKeyMapper.insert(record);
        return new ApiKeyIssueResult(rawKey, keyName, normalizedTenant, expiresAt);
    }

    /**
     * 轮换：撤销指定 keyName 的活跃键后立即签发新键，并在新键上记录
     * rotatedFromId 审计链。旧键不存在时抛 IllegalArgumentException。
     */
    public ApiKeyIssueResult rotate(String keyName, String reason, String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        ApiKeyRecord old = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (old == null) {
            throw new IllegalArgumentException("active api key not found");
        }
        apiKeyMapper.revoke(old.getId(), LocalDateTime.now(), reason, LocalDateTime.now());
        ApiKeyIssueResult issued = issue(keyName, old.getRoleName(), normalizedTenant);
        ApiKeyRecord newer = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (newer != null) {
            newer.setRotatedFromId(old.getId());
            newer.setUpdatedAt(LocalDateTime.now());
            apiKeyMapper.updateById(newer);
        }
        return issued;
    }

    /** 撤销指定 keyName 的活跃键（记录原因）；不存在时抛 IllegalArgumentException。 */
    public void revoke(String keyName, String reason, String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        ApiKeyRecord record = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (record == null) {
            throw new IllegalArgumentException("active api key not found");
        }
        apiKeyMapper.revoke(record.getId(), LocalDateTime.now(), reason, LocalDateTime.now());
    }

    private String normalizeTenant(String tenantId) {
        return TenantContext.normalize(tenantId);
    }

    public record ApiKeyIssueResult(String rawApiKey, String keyName, String tenantId, LocalDateTime expiresAt) {}
}

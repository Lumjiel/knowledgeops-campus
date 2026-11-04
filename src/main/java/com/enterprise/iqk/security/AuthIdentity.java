package com.enterprise.iqk.security;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * File: AuthIdentity.java
 * <p>
 * 认证成功的统一身份载体：无论来源是 JWT、API Key 还是刷新令牌，
 * 都归一为此对象（source 标注来源），供过滤器装配 authorities 与
 * 业务层读取租户。不可变性由调用方保证，字段仅经 builder/ setter 写入。
 */
@Data
@Builder
public class AuthIdentity {
    /** 主体标识（用户名 / key 名 / 刷新令牌归属主体）。 */
    private String principal;
    /** 角色列表（如 ADMIN / OPS / USER）。 */
    private List<String> roles;
    /** 细粒度权限列表（如 chat:write），角色为空或需精确控制时使用。 */
    private List<String> permissions;
    /** 凭证来源：jwt / api_key / refresh_token。 */
    private String source;
    /** 归属租户（使用方须再经 {@code TenantContext.normalize} 归一化）。 */
    private String tenantId;
}

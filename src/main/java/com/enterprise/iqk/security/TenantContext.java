package com.enterprise.iqk.security;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

/**
 * File: TenantContext.java
 * <p>
 * 租户上下文工具：请求侧租户经过滤器写入 MDC（键 {@link #TENANT_REQUEST_ATTRIBUTE}），
 * 业务层统一通过 {@link #normalize}/{@link #currentTenantId} 读取并归一化，
 * 保证「缺省租户」语义全局一致（public）。
 */
public final class TenantContext {
    /** 请求头名（未认证回退通道，受过滤器白名单约束）。 */
    public static final String TENANT_HEADER = "X-Tenant-Id";
    /** request attribute 与 MDC 共用的租户键名。 */
    public static final String TENANT_REQUEST_ATTRIBUTE = "tenant_id";
    /** 缺省租户：无租户信息时归一化结果。 */
    public static final String DEFAULT_TENANT = "public";

    private TenantContext() {
    }

    /** 归一化：空白返回缺省租户 "public"，否则 trim 原值。 */
    public static String normalize(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : DEFAULT_TENANT;
    }

    /** 当前线程租户：读 MDC 并归一化（无 MDC 的后台线程得到 "public"）。 */
    public static String currentTenantId() {
        return normalize(MDC.get(TENANT_REQUEST_ATTRIBUTE));
    }
}

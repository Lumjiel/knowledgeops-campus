package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.vo.ApiKeyIssueVO;
import com.enterprise.iqk.domain.vo.AuthTokenVO;
import com.enterprise.iqk.security.ApiKeyAuthService;
import com.enterprise.iqk.security.ApiKeyLifecycleService;
import com.enterprise.iqk.security.AuthIdentity;
import com.enterprise.iqk.security.JwtService;
import com.enterprise.iqk.security.PermissionService;
import com.enterprise.iqk.security.RefreshTokenService;
import com.enterprise.iqk.security.TenantContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * File: AuthController.java
 * <p>
 * 认证与凭证管理接口（前缀 {@code /auth}），全部为 POST：
 * <ul>
 *   <li>{@code POST /auth/token} — API Key 换取 JWT + 刷新令牌（刷新令牌以 HttpOnly Cookie 下发）；</li>
 *   <li>{@code POST /auth/guest} — 游客模式，免凭证签发低权限短时效 JWT（按 IP 限流）；</li>
 *   <li>{@code POST /auth/refresh} — 用刷新令牌（Header 或 Cookie）换取新 JWT 并轮换刷新令牌；</li>
 *   <li>{@code POST /auth/api-keys[/rotate|/revoke]} — API Key 的签发/轮换/吊销，
 *       仅 {@code PERM_AUTH_KEY_MANAGE} 权限或 {@code ROLE_ADMIN} 可调用。</li>
 * </ul>
 * 失败统一返回 {@code ok=0} 的响应体而非抛异常，msg 说明具体原因（缺哪个参数、
 * 越过什么边界、失败在哪个阶段），由前端按 {@code ok} 字段分支处理。下游存储/签名
 * 等后端异常同样降级为 ok=0 并注明失败阶段与已发生的副作用（如「旧刷新令牌已被
 * 消费」），完整堆栈进服务端日志，不向客户端泄露内部细节。请求超时/存储不可用
 * 亦走该路径，不会以 500 裸异常形式暴露。
 * 租户一律经 {@link TenantContext} 归一化；API Key 认证失败或租户头不匹配均拒绝签发。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private static final long REFRESH_EXPIRE_SOON_DAYS = 2L;
    /** Name of the HttpOnly cookie that holds the long-lived refresh token. */
    public static final String REFRESH_COOKIE = "kops_refresh";

    /** keyName 最长 128 字符，防超长串打满 DB 列与审计日志。 */
    private static final int MAX_KEY_NAME_LENGTH = 128;
    /** 角色名最长 64 字符。 */
    private static final int MAX_ROLE_NAME_LENGTH = 64;
    /** 审计原因（rotate/revoke 的 reason）最长 256 字符。 */
    private static final int MAX_REASON_LENGTH = 256;
    /** 限流键（客户端 IP）截断长度，防恶意超长 X-Forwarded-For 撑大限流表。 */
    private static final int MAX_CLIENT_IP_LENGTH = 64;

    private final ApiKeyAuthService apiKeyAuthService;
    private final ApiKeyLifecycleService apiKeyLifecycleService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final PermissionService permissionService;
    private final SecurityProperties securityProperties;

    /**
     * When true (default), the refresh token cookie is marked Secure and
     * the SameSite=Strict policy is used. Operators serving the app over
     * plain HTTP during local development should set
     * {@code app.security.refresh-cookie-secure=false} to make the cookie
     * usable from a localhost browser.
     */
    @Value("${app.security.refresh-cookie-secure:true}")
    private boolean refreshCookieSecure;

    /**
     * API Key 换取访问凭证：校验 {@code X-API-Key}（可附租户头，须与 Key 归属租户一致），
     * 签发携带角色+权限的 JWT，并签发刷新令牌。刷新令牌以 HttpOnly Cookie（{@link #REFRESH_COOKIE}）
     * 下发，同时在响应体保留一份供尚未迁移的旧前端使用（见方法内注释）。
     *
     * @param apiKey       请求头 X-API-Key，API Key 明文
     * @param tenantHeader 可选租户头，非空时必须与 API Key 归属租户一致
     * @param response     Servlet 响应，用于写入刷新 Cookie
     * @return 成功时 ok=1 含 token/refreshToken/租户/过期信息；Key 无效或租户不匹配时 ok=0
     */
    @PostMapping("/token")
    public AuthTokenVO token(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                             @RequestHeader(value = TenantContext.TENANT_HEADER, required = false) String tenantHeader,
                             HttpServletResponse response) {
        // Header 声明为可选并在控制器内校验：此前缺失/空白会经全局兜底处理器变成
        // 500「internal server error」，现在以 ok=0 说明原因。
        if (!StringUtils.hasText(apiKey)) {
            return tokenFailure("invalid api key: blank X-API-Key header");
        }
        AuthIdentity identity;
        try {
            identity = apiKeyAuthService.authenticate(apiKey);
        } catch (Exception e) {
            log.error("[auth/token] api key authentication failed: {}", e.getMessage(), e);
            return tokenFailure("invalid api key: authentication backend error, try later");
        }
        if (identity == null) {
            return AuthTokenVO.builder().ok(0).msg("invalid api key").build();
        }
        String identityTenant = TenantContext.normalize(identity.getTenantId());
        if (StringUtils.hasText(tenantHeader) && !identityTenant.equals(TenantContext.normalize(tenantHeader))) {
            return AuthTokenVO.builder().ok(0).msg("tenant mismatch for api key").build();
        }
        try {
            List<String> permissions = permissionService.permissionsForRoles(identity.getRoles());
            String token = jwtService.issueToken(identity.getPrincipal(), identity.getRoles(), permissions, identityTenant);
            RefreshTokenService.RefreshTokenIssueResult refreshIssue =
                    refreshTokenService.issue(identity.getPrincipal(), identity.getRoles(), identityTenant);
            // Refresh token is delivered as an HttpOnly cookie so an XSS in the
            // front-end cannot exfiltrate the long-lived credential. The body
            // still carries a copy for the existing App.vue that has not yet
            // moved to credentials: 'include'. Once the companion PR (PR B)
            // lands and App.vue drops localStorage, the body field can go
            // away without breaking older clients mid-upgrade.
            setRefreshCookie(response, refreshIssue);
            return buildTokenResponse(token, identityTenant, refreshIssue);
        } catch (IllegalArgumentException e) {
            // 业务性拒绝（参数/配置类问题）：原因直接透出，便于调用方自纠。
            log.error("[auth/token] token issuance rejected: {}", e.getMessage());
            return tokenFailure("token issuance failed: " + e.getMessage());
        } catch (Exception e) {
            // 存储超时/不可用等：签名或刷新令牌可能已部分生成，但 Cookie 尚未写出，
            // 统一按未签发处理并说明阶段，避免客户端拿着半套凭证续连。
            log.error("[auth/token] token issuance failed", e);
            return tokenFailure("token issuance failed: credential backend error, no token was issued");
        }
    }

    /**
     * 游客模式：无需任何凭证，签发低权限短时效 JWT（仅 chat/session/feedback 读写）。
     * 面向"打开即可体验"的演示与校内服务场景；按 IP 限流防滥用。
     */
    @PostMapping("/guest")
    public AuthTokenVO guest(jakarta.servlet.http.HttpServletRequest request) {
        String clientIp = clientIpOf(request);
        if (!guestLimiterTryAcquire(clientIp)) {
            return AuthTokenVO.builder().ok(0).msg("too many guest tokens from this ip, try later").build();
        }
        String principal = "guest-" + Integer.toHexString(java.util.Objects
                .hash(clientIp, java.time.LocalDate.now().toString()));
        List<String> guestPermissions = List.of(
                "chat:read", "chat:write", "session:read", "session:write", "feedback:write",
                "rag:read");
        String token;
        try {
            token = jwtService.issueToken(principal, List.of("GUEST"), guestPermissions, "public", 120L);
        } catch (Exception e) {
            log.error("[auth/guest] guest token issuance failed: {}", e.getMessage(), e);
            return AuthTokenVO.builder().ok(0)
                    .msg("guest token issuance failed: token signing backend error, try later")
                    .build();
        }
        return AuthTokenVO.builder()
                .ok(1)
                .msg("ok")
                .token(token)
                .tenantId("public")
                .build();
    }

    private static final int GUEST_LIMIT_PER_HOUR = 30;
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> guestCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 每 IP 每小时最多 GUEST_LIMIT_PER_HOUR 个游客 token（固定窗口）。 */
    private synchronized boolean guestLimiterTryAcquireInternal(String ip) {
        long hour = System.currentTimeMillis() / 3_600_000L;
        long[] counter = guestCounters.computeIfAbsent(ip, k -> new long[]{hour, 0});
        if (counter[0] != hour) {
            counter[0] = hour;
            counter[1] = 0;
        }
        if (counter[1] >= GUEST_LIMIT_PER_HOUR) {
            return false;
        }
        // Previously the whole map was cleared once it grew past 10k entries:
        // that reset every honest client's budget AND let an attacker spamming
        // spoofed X-Forwarded-For values erase their own counter (limit
        // bypass). Evict only stale windows instead, and fail closed —
        // reject the request — if the table is still saturated.
        if (guestCounters.size() > 10_000) {
            guestCounters.values().removeIf(c -> c[0] != hour);
            if (guestCounters.size() > 10_000) {
                return false;
            }
        }
        counter[1]++;
        return true;
    }

    /**
     * 游客限流入口（薄封装，保留以隔离内部实现）。见 {@link #guestLimiterTryAcquireInternal}。
     */
    private boolean guestLimiterTryAcquire(String ip) {
        return guestLimiterTryAcquireInternal(ip);
    }

    /**
     * 解析客户端真实 IP：优先取 {@code X-Forwarded-For} 首段（经反向代理场景），否则回退
     * {@code getRemoteAddr()}。游客 principal 与限流均以此为键。
     */
    private String clientIpOf(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = StringUtils.hasText(forwarded) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        if (!StringUtils.hasText(ip)) {
            return "unknown";
        }
        // Truncate the limiter key so a hostile multi-kilobyte X-Forwarded-For
        // header cannot balloon the guest counter table (or the principal seed).
        return ip.length() > MAX_CLIENT_IP_LENGTH ? ip.substring(0, MAX_CLIENT_IP_LENGTH) : ip;
    }

    /**
     * 刷新令牌换新 JWT 并轮换刷新令牌（旧刷新令牌一次性消费，{@code consume} 校验与吊销）。
     * 刷新令牌来源二选一：请求头 {@code X-Refresh-Token} 或 {@link #REFRESH_COOKIE} Cookie，
     * Header 优先以兼容旧客户端/测试脚本。本接口不回写 Cookie，新 Cookie 由下次 /auth/token 下发。
     *
     * @param refreshTokenHeader 可选，Header 形式的刷新令牌
     * @param refreshCookie      可选，Cookie 形式的刷新令牌
     * @return 成功时 ok=1 含新 token 与新刷新令牌信息；刷新令牌无效时 ok=0
     */
    @PostMapping("/refresh")
    public AuthTokenVO refresh(@RequestHeader(value = "X-Refresh-Token", required = false) String refreshTokenHeader,
                              @CookieValue(value = REFRESH_COOKIE, required = false) String refreshCookie) {
        // Accept the refresh token from either the legacy X-Refresh-Token
        // header or the HttpOnly cookie set by /auth/token. Header takes
        // precedence so older clients (or test scripts) can still override
        // the cookie value.
        String refreshToken = StringUtils.hasText(refreshTokenHeader) ? refreshTokenHeader : refreshCookie;
        AuthIdentity identity;
        try {
            identity = refreshTokenService.consume(refreshToken);
        } catch (Exception e) {
            log.error("[auth/refresh] refresh token validation failed: {}", e.getMessage(), e);
            return AuthTokenVO.builder().ok(0)
                    .msg("refresh failed: refresh token validation backend error, try later")
                    .build();
        }
        if (identity == null) {
            return AuthTokenVO.builder().ok(0).msg("invalid refresh token").build();
        }
        String tenantId = TenantContext.normalize(identity.getTenantId());
        try {
            List<String> permissions = permissionService.permissionsForRoles(identity.getRoles());
            String token = jwtService.issueToken(identity.getPrincipal(), identity.getRoles(), permissions, tenantId);
            RefreshTokenService.RefreshTokenIssueResult refreshIssue =
                    refreshTokenService.issue(identity.getPrincipal(), identity.getRoles(), tenantId);
            return buildTokenResponse(token, tenantId, refreshIssue);
        } catch (IllegalArgumentException e) {
            log.error("[auth/refresh] refresh rejected: {}", e.getMessage());
            return AuthTokenVO.builder().ok(0).msg("refresh failed: " + e.getMessage()).build();
        } catch (Exception e) {
            // 部分失败：旧刷新令牌已一次性消费，而新凭证未能签出。客户端必须
            // 重新走 /auth/token 认证，明确告知这一点比笼统 500 更可用。
            log.error("[auth/refresh] refresh failed after consuming the old token", e);
            return AuthTokenVO.builder().ok(0)
                    .msg("refresh failed: token backend error; the consumed refresh token is no longer valid, re-authenticate with your api key")
                    .build();
        }
    }

    /**
     * 将刷新令牌写入 HttpOnly Cookie（Path=/auth，Max-Age 取刷新令牌剩余有效期）。
     * Secure 标志与 SameSite 策略受 {@code app.security.refresh-cookie-secure} 开关控制
     * （开发环境走明文 HTTP 时关闭）；SameSite 属性因 Servlet Cookie API 不支持而经
     * 手工 Set-Cookie 头补写，机制详见方法内注释。
     */
    private void setRefreshCookie(HttpServletResponse response, RefreshTokenService.RefreshTokenIssueResult refreshIssue) {
        // 防御式兜底：expiresAt 理论上由签发服务保证非空，但为空时不能让整个
        // /auth/token 响应 NPE —— 退化为会话级 Cookie（不带 Max-Age）。
        java.time.LocalDateTime expiresAt = refreshIssue.expiresAt();
        long maxAge = expiresAt == null ? -1L
                : Math.max(0L,
                        java.time.Duration.between(
                                java.time.Instant.now(),
                                expiresAt.atZone(java.time.ZoneOffset.UTC).toInstant()
                        ).getSeconds());
        // The Servlet Cookie API has no SameSite setter, so build the
        // Set-Cookie header by hand. The Secure flag is omitted in dev
        // profiles (refresh-cookie-secure=false) because Secure cookies
        // are not sent over plain HTTP, which is how the dev front-end
        // talks to the dev back-end.
        String secureAttr = refreshCookieSecure ? "; Secure" : "";
        String maxAgeAttr = maxAge < 0 ? "" : "; Max-Age=" + Math.min(maxAge, Integer.MAX_VALUE);
        String cookieValue = String.format(
                "%s=%s; Path=/auth%s; HttpOnly%s; SameSite=Strict",
                REFRESH_COOKIE,
                refreshIssue.rawToken(),
                maxAgeAttr,
                secureAttr);
        Cookie cookie = new Cookie(REFRESH_COOKIE, refreshIssue.rawToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(refreshCookieSecure);
        cookie.setPath("/auth");
        if (maxAge >= 0) {
            cookie.setMaxAge((int) Math.min(maxAge, Integer.MAX_VALUE));
        }
        response.addCookie(cookie);
        // The standard Cookie API does not expose SameSite; add a second
        // Set-Cookie header (with the SameSite attribute) so the
        // container is required to merge on serialization. The first
        // Set-Cookie (from addCookie) and the second (from addHeader)
        // carry the same value+attributes; downstream caches will keep
        // one of them per RFC 7234.
        response.addHeader("Set-Cookie", cookieValue);
    }

    /**
     * 签发新的 API Key（归属当前租户）。明文 rawApiKey 仅在本次响应中返回一次，服务端只存哈希。
     *
     * @param keyName  Key 名称，租户内唯一
     * @param roleName 角色名，缺省 USER，决定该 Key 的权限集
     * @return ok=1 含 keyName/tenantId/明文 Key/过期时间
     */
    @PostMapping("/api-keys")
    @PreAuthorize("hasAnyAuthority('PERM_AUTH_KEY_MANAGE','ROLE_ADMIN')")
    public ApiKeyIssueVO issueApiKey(@RequestParam(value = "keyName", required = false) String keyName,
                                     @RequestParam(value = "role", defaultValue = "USER") String roleName) {
        // 参数声明为可选并在控制器内校验：缺失/空白/超长给出明确 ok=0 原因，
        // 而不是框架层 500 或把空值透传给持久层。
        String paramError = firstError(keyNameError(keyName), roleNameError(roleName));
        if (paramError != null) {
            return keyFailure(paramError);
        }
        try {
            ApiKeyLifecycleService.ApiKeyIssueResult result = apiKeyLifecycleService.issue(
                    keyName, roleName, TenantContext.currentTenantId());
            return ApiKeyIssueVO.builder()
                    .ok(1)
                    .msg("ok")
                    .keyName(result.keyName())
                    .tenantId(result.tenantId())
                    .rawApiKey(result.rawApiKey())
                    .expiresAt(result.expiresAt())
                    .build();
        } catch (IllegalArgumentException e) {
            // 业务性拒绝（如同名活跃键已存在）：透出服务端原因。
            log.error("[auth/api-keys] issue api key '{}' rejected: {}", keyName, e.getMessage());
            return keyFailure("issue api key '" + keyName + "' failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("[auth/api-keys] issue api key '{}' failed", keyName, e);
            return keyFailure("issue api key '" + keyName + "' failed: credential storage backend error, try later");
        }
    }

    /**
     * 轮换指定 API Key：旧 Key 失效，生成新明文 Key（仅本次响应返回）。供定期轮换或疑似泄露处置。
     *
     * @param keyName 待轮换的 Key 名称
     * @param reason  轮换原因，审计用，缺省 rotation
     * @return ok=1 含新明文 Key 与新过期时间
     */
    @PostMapping("/api-keys/rotate")
    @PreAuthorize("hasAnyAuthority('PERM_AUTH_KEY_MANAGE','ROLE_ADMIN')")
    public ApiKeyIssueVO rotateApiKey(@RequestParam(value = "keyName", required = false) String keyName,
                                      @RequestParam(value = "reason", defaultValue = "rotation") String reason) {
        String paramError = firstError(keyNameError(keyName), reasonError(reason));
        if (paramError != null) {
            return keyFailure(paramError);
        }
        try {
            ApiKeyLifecycleService.ApiKeyIssueResult result = apiKeyLifecycleService.rotate(
                    keyName, reason, TenantContext.currentTenantId());
            return ApiKeyIssueVO.builder()
                    .ok(1)
                    .msg("rotated")
                    .keyName(result.keyName())
                    .tenantId(result.tenantId())
                    .rawApiKey(result.rawApiKey())
                    .expiresAt(result.expiresAt())
                    .build();
        } catch (IllegalArgumentException e) {
            // 业务性拒绝（如活跃键不存在，尚未撤销任何东西）：透出原因。
            log.error("[auth/api-keys/rotate] rotate api key '{}' rejected: {}", keyName, e.getMessage());
            return keyFailure("rotate api key '" + keyName + "' failed: " + e.getMessage());
        } catch (Exception e) {
            // 部分失败：rotate 在服务端是「撤销旧键 → 签发新键」，走到这里说明
            // 旧键可能已被撤销而新键未就绪，明确提示先核实状态再重试，避免
            // 盲目重试造成误解。
            log.error("[auth/api-keys/rotate] rotate api key '{}' failed", keyName, e);
            return keyFailure("rotate api key '" + keyName + "' failed: credential storage backend error;"
                    + " the old key may already be revoked, verify its status before retrying");
        }
    }

    /**
     * 吊销指定 API Key（立即失效，不产生新 Key）。
     *
     * @param keyName 待吊销的 Key 名称
     * @param reason  吊销原因，审计用，缺省 manual revoke
     * @return ok=1，仅回显 keyName/tenantId（无新 Key 信息）
     */
    @PostMapping("/api-keys/revoke")
    @PreAuthorize("hasAnyAuthority('PERM_AUTH_KEY_MANAGE','ROLE_ADMIN')")
    public ApiKeyIssueVO revokeApiKey(@RequestParam(value = "keyName", required = false) String keyName,
                                      @RequestParam(value = "reason", defaultValue = "manual revoke") String reason) {
        String paramError = firstError(keyNameError(keyName), reasonError(reason));
        if (paramError != null) {
            return keyFailure(paramError);
        }
        String normalizedTenant = TenantContext.currentTenantId();
        try {
            apiKeyLifecycleService.revoke(keyName, reason, normalizedTenant);
        } catch (IllegalArgumentException e) {
            log.error("[auth/api-keys/revoke] revoke api key '{}' rejected: {}", keyName, e.getMessage());
            return keyFailure("revoke api key '" + keyName + "' failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("[auth/api-keys/revoke] revoke api key '{}' failed", keyName, e);
            return keyFailure("revoke api key '" + keyName + "' failed: credential storage backend error;"
                    + " the key may still be active, verify its status before retrying");
        }
        return ApiKeyIssueVO.builder()
                .ok(1)
                .msg("revoked")
                .keyName(keyName)
                .tenantId(normalizedTenant)
                .build();
    }

    /** 认证类（/auth/*）失败响应：ok=0 + 说明原因的 msg。 */
    private static AuthTokenVO tokenFailure(String msg) {
        return AuthTokenVO.builder().ok(0).msg(msg).build();
    }

    /** API Key 管理类（/auth/api-keys*）失败响应：ok=0 + 说明原因的 msg。 */
    private static ApiKeyIssueVO keyFailure(String msg) {
        return ApiKeyIssueVO.builder().ok(0).msg(msg).build();
    }

    /** 返回第一个非空的校验错误信息；全部通过时为 null。 */
    private static String firstError(String... errors) {
        for (String error : errors) {
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    /** keyName 空值/越界校验；非法时返回给客户端的失败原因，null 表示通过。 */
    private static String keyNameError(String keyName) {
        if (!StringUtils.hasText(keyName)) {
            return "keyName is required";
        }
        if (keyName.length() > MAX_KEY_NAME_LENGTH) {
            return "keyName must not exceed " + MAX_KEY_NAME_LENGTH + " characters";
        }
        return null;
    }

    /** 角色名空值/越界校验；非法时返回给客户端的失败原因，null 表示通过。 */
    private static String roleNameError(String roleName) {
        if (!StringUtils.hasText(roleName)) {
            return "role is required";
        }
        if (roleName.length() > MAX_ROLE_NAME_LENGTH) {
            return "role must not exceed " + MAX_ROLE_NAME_LENGTH + " characters";
        }
        return null;
    }

    /** 审计原因越界校验；非法时返回给客户端的失败原因，null 表示通过。 */
    private static String reasonError(String reason) {
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            return "reason must not exceed " + MAX_REASON_LENGTH + " characters";
        }
        return null;
    }

    /**
     * 组装 /auth/token 与 /auth/refresh 共用的成功响应体：访问 token、刷新令牌（明文，兼容旧前端）、
     * 租户、访问令牌秒级有效期，以及「刷新令牌是否将于 {@link #REFRESH_EXPIRE_SOON_DAYS} 天内过期」
     * 的提示位（供前端决定是否提示重新登录）。
     */
    private AuthTokenVO buildTokenResponse(String token,
                                           String tenantId,
                                           RefreshTokenService.RefreshTokenIssueResult refreshIssue) {
        LocalDateTime refreshExpiresAt = refreshIssue.expiresAt();
        boolean refreshWillExpireSoon = refreshExpiresAt != null
                && refreshExpiresAt.isBefore(LocalDateTime.now().plusDays(REFRESH_EXPIRE_SOON_DAYS));
        return AuthTokenVO.builder()
                .ok(1)
                .msg("ok")
                .token(token)
                .refreshToken(refreshIssue.rawToken())
                .tenantId(tenantId)
                .expiresInSeconds(securityProperties.getJwtExpireMinutes() * 60L)
                .refreshExpiresAt(refreshExpiresAt)
                .refreshWillExpireSoon(refreshWillExpireSoon)
                .build();
    }
}

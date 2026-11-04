# 缺陷审查报告：AuthController.java

- **审查对象**：`src/main/java/com/enterprise/iqk/controller/AuthController.java`（251 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`ApiKeyAuthService`（全文）、`ApiKeyLifecycleService`（全文）、`JwtService`（全文）、`RefreshTokenService`（全文，含 `consume` 的条件撤销 SQL 语义）、`PermissionService`（全文）、`TenantContext`（全文）、`SecurityProperties`（全文）、`AuthIdentity`/`AuthTokenVO`/`ApiKeyIssueVO`（全文）、`SecurityConfiguration`（路由授权与过滤器链）、`ApiKeyOrJwtAuthFilter`（公开路径判定与租户 MDC 写入）、`RequestContextFilter`、`RateLimitFilter`（与游客限流对照）、`GlobalExceptionHandler`；并用 grep 核实调用方：前端 `frontend/src/api/client.ts`、`frontend/src/api/authRecovery.ts`（仅经 HTTP 调用 `/auth/token|refresh|guest`），后端无其他 Java 调用方；权限种子数据核实至 `db/migration/V1__init_core_and_platform_tables.sql`、`V4__expand_permission_matrix.sql`
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm/mvn 操作
- **关联报告**：`reports/audit-WorkspaceRuntime.java.md`、`reports/audit-ReactAgentService.java.md`、`reports/audit-EvaluationService.java.md` 等（本项目既有 7 份）

---

## 概览

该类是认证面：API key 换 JWT + refresh token（`/auth/token`）、refresh token 轮换（`/auth/refresh`）、游客短时效 JWT（`/auth/guest`）、API key 生命周期管理（`/auth/api-keys[/rotate|/revoke]`）。做对的部分不少：

- `/auth/token` 校验 `X-Tenant-Id` 头与 API key 归属租户一致（`:63-65`），堵住了"跨租户铸造 token"的口子；JWT 内嵌租户取自身份而非请求头。
- Refresh token 为单次使用：`RefreshTokenService.consume` 用条件 UPDATE 撤销旧 token，并发重放时只有第一个调用者成功（`RefreshTokenService.java:52-56`），重放检测正确。
- Refresh cookie 的设计意图正确：HttpOnly、Path 收窄到 `/auth`、Secure 可配、SameSite=Strict，且有清晰的开发模式开关（`:44-52`）。
- `/auth/api-keys/**` 的 `@PreAuthorize` 与 `SecurityConfiguration.java:56` 的路由规则双层一致；`PERM_AUTH_KEY_MANAGE` 经 V4 迁移仅授予 ADMIN（role_id=1），USER/OPS 无此权限，key 管理面无水平提权路径。
- 游客 JWT 为独立 `GUEST` 角色 + 120 分钟短时效，权限列表最小化意图明确。
- 失败路径统一返回 `ok:0` 结构化体；`IllegalArgumentException` 经 `GlobalExceptionHandler` 映射 400，密钥管理的参数错误不会变成 500。

但存在 **2 个高危问题**（refresh 链路对 cookie-only 客户端实际断裂；游客限流可被伪造头绕过）和若干中低问题。所有修复建议均不需变更任何对外 HTTP 接口签名。

---

## 高严重度

### H-1 `/auth/refresh` 轮换后不回写 Cookie，cookie-only 客户端的刷新链第二次必断

- **位置**：`AuthController.java:135-153`（`refresh` 方法）；对照 `:76`（`/auth/token` 成功后调用了 `setRefreshCookie`）
- **描述**：`/auth/token` 把 refresh token 以 HttpOnly cookie 下发，但 `/auth/refresh` 在 `consume()`（**该调用会撤销旧 token**，`RefreshTokenService.java:54`）并签发新 refresh token 后，只把新 token 放进响应体（经 `buildTokenResponse:244`），**从不调用 `setRefreshCookie`**。于是纯 cookie 客户端（`credentials:'include'`、依赖 HttpOnly cookie 的浏览器场景）第一次刷新成功后，cookie 里留存的仍是已被撤销的旧 token，第二次刷新必然返回 `invalid refresh token`，刷新链在第二步断裂——cookie 方案宣称的目标（XSS 安全 + 不依赖 localStorage 的长会话）实际走不通。
- **触发条件**：客户端不带 `X-Refresh-Token` 头、仅凭 cookie 调 `/auth/refresh` 两次。当前前端 `authRecovery.ts:162-182` 恰好靠 localStorage 里的 header 值才没踩中——这正是该缺陷处于潜伏状态的原因，但 cookie 机制本身就是为摆脱 localStorage 而设计的。
- **影响**：refresh token 轮换 + cookie 化的安全方案不完整；一旦前端按设计迁移到 cookie 模式（代码注释 `:70-75` 所述的 PR B 方向），用户会在两次刷新后被登出。
- **修复建议**：在 `refresh` 成功路径上补 `setRefreshCookie(response, refreshIssue)`（需给 handler 注入 `HttpServletResponse` 参数——这是 Spring 注入的运行时参数，对外 HTTP 契约与请求/响应体完全不变，不违反"不得变更对外 API 签名"约束）。

### H-2 游客限流信任最左 `X-Forwarded-For`，可无限绕过

- **位置**：`AuthController.java:130-133`（`clientIpOf`：`forwarded.split(",")[0].trim()`，无条件采信）；限流判断在 `:87-89`
- **描述**：`clientIpOf` 把客户端可任意伪造的 `X-Forwarded-For` 最左值当作限流键，没有 `RateLimitFilter.resolveClientIp`（`RateLimitFilter.java:80-116`）里那套防线——那里仅在直连地址为私有/回环（即确在反代之后）时才采信 XFF，且取**最右非私有跳**并明确注释了"恶意调用者不能注入伪造的最左条目"。同一请求进来，`RateLimitFilter` 能算出真实 IP，`AuthController` 却用攻击者随口填的字符串：攻击者每次请求换一个 XFF 值即可绕过"每 IP 每小时 30 个"的游客限额，无限铸造 120 分钟的 `GUEST` JWT。
- **触发条件**：直连部署（无反代）时 `RateLimitFilter` 退回 `remoteAddr`，controller 层限流完全失效；经反代部署时 `RateLimitFilter` 用真实 IP 仍有一个共享桶兜底，但 controller 层这道每 IP 额度照样可被任意轮换 XFF 打穿。
- **影响**：游客 token 滥用防线形同虚设：GUEST 角色虽只读 `chat/rag` 等低敏权限，但无限量合法 JWT 可直接打满聊天/RAG 资源与下游 LLM 配额（成本面），并污染会话与反馈数据。
- **修复建议**：复用 `RateLimitFilter.resolveClientIp`（已具备正确的反代判定），或将其抽为共享工具类；不建议在 controller 里另写一套 IP 解析。顺带可把限流键与 `RateLimitFilter` 的键对齐，避免两层各算各的。

---

## 中严重度

### M-1 游客计数器 `clear()` 全量清空，攻击者可一键重置所有人的限额并制造内存抖动

- **位置**：`AuthController.java:120-122`（`guestCounters.size() > 10_000` 时 `guestCounters.clear()`）；`:104-106`（键为不受控的 IP 字符串）
- **描述**：键是 `clientIpOf` 的返回值（结合 H-2 即客户端可控字符串），攻击者用上万个伪造 XFF 值即可把 map 灌到 10k 触发 `clear()`——该操作**不是**淘汰最旧，而是把所有 IP 的计数归零：合法用户的已用额度被重置，窗口语义被破坏；配合 H-2，攻击者的"自己被限流"问题也不存在。同时每次 clear 都丢弃全部条目再重新分配，属于反复的全量内存抖动。
- **触发条件**：约 1 万次携带不同伪造 XFF 的 `POST /auth/guest`（即使被限流拒绝，key 也已先入 map，`:111` 的 `computeIfAbsent` 在限额判断之前执行）。
- **影响**：游客限流窗口语义失效；计数 map 成为可远程操纵的内存与分配抖动源。
- **修复建议**：改用带过期淘汰的结构（如 Caffeine `expireAfterAccess(1h)`），或至少按 hour 分桶、清理 `counter[0] != hour` 的陈旧条目而非全清；配合 H-2 修复后键基数回归真实 IP 数量，10k 阈值本身也不再必要。

### M-2 `setRefreshCookie` 双写 `Set-Cookie`，SameSite 属性依赖"恰好后到者胜"

- **位置**：`AuthController.java:173-178`（`response.addCookie(cookie)`，产生**不含** SameSite 的头）；`:185`（`response.addHeader("Set-Cookie", cookieValue)`，含 SameSite=Strict）；注释 `:179-184` 声称"容器会合并/缓存会保留一个"
- **描述**：同一响应发出两个同名同 Path 的 `Set-Cookie`。RFC 6265 处理模型下，同名同 domain/path 的 cookie 后写覆盖先写——当前浏览器实现恰好"后者（手工头）胜出"保住了 SameSite，但这是**顺序巧合而非契约**：注释里"container is required to merge"并无此规范依据；部分代理/CDN 会合并或去重同名 Set-Cookie（往往保留第一个），届时 SameSite=Strict 静默丢失，cookie 回落浏览器默认 Lax，CSRF 防线降级且无人察觉。`refreshCookieSecure=false` 的开发模式下两头的差异只剩 SameSite，问题同样存在。
- **触发条件**：任何经过会合并/重排 `Set-Cookie` 头的中间层，或未来容器序列化顺序变化。
- **影响**：refresh cookie 的 SameSite 保护可能静默失效；两份头也让排障时的报文与代码意图对不上。
- **修复建议**：删掉 `addCookie` 分支，只保留手工 `Set-Cookie`（token 值为 UUID+"." 格式，无分号/逗号，手工序列化安全）；更稳妥的是改用 `org.springframework.http.ResponseCookie`（原生支持 SameSite），单次 `addHeader` 写入，两处属性自然一致。

### M-3 Cookie `Max-Age` 按 UTC 换算服务器本地时间，生命周期随部署时区偏移

- **位置**：`AuthController.java:156-160`（`expiresAt().atZone(ZoneOffset.UTC).toInstant()`）；对照 `RefreshTokenService.java:26`（`expiresAt = LocalDateTime.now().plusDays(...)`，服务器默认时区）
- **描述**：`expiresAt` 是以服务器**默认时区**的 `LocalDateTime.now()` 为基准生成的，此处却把它当作 UTC 时间转回 `Instant` 求差。服务器时区非 UTC 时，Max-Age 偏差恰等于时区偏移：东八区部署 cookie 少 8 小时，UTC-5 少 5 小时，反之西半球偏大。cookie 实际寿命与数据库记录的 `expires_at` 不一致，且偏差随部署环境漂移。
- **触发条件**：JVM 默认时区 ≠ UTC（容器未显式设 `TZ=UTC` 时极常见）。
- **影响**：refresh cookie 提前失效（用户被迫提前用 header/重新登录）或晚于记录过期（后一段 cookie 指向已过期 token，刷新被拒）——两种都造成"有效期显示对、实际不对"的难以排查的会话问题。
- **修复建议**：去掉时区转换，直接 `Duration.between(LocalDateTime.now(), refreshIssue.expiresAt()).getSeconds()`，与签发端的时钟基准保持同一时域。

### M-4 Refresh token 仍随响应体下发，HttpOnly cookie 的 XSS 防线在同端点上自我瓦解

- **位置**：`AuthController.java:70-77`（注释自述过渡方案）、`:244`（`buildTokenResponse` 的 `refreshToken` 字段）；`/auth/refresh` 同样经 `:152` 回传
- **描述**：cookie 方案的存在理由（`:70-72` 注释）是"防 XSS 窃取长效凭证"，但同一响应体里仍带明文 refresh token——XSS 读 body 一样拿得到，防线只剩"提高一点门槛"。注释明确这是等前端（App.vue）迁移到 `credentials:'include'` 的过渡债务。核实前端现状：`client.ts:110,161` 与 `authRecovery.ts:168` 仍在用 localStorage + `X-Refresh-Token` 头，即过渡状态实际仍在持续。
- **触发条件**：前端存在可注入的 XSS 点（任意时刻）。
- **影响**：14 天有效期的长效凭证可被窃取；H-1 修复后若前端继续双轨，此暴露面将无限期存在。
- **修复建议**：给迁移定死期限；前端切换到 cookie 后从 `AuthTokenVO` 响应中移除 `refreshToken` 字段（新增响应字段或收缩字段均不破坏既有对外 API 签名；收缩前需确认所有客户端不再读取该字段，可用响应体字段埋点观察）。

---

## 低严重度

### L-1 游客权限列表与注释不一致（含 `rag:read`）

- **位置**：`AuthController.java:81-83`（注释："仅 chat/session/feedback 读写"）vs `:92-94`（实际含 `rag:read`）
- **描述/影响**：`rag:read` 使游客可触发 RAG 问答链路（对应 LLM 成本），注释却未提——维护者会低估游客面的资源消耗。权限本身最小化设计没问题，属于文档失真。
- **修复建议**：修注释把 `rag:read` 列入，或在配置中显式声明游客可用的权限集。

### L-2 游客 principal 由 IP+日期哈希派生，可预测且可碰撞

- **位置**：`AuthController.java:90-91`（`"guest-" + Integer.toHexString(Objects.hash(clientIp, date))`）
- **描述**：32 位哈希：同 IP 同日 principal 固定（可预测、可被第三方在日志/会话记录中关联到具体 IP）；不同 IP 同日碰撞（2^32 空间，访客量大时非可忽略）会共享 principal，审计归因混淆。GUEST 权限固定，无越权后果。
- **修复建议**：直接用 `UUID.randomUUID()`；如需按 IP 聚合统计，放在服务端日志/MDC 而非 principal。

### L-3 API key 签发未校验 `roleName` 与 `keyName`

- **位置**：`AuthController.java:190-191`（`role` 默认 USER、无校验）、`:206`（rotate 未校验，取旧值所以尚可）、`:222`（revoke 同）
- **描述**：`roleName` 任意字符串直通 `ApiKeyLifecycleService.issue` 入库：拼错的角色名会签发出"存在但零权限"的 key（`PermissionService.java:24` 查无权限返回空列表），静默造成"发了 key 却什么都干不了"的排障成本；空白/超长 `keyName` 也未拦截。权限面已核实无提权风险：能调这三个端点的仅 ADMIN（V4 迁移中 `PERM_AUTH_KEY_MANAGE` 仅授予 role_id=1），故最多是"ADMIN 给自己造废 key"。
- **修复建议**：校验 `roleName` ∈ roles 表（可注入查询或维护合法枚举）、`keyName` 非空白且限长；校验失败抛 `IllegalArgumentException` 走既有 400 通道。

### L-4 游客限流的包装方法与固定窗口边界

- **位置**：`AuthController.java:126-128`（`guestLimiterTryAcquire` 纯转发 `guestLimiterTryAcquireInternal`）、`:109`（`synchronized` 实例锁）、`:108-123`（固定窗口）
- **描述**：包装方法无任何附加逻辑，徒增一层间接；固定窗口在整点切换时允许瞬时 2 倍配额（30+30）；`synchronized` 是单实例粗粒度锁（此处并发量低，无实际危害）。配合 M-1 重写限流器时一并清理即可。
- **修复建议**：删除转发包装；改滑动窗口或接受边界突发并注释说明。

### L-5 `app.security.enabled=false` 时 key 管理端点行为不一致（fail-safe，但应显式记录）

- **位置**：`AuthController.java:189/205/221`（`@PreAuthorize`）；对照 `SecurityConfiguration.java:47-48`（安全关闭时全部 `permitAll`）
- **描述**：`security.enabled=false` 时过滤器链放行一切，但 `@EnableMethodSecurity` 常开且无认证上下文，`@PreAuthorize` 仍会把 `/auth/api-keys/**` 拒成 403——"安全总开关关闭"模式下，匿名用户能拿到业务 API 却拿不到 key 管理端点。方向是 fail-safe（比反着漏好），但与该开关的语义不一致，容易让联调环境误判。
- **修复建议**：在 `SecurityProperties.enabled` 的文档/README 中注明：关闭安全开关不影响 `@PreAuthorize`，key 管理仍需真实凭证；或在该模式下统一拒绝策略并写测试固定行为。

---

## 修复优先级建议

1. **P0**：H-1（`/auth/refresh` 回写 cookie）——一行级修复，决定整个 cookie 化方案能否成立。
2. **P1**：H-2 + M-1（游客限流：IP 解析对齐 `RateLimitFilter`、计数器改过期淘汰，两处宜一次改完）；M-2（`Set-Cookie` 单头化，顺手换 `ResponseCookie`）；M-3（Max-Age 去时区换算）。
3. **P2**：M-4（给 refresh token 出响应体的迁移定期限）；L-1~L-5 随下次触碰相关代码时清理。

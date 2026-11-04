# 架构分析报告：AuthController 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；每条结论附源码位置证据）
- **分析对象**：`src/main/java/com/enterprise/iqk/controller/AuthController.java`（251 行）
- **核查范围**：全部 6 个注入协作者 + 1 个 `@Value` 字段，逐个下钻其二跳出边（grep 证实 security 包 5 服务只依赖 mapper/domain/util/properties）；`SecurityConfiguration.java:37-89` 过滤器链与放行清单；`ApiKeyOrJwtAuthFilter:100-102` 免认证清单；`RateLimitFilter.resolveClientIp`（:77-96）信任模型全文；`PermissionService` 的 DB 驱动角色映射；`JwtService` 两个 `issueToken` 重载（:56/:61）；`SecurityProperties` 默认值；前端 `client.ts:29/:156-166` 的 refresh 消费方式；既有 `audit-AuthController.java.md` 全部发现承接（H-1/H-2/M-1~M-4/L-1~L-5）
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm/mvn 构建、动笔前已完整读目标文件、未变更任何对外 API 签名
- **关联报告**：`audit-AuthController.java.md`（同文件缺陷审查，H-1 轮换不回写 Cookie/H-2 XFF 绕过/M-1 计数器全清/M-2 双 Set-Cookie 等以该报告为准）、`architecture-enterprise.md` 审查（实例内存态无状态性反例清单）、`audit-client.ts.md`（前端凭证通道的对照面）

## 1. 模块定位

`/auth` 命名空间下的认证门面，六个端点两组职责：

1. **凭证签发**（全放行，`SecurityConfiguration:55` + `ApiKeyOrJwtAuthFilter:100-102` 双重免认证）：`/auth/token`（API key → JWT + refresh cookie，:54-78）、`/auth/refresh`（header 或 cookie → 轮换，:135-153）、`/auth/guest`（无凭证游客 JWT，:84-102）；
2. **API key 生命周期管理**（`@PreAuthorize` 三处，:188-232）：issue/rotate/revoke，薄委托 `ApiKeyLifecycleService`。

**本类的特殊性：它是全库唯一「自身就是安全边界」的 controller**。普通 controller 的租户/身份错误有过滤器链兜底，而本类三个签发端点被整条过滤器链放行——类内每一行判断（租户匹配 :63-65、XFF 解析 :130-133、限流 :87）都没有外层第二道闸，**controller 内联实现的质量直接等于安全边界质量**。这决定了它的越层评估标准必须严于普通 controller。

## 2. 依赖全景

### 2.1 出边（6 个注入协作者 + 1 个 @Value）

| 依赖 | 所在包 | 二跳出边（已下钻） | 方向评价 |
| --- | --- | --- | --- |
| `ApiKeyAuthService` | security | ApiKeyMapper、PermissionMapper、HashUtils | ✅ 干净 |
| `ApiKeyLifecycleService` | security | SecurityProperties、ApiKeyMapper、HashUtils | ✅ 干净 |
| `JwtService` | security | SecurityProperties | ✅ 叶子 |
| `RefreshTokenService` | security | SecurityProperties、RefreshTokenMapper、HashUtils | ✅ 干净 |
| `PermissionService` | security | PermissionMapper | ✅ 干净 |
| `SecurityProperties` | config.properties | 零 iqk 依赖 | ✅ 叶子 |
| `refreshCookieSecure`（@Value :51-52） | — | 运行环境属性，绕过 properties 类直取——本类唯一的配置面旁路 | ⚠️ 见 5.4 |

五个 security 协作者**无一回指 controller 包**（import 清单 grep 证实）。静态依赖：`TenantContext`（security，:62/:147/:193/:209/:224）、Servlet Cookie/Response API（:155-186）。

### 2.2 入边

- **零调用方**——controller 是入口层，无任何类注入 AuthController。`REFRESH_COOKIE` 常量（:35，`kops_refresh`）全后端仅本类引用，前端 `client.ts` 也未引用该 cookie 名（其 refresh 走 `X-Refresh-Token` header + localStorage，client.ts:161）——**cookie 当前没有实际消费方**，是为「App.vue 迁移 PR B」预埋的前向契约（:70-75 注释自述），契约名只存在于本类一处。
- 对外类型：`AuthTokenVO`/`ApiKeyIssueVO`，被前端按响应形状消费（裸 VO 风格，与 `Result{ok,msg}` 风格在本类内部混用——`ok/msg` 字段实为两种风格的杂交）。

### 2.3 横切依赖

无 Meter（本类零指标——对比 rag/ingestion 管线的指标密度，认证端点的失败/限流拒绝完全不可观测）；无事件；无 MDC 直读（租户全部取自 identity，正确）。

## 3. 循环依赖评估

**Bean 级与包级均无环，方向是教科书式的。**

- Bean 图：controller → security×5 → mapper，严格单向三层。
- 包图：`controller → security → mapper/util/config.properties`，security 包没有任何指向 controller 或 web 层的边。本类与 `security` 包的关系是「web 编排层消费安全服务」的正确分层，对比组内其他 controller（如 `IngestionController` 还要自行取 Tracer/Properties 组装 VO），本类的出边收敛度最高。
- 唯一的结构性瑕疵不在环上而在**职责下沉缺失**（见 4.1）：guest 能力整体长在 controller，使 security 包「看起来」完整而实际缺一块——环风险为零，但内聚缺口真实存在。

## 4. 越层评估

### 4.1 游客身份体系整体内联在 controller（本报告核心结构发现）

`/auth/guest` 不是一个 HTTP 映射，是一个**完整的微型身份子系统**长在 controller 里：IP 限流器（字段 :104-106 + 方法 :109-128）、principal 生成算法（:90-91，IP+日期哈希）、权限清单（:92-94）、TTL 决策（:95 硬编码 120L 分钟）、租户硬编码 `"public"`（:95/:100）。对照组：`/auth/token` 和 `/auth/refresh` 是标准的「编排五步」（鉴权→归一→权限→签 JWT→签 refresh，:58-69 与 :143-151），逻辑全部在 security 包，controller 只做参数搬运。而 guest 的每一环都是 controller 私有实现——security 包的认知模型（Identity/JWT/限流）在这里断档。

这不是抽象洁癖：它的直接后果是 4.2/4.3/5.2 的三个平行实现——**正因为能力没有下沉，它既接不上 `RateLimitFilter` 的限流设施，也接不上 `PermissionService` 的权限矩阵，还自带了一份与 `resolveClientIp` 信任模型相反的 IP 解析**。修复方向（不改任何 API 签名）：抽 `GuestTokenService` 进 security 包，controller 退回编排层。

### 4.2 限流三重平行：filter 级设施已存在，controller 又养一个

限流请求实际穿过**两层独立限流**：`RateLimitFilter` 在过滤器链末位（SecurityConfiguration:88）对所有请求按 `tenant+ip` 限流（无 /auth 豁免，全文核实无 `shouldNotFilter`），controller 内又有 guest 专属 IP 计数器（:105）。后者是 singleton 实例状态（`ConcurrentHashMap` 字段）——多实例部署限流按实例分桶失效、重启清零（arch-enterprise 无状态性反例 `TrustedActionService` 同型），且 `clear()` 全量重置（audit M-1）。**框架级限流设施已存在且恰好覆盖该端点，controller 的第二套实现没有增量价值，只有漂移面**。而它存在的唯一理由是粒度（每 IP 30/h 的游客专项额度）——这恰好是应该下沉到 `GuestTokenService` 或以 `RateLimitFilter` 规则扩展表达的需求。

### 4.3 `clientIpOf` 与 `RateLimitFilter.resolveClientIp` 信任模型相反（audit H-2 的架构面）

同一个「客户端 IP」概念，全库两套解析器：

| | `RateLimitFilter.resolveClientIp`（:77-96，static 包私有） | 本类 `clientIpOf`（:130-133） |
| --- | --- | --- |
| XFF 信任条件 | 直连 peer 是私网/回环才信任（:83） | 无条件信任 |
| 取哪个跳 | 最右非私网地址（防注入） | **最左**（可注入） |
| 后果 | 限流桶不可被 header 旋转 | guest 限流可被 header 旋转无限绕过（audit H-2）+ principal 身份可被 header 操纵（:90 的哈希输入即此 IP） |

`resolveClientIp` 是 static 包私有——controller 包**用不了**，这是平行实现得以存活的包结构原因。修复：把它提升为 security 包的公共组件（如 `ClientIpResolver`），两处收敛。注意提升后 guest 的语义还会联动变化：principal 由「伪造 IP」变为「真实 peer」，游客身份稳定性反而提高。

### 4.4 GUEST 权限清单：全库唯一的代码内联权限定义点

角色→权限映射的既有体系是 DB 驱动（`PermissionService:24` → `PermissionMapper.findByRoleName`，种子在 V4/V5 迁移），而 guest 的 6 条权限硬编码在 controller（:92-94），`GUEST` 角色不在权限矩阵种子里——**硬编码是必须的旁路**（走矩阵会返回空），但代价是权限矩阵出现第二体系：新增 guest 权限要改 Java 而非迁移脚本，且「GUEST 能干什么」的审计答案分散在两处。audit L-1 已指出清单与注释不一致（含 `rag:read`）；架构面是定义点归属错误。

### 4.5 免认证端点上的每个内联判断都是最终防线

在 1 节结论下重新审视类内判断的层级归属：租户 header 一致性校验（:63-65）逻辑正确（identity 权威、header 仅声明），但它长在 controller 意味着任何未来新增的免认证端点都要靠作者记得复制这个模式——`ApiKeyOrJwtAuthFilter` 的放行清单（:100-102）与本类端点之间是**字符串约定而非结构约束**（`startsWith("/auth/token")` 前缀匹配还意味着 `/auth/tokens` 之类路径会被意外放行）。边界建议：放行清单收敛为枚举/常量共享，或至少前缀匹配改精确匹配。

### 4.6 双 Set-Cookie 写法：平台限制的 workaround 内联（audit M-2 的架构面）

Servlet Cookie API 无 SameSite setter，本类用 `addCookie`（无 SameSite）+ `addHeader`（带 SameSite）双写（:173-185），注释声称「container is required to merge」——**Servlet 规范不合并**，实际发出两条同名 Set-Cookie，正确性依赖浏览器「后到者胜」恰好 SameSite 版在后。这是把平台限制的绕法、RFC 语义猜测、升级兼容三件事压在一个方法里。修复方向：只保留 `addHeader` 单写（Cookie 对象仅用于组装，不 addCookie），语义等价且消除双头——不改对外 API 签名。

## 5. 平行实现

### 5.1 编排五步两份拷贝，且正是 audit H-1 的病灶

`token()`（:58-77）与 `refresh()`（:143-152）共享同一签发编排（归一→permissionsForRoles→issueToken→issue refresh→buildTokenResponse），逐行比对仅两处差异（鉴权源、无 cookie 写入）。**refresh 分支少了 `setRefreshCookie` 正是 audit H-1「cookie-only 客户端第二次刷新必断」的根源**——编排重复不是风格问题，它让轮换语义已经分叉：header 通道拿到新 refresh token，cookie 通道拿到过期的旧 cookie。收敛为一个私有编排方法（identity + response → AuthTokenVO）即同时修复 H-1 与重复。

### 5.2 IP 解析两份（4.3，信任模型相反）、限流两套（4.2，粒度不同）——均已在越层节展开，此处计入平行实现总账。

### 5.3 guest 响应的 `expiresInSeconds` 契约漂移（同型于 debugRetrieve 死参数）

`buildTokenResponse:246` 恒用 `securityProperties.getJwtExpireMinutes()*60` 计算 TTL 声明值，而 guest token 的实际 TTL 是硬编码 `120L` 分钟（:95）。默认配置（`jwtExpireMinutes=120`，SecurityProperties:11）下两者**碰巧一致**；运营者一旦调整配置，guest token 实际 120 分钟、响应声明另一个值——调用方据 `expiresInSeconds` 做刷新调度即漂移。修复：guest 路径传实际 TTL（方法加参或 guest 单独组装），不改对外端点签名。

### 5.4 配置面双轨

本类唯一的 `@Value`（:51-52）绕过 `SecurityProperties` 直取 yml——同类的 `jwtExpireMinutes`/`refreshExpireDays` 都走 properties 类。单字段无伤，但它让「app.security.*」配置的读取入口出现第二个位置，运营者 grep `SecurityProperties` 会漏掉这个安全相关开关（cookie Secure 标志）。收敛：并入 SecurityProperties。

## 6. 修复优先级建议（均不改对外 API 签名）

1. **P0** `clientIpOf` 收敛到 `resolveClientIp`（提升为 security 公共组件）——同时修复 audit H-2 并稳定 guest principal 语义（4.3）。
2. **P0** `token()`/`refresh()` 编排收敛，refresh 回写 cookie——同时修复 audit H-1（5.1）。
3. **P1** guest 能力下沉 `GuestTokenService`（security 包）：限流器、principal 算法、权限清单、TTL 随迁；限流优先评估复用/扩展 `RateLimitFilter` 而非私有计数器（4.1/4.2）。
4. **P1** 双 Set-Cookie 改单头写法（4.6）；`expiresInSeconds` 契约修复（5.3）。
5. **P2 记录项**：GUEST 权限进权限矩阵或 SecurityProperties 显式声明（4.4）；放行清单前缀匹配改精确（4.5）；`refreshCookieSecure` 并入 SecurityProperties（5.4）；认证端点补失败/限流拒绝指标（2.3）——本类是全库指标密度最低的安全边界。

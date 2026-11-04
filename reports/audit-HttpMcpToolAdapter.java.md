# 缺陷审查报告：HttpMcpToolAdapter.java

- **审查对象**：`src/main/java/com/enterprise/iqk/agent/harness/HttpMcpToolAdapter.java`（179 行，全文通读）
- **审查方式**：只读静态审查；直接协作者已逐一核实——`McpToolAdapter` 接口（全文，三参 `execute` 有 default 实现，本类覆写了三参版）、`AgentHarnessProperties.Mcp/McpServer/McpTool`（全文，默认 `timeoutMs=5000`、`path="/mcp/tools/call"`、`allowedHosts` 默认空列表、`servers` 默认空 Map）、三个 yml（`application.yml`/`-dev`/`-prod` 均无 `mcp:` 配置，`servers` 默认为空 → **本 adapter 在默认部署下完全惰性**）、调用方 `McpToolRuntime.java:26-46`（`execute` 前必经 `supports()` 筛选，adapter 返回 `status=error` 的 Map 被转为 `AgentObservation.error`，其余一律包装为 `AgentObservation.success`）、动作门控 `ActionSchemaRegistry.java:25-27`（`mcp_call` 注册为 **trustedOnly=true**，需受信 runtime + 未被 `disabledActions` 禁用）与 `ActionPolicyGuard.java:23-63`；JDK HttpClient 默认 redirect 策略为 `NEVER`（代码未显式设置，属既有正确行为）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm/mvn 操作
- **关联报告**：`reports/audit-WorkspaceRuntime.java.md`（同 harness 包，`max-file-bytes` 上限类比）、`reports/audit-AgentWorkflowEngine.java.md`

---

## 概览

该类是 MCP 工具的出站 HTTP 适配器：LLM 通过 `mcp_call` 动作触发，adapter 将请求封装为 JSON-RPC `tools/call` POST 到运营者预配置的 baseUrl。安全设计意图明确（注释写在类头与 `isSafeBaseUrl` 上）：仅允许公网 HTTP(S) 端点，拒绝回环/内网/链路本地/云元数据地址，并允许运营者用 `allowed-hosts` 显式豁免。做对的部分：

- **SSRF 纵深防御分层正确**：`supports()`（`:56`）与 `resolveUri()`（`:100`）双重调用 `isSafeBaseUrl`，即使调用方跳过 supports 门控，`execute` 内仍会拦截（抛 IAE 走 error Map）。
- **黑名单覆盖了注释声称的主要类别**：loopback、anyLocal（0.0.0.0）、link-local（169.254.169.254 云元数据）、site-local（RFC1918）、multicast（`:145-147`），且对**所有**解析结果做检查（任一命中即拒），不是只查第一个——防止多 A 记录部分命中绕过。
- **scheme 白名单**（仅 http/https，`:29`/`:132`）排除了 `file:`/`gopher:` 等；URI 解析失败一律拒绝（`:126-129`）。
- **重定向未跟随**：`HttpClient.newHttpClient()` 默认 `NEVER` 策略，敌意服务器无法用 302 把请求重定向到内网（若显式改为 `NORMAL` 会破坏这层防护，值得在注释中固化）。
- **响应体大小上限存在**（`:80-83`）；timeout 有 `Math.max(1, ...)` 防零/负值（`:74`）；异常统一捕获转结构化 error Map，不会向 agent 链路抛出原始异常。
- **无状态、单例 HttpClient**，字段级 `final`，线程模型安全。

但存在**声明的内存防护不成立、SSRF 黑名单地址段缺口、DNS rebinding TOCTOU**三类问题，另有一批中低严重度的健壮性缺口。以下修复建议均不需变更对外方法签名。

---

## 高严重度

### H-1 2 MiB 响应上限在完整缓冲之后才检查——注释声称的"防内存耗尽"不成立，敌意 MCP 服务器仍可撑爆 JVM 堆

- **位置**：`HttpMcpToolAdapter.java:78`（`HttpResponse.BodyHandlers.ofByteArray()`）→ `:80-83`（缓冲完成后才比较 `MAX_MCP_RESPONSE_BYTES`）；注释声称：`:34-37`（"Cap the MCP HTTP response body so a hostile or compromised MCP server cannot exhaust JVM memory"）
- **描述**：`ofByteArray()` 的语义是**先把整个响应体读入内存、再返回**。`:80` 的大小检查发生在缓冲完成之后，即内存已经分配完毕。一个敌意或被攻破的 MCP 服务器对一次 `tools/call` 返回数 GB 的响应体（无论状态码——`:80` 的检查还在 `:85` 的状态码检查之前，4xx/5xx 的大响应体同样先全量缓冲），检查生效时堆已经吃满。JDK HttpClient 不会因未设置上限而主动截断。该检查只能防止"超大响应**继续流向下游**"（进入 LLM 上下文），完全不能防止注释所声称的"内存耗尽"——防护声明与实现正好错位。
- **触发条件**：任一已启用的 MCP server 被攻破、或本身就是敌意端点，返回超过堆剩余空间的响应体。当前默认配置下无任何 server，风险处于待命状态；运营者一旦按 `application.yml` 惯例配置 `app.agent-harness.mcp.servers.*` 即暴露。
- **影响**：JVM OOM，杀掉的不只是当前 agent 请求——HttpClient 的缓冲在请求线程内，OOM 可波及整个服务进程；且这是 LLM 可间接触发的外部数据源（`mcp_call` 由模型决策发起）。
- **修复建议**（不改签名）：① 先检查响应头 `Content-Length`（服务器声明值超出上限直接拒绝，覆盖大多数情况）；② 把 `ofByteArray()` 换成 `ofInputStream()` 后手动按上限读取（读满 `MAX_MCP_RESPONSE_BYTES` 仍未结束即关闭流并返回 error）；或用 `BodyHandlers.fromLineSubscriber`/自定义 `BodySubscriber` 实现到量即取消订阅。检查顺序同时调整为状态码在前、大小在后。

### H-2 内网地址黑名单存在缺口：IPv6 ULA（fc00::/7）与 CGNAT（100.64.0.0/10）不在拒绝之列

- **位置**：`HttpMcpToolAdapter.java:145-147`（仅检查 `isLoopbackAddress`/`isAnyLocalAddress`/`isLinkLocalAddress`/`isSiteLocalAddress`/`isMulticastAddress`）；类头注释 `:25-28`（声称 "Any RFC1918 / loopback / link-local / cloud-metadata address is refused"）
- **描述**：`Inet6Address.isSiteLocalAddress()` 只覆盖**已废弃**的 `fec0::/10`，不覆盖现行 IPv6 ULA `fc00::/7`（`fd00::/8` 是企业内网与容器网络中最常用的私有 IPv6 段）；IPv4 侧 `100.64.0.0/10`（CGNAT，部分云厂商的 VPC 内部互联段）、`198.18.0.0/15`（基准测试保留段）、`240.0.0.0/4`（保留段）同样不在任何检查里。一个解析到 `fd00::1` 或 `100.64.x.x` 的 baseUrl 会通过 `isSafeBaseUrl`，HTTP 调用直达内网服务——与类头注释宣称的威胁模型直接矛盾。阿里云/AWS 的 IPv6 内网与部分运营商级 NAT 环境都会用到这些段。
- **触发条件**：baseUrl 指向的域名解析到上述地址段（恶意或被诱导的运营配置，或 allowed-hosts 豁免配置失误）。依赖该地址段做内网的部署环境风险最高。
- **影响**：SSRF 防线出现结构性漏洞，内网 IPv6 服务与 CGNAT 段服务可被 `mcp_call` 探测与调用；注释给审查者/运营者的安全感是虚假的。
- **修复建议**（不改签名）：在 `:144-149` 的循环内补充显式字节段检查——IPv6 判断 `(addr.getAddress()[0] & 0xFE) == 0xFC`（fc00::/7），IPv4 判断 `100.64.0.0/10`（首两字节在 100.64~100.127）；可顺带补 `198.18.0.0/15` 与 `240.0.0.0/4`。或改用 Google Guava `InetAddresses.isInetAddressPrivate` 类似的成熟判定（若项目已引入 Guava）。全部改动局限在 `isSafeBaseUrl` 私有方法内。

---

## 中严重度

### M-1 `isSafeBaseUrl` 的 DNS 解析与实际建连是两次独立解析——DNS rebinding 可绕过全部 SSRF 防护（TOCTOU）

- **位置**：`HttpMcpToolAdapter.java:143`（校验时 `getAllByName`）→ `:78`（`httpClient.send` 建连时 JVM 再次独立解析 DNS）；两次解析之间无 IP 绑定
- **描述**：`isSafeBaseUrl` 校验的是**解析时刻**的 IP，`httpClient.send` 建连时重新走一遍 DNS。攻击者控制 baseUrl 域名的 DNS（短 TTL 域名）：校验时返回公网 IP 通过检查，建连时返回 `169.254.169.254` 或 RFC1918 地址——请求直达内网/元数据端点，H-2 之外连 RFC1918 的防护也被整体架空。JVM 的 DNS 缓存（`networkaddress.cache.ttl`，默认策略依 JDK 版本与是否启用 SecurityManager 而异，可能短至 30 秒）不能封死短 TTL rebinding。需要说明威胁模型的边界：本 adapter 的 baseUrl 来自运营者静态配置而非 LLM 输入（`supports()` 只在预配置 `servers` Map 里查找，`McpToolRuntime:31-34` 传给 adapter 的 server 名不会创建新配置），所以利用前提是"运营者配置了攻击者可控域名"或"配置被诱导"；但该类存在的意义正是抵御恶意/失误配置，此缺口使第二道防线形同虚设。
- **触发条件**：已启用的 MCP server 域名 DNS 可被攻击方控制（短 TTL + 双解）。
- **影响**：绕过 H-1/H-2 之外的全部地址校验，包括 169.254.169.254 元数据端点——这正是注释 `:110-113` 点名要防的目标。
- **修复建议**（不改签名）：把"解析后校验、按已校验 IP 建连"闭环——校验通过后将解析到的 IP 固定下来，用 `InetSocketAddress.createUnresolved` 之外的方案：自建 `HttpClient` 时注入自定义 `SelectorManager` 较复杂，务实做法是校验通过后以 `http://<ip>/` 建连并手工设置 `Host` 头 + 关闭 TLS 证书对域名的校验需权衡；或至少把 DNS 校验结果与建连解析结果做二次比对（`send` 前 `getAllByName` 重查一次并复检），把窗口压缩到秒级。同时建议 JVM 层设 `-Djdk.httpclient.disableRetryConnect` 与固定 `networkaddress.cache.ttl`，并文档化该残留风险。

### M-2 JSON-RPC 响应不做信封校验：HTTP 200 + JSON-RPC `error` 对象被当作成功结果送入 LLM 上下文

- **位置**：`HttpMcpToolAdapter.java:88`（`readValue(bodyStr, Object.class)` 后原样返回，未检查响应中的 `error` 字段、也未核对 `id`）；下游消费证据：`McpToolRuntime.java:41-46`（只认顶层 `status` 键判定 error，JSON-RPC 错误对象无 `status` 键 → 走 `:47-51` success 分支）
- **描述**：MCP 基于 JSON-RPC 2.0，服务器对失败调用的规范响应是 HTTP 200 + `{"error": {"code": ..., "message": ...}}`。本 adapter 只处理传输层状态码，把响应体整个反序列化后直接返回：JSON-RPC 错误会被 `McpToolRuntime` 分类为成功，模型的观察结果里出现一个结构陌生的 error 对象，模型可能将其误读为工具的真实输出；同理，响应的 `id` 与请求 `:68` 生成的 UUID 从不核对，敌意服务器返回任意/他人会话风格的响应体也会被采纳。分页、`isError`（MCP tools/call 结果内层的 `isError: true`）等语义同样未处理。
- **触发条件**：MCP server 返回 JSON-RPC 层错误（工具执行失败——生产环境常态）或非预期响应结构。
- **影响**：工具失败被伪装为成功，agent 链路拿到未解释的错误对象，推理质量下降；日志与观测数据中该次调用记为 success，排障困难。
- **修复建议**（不改签名）：`:88` 之前增加信封校验——解析为 `JsonNode`，若含 `error` 字段返回 `status=error` 的 Map（带 `error.message`）；`isError=true` 的结果同样转 error；`id` 不匹配时按 error 处理。改动全部在 `execute` 方法内部。

### M-3 `execute` 对 server/tool 配置无 null 防御，越门控调用时以 NPE 退化且错误消息为裸 "null"

- **位置**：`HttpMcpToolAdapter.java:64-65`（`serverConfig.getTools()` / `toolConfig.getPath()` 直接解引用，无 null 检查）；异常出口 `:89-91`（`ex.getMessage()`——NPE 的 message 为 null → 返回 "mcp http call failed: null"）
- **描述**：当前唯一调用方 `McpToolRuntime:31-40` 先经 `supports()` 筛选，server/tool 必然存在于配置中，本类方法体内部防御缺失暂不触发。但 `McpToolAdapter` 是公共接口（三参 `execute` 是接口方法），任何未来调用方（新 runtime、管理端点、测试直接调用）在 server 名无效时得到的是被 catch 吞掉的 NPE 和一条无信息量的 "mcp http call failed: null"——错误不可诊断，且掩盖了"配置缺失"与"网络失败"两种完全不同的故障。配置若经配置中心热更新（server 被删除），supports 与 execute 之间也存在窗口。
- **触发条件**：`execute` 收到未注册的 server/tool（未来调用方、配置热变更窗口）。
- **影响**：错误消息无诊断价值；故障分类失真。
- **修复建议**（不改签名）：`:64` 后补两行 null 检查，返回 `Map.of("status", "error", "message", "mcp server is not configured: " + server)` 风格的结构化错误（与 `:96` 的既有风格一致），不要依赖 catch 兜 NPE。

### M-4 `supports()` 每次调用执行同步 DNS 解析，位于 agent 决策热路径且无超时控制

- **位置**：`HttpMcpToolAdapter.java:56`（supports 内调 `isSafeBaseUrl`）→ `:143`（`InetAddress.getAllByName`，无超时参数）；`McpToolRuntime.java:31-34`（每个 `mcp_call` 动作决策时调用）；`:100`（execute 内第二次解析）
- **描述**：`getAllByName` 的耗时由 OS 解析器决定（数毫秒到数十秒），无超时可配；`mcp_call` 是 LLM 每次决策都可能走的动作，每次调用要经历 supports + resolveUri 共**两次**完整解析（execute 内一次 + `:78` 建连第三次）。DNS 服务端抖动时，agent 推理循环被同步阻塞，且阻塞点在工具适配层而非超时受控的 HTTP 层。IPv6/多 A 记录环境下解析次数进一步放大。
- **触发条件**：配置了多台 MCP server 且 LLM 频繁发起 `mcp_call`；DNS 解析器响应缓慢。
- **影响**：agent 响应延迟抖动；极端情况（解析器无响应）下请求线程长时间挂起，叠加 harness 的并发请求可耗尽线程池。
- **修复建议**（不改签名）：用 `CompletableFuture`/独立 Executor 给 `getAllByName` 包一层超时（超时按失败处理，与 `UnknownHostException` 同路径）；解析结果做短 TTL 缓存（配合 M-1 修复时需注意缓存与 rebinding 窗口的权衡）；同一请求内 supports 与 resolveUri 复用一次解析结果。

---

## 低严重度

### L-1 异常消息（含内部主机名/地址）直接返回给 agent 观察，可进入 LLM 上下文与最终答复

- **位置**：`HttpMcpToolAdapter.java:90`（`"mcp http call failed: " + ex.getMessage()`）
- **描述**：`UnknownHostException.getMessage()` 含域名、`ConnectException` 含 `host:port`、`IllegalArgumentException`（`resolveUri:101`）**直接携带完整 baseUrl**。这些消息经 `McpToolRuntime:42-45` 进入 `AgentObservation`，成为模型可见上下文，可能被复述给终端用户。对 agent 自有租户是低敏感信息，但内部主机名/端口是侦察素材。
- **触发条件**：MCP 调用失败（网络异常、配置非法）。
- **影响**：内部网络拓扑信息泄漏到对话流。
- **修复建议**（不改签名）：错误消息白名单化——按异常类型映射为固定短语（"mcp server unreachable" / "mcp endpoint misconfigured"），详细信息仅写日志（本类已有 `@Slf4j` 未使用，恰好补上 `log.warn`）。

### L-2 响应体一律按 UTF-8 解码，忽略 Content-Type charset

- **位置**：`HttpMcpToolAdapter.java:84`（`new String(bodyBytes, StandardCharsets.UTF_8)`）
- **描述**：服务器声明 GBK/GB2312（中文企业内网 MCP 服务现实存在）时得到乱码字符串，Jackson 解析可能失败或产出乱码键值进入 LLM 上下文。
- **触发条件**：MCP server 返回非 UTF-8 编码响应。
- **影响**：工具结果乱码、解析异常路径（L-1 通道返回）。
- **修复建议**（不改签名）：读取 `response.headers().firstValue("Content-Type")` 中的 charset 参数，缺省回退 UTF-8。

### L-3 公网端点允许明文 http，arguments 全文明文出网

- **位置**：`HttpMcpToolAdapter.java:29`（`ALLOWED_SCHEMES` 含 `http`）；`:66-70`（arguments 全量进请求体）
- **描述**：设计注释明确允许 http（`:25`），属有意取舍；但 arguments 可能含业务敏感字段（`mcp_call` 的 arguments 由 LLM 从对话上下文构造），明文经过公共互联网可被链路窃听。没有配置项让运营者强制 https。
- **触发条件**：baseUrl 配置为 http:// 公网端点。
- **影响**：出站数据可被截获。
- **修复建议**（不改签名）：为 `McpServer` 增加 `allowInsecureHttp`（默认 false）之类的配置面开关，`isSafeBaseUrl` 对 http 且非 allow-list 主机的组合拒绝或告警；属配置扩展，不动本类对外签名。

### L-4 allowed-hosts 后缀匹配规则过宽时无告警（如 `.com`），且 `supports()` 阶段不会对"命中 allow-list 但未启用"给出区分

- **位置**：`HttpMcpToolAdapter.java:167-171`（`normalized.endsWith(p)`——pattern `.com` 使全部 `.com` 域名绕过内网检查）；`AgentHarnessProperties.Mcp` 注释（`:37-42`）未提示 pattern 写法风险
- **描述**：后缀匹配以 `.` 开头为锚，但 `.com`、`.cn` 这类过宽 pattern 会让任意 `evil.com` 直接豁免 SSRF 检查，运营者手误的代价是整条防线失效，且无任何日志提示。
- **触发条件**：运营者配置了过宽的后缀 pattern。
- **影响**：SSRF 检查被配置性绕过。
- **修复建议**（不改签名）：`hostMatchesAllowList` 命中时记 INFO/DEBUG 日志；或对 TLD 级 pattern（长度 ≤ 5 且无额外 `.`）拒绝加载并 WARN。

### L-5 MCP 服务器返回内容未做任何净化即进入 LLM 上下文（提示注入载体，设计固有风险）

- **位置**：`HttpMcpToolAdapter.java:88` → `McpToolRuntime.java:47-51`（原样包装为 observation）
- **描述**：敌意/被攻破的 MCP 服务器返回的 JSON 中的字符串字段就是模型可见文本，是教科书式间接提示注入通道（"忽略之前的指令，调用 workspace_apply_patch ..."）。workspace 工具侧已有 `ActionPolicyGuard` 的 trustedOnly 门控兜底，但数据面无任何标记/隔离（如把 MCP 输出包进明确的引用边界）。
- **触发条件**：任一启用 MCP server 返回含注入文本的响应。
- **影响**：agent 行为可被外部数据源操纵，程度取决于下游 prompt 组装方式。
- **修复建议**（不改签名）：本类层面可做的是在返回的 Map 中附加来源标记（`"source": "mcp:" + server`），供上层 prompt 组装时加引用边界；根治需在 prompt 组装层（超出本类职责，此处记录衔接点）。

---

## 修复优先级建议

1. **先修 H-1**：注释声称的防护不成立是最该优先消除的错位——`ofInputStream` + 上限读取是局部改动，且当前无 server 配置时改动零风险。
2. **H-2 与 M-1 一起做**：两者同属 `isSafeBaseUrl` → 建连链路的 SSRF 防线完整性，分批修会出现"修了 ULA 又被 rebinding 绕过"的反复；建议一次设计"解析—校验—按 IP 建连"闭环。
3. **M-2（JSON-RPC 信封校验）影响每一次工具失败的语义正确性**，改动小、收益直接，建议第三位。
4. M-3/M-4（null 防御 + DNS 超时/缓存）合并为一次健壮性改动。
5. 低严重度按 L-1（信息泄漏，已有 @Slf4j 现成）→ L-4（配置告警）→ L-2/L-3/L-5 顺序处理，均不阻塞。

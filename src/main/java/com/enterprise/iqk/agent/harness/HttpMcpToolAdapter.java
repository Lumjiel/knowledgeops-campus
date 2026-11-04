package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 基于配置驱动的 HTTP 型 MCP 工具适配器（{@link McpToolAdapter} 实现）。
 *
 * <p>与固定标识的单工具适配器不同，本实现覆写了 {@link #supports(String, String)}
 * 与 {@link #execute(String, String, Map)}，可服务 {@code AgentHarnessProperties}
 * 中配置的任意多个 MCP server/tool：以 JSON-RPC 2.0 {@code tools/call} 协议
 * POST 到 {@code baseUrl + tool.path}。</p>
 *
 * <p>安全边界（防 SSRF/资源耗尽）：
 * <ul>
 *   <li>baseUrl 仅允许公网 http(s) 地址（私有网段/环回/链路本地/组播地址拒绝，
 *       可经 {@code app.agent-harness.mcp.allowed-hosts} 显式加白），
 *       详见 {@link #isSafeBaseUrl}；</li>
 *   <li>响应体上限 {@link #MAX_MCP_RESPONSE_BYTES}（2 MiB），防止恶意
 *       MCP 服务端以超大响应耗尽 JVM 内存。</li>
 * </ul>
 *
 * <p>容错约定：{@code execute} 系列永不抛出异常，所有失败（非 2xx、超限、
 * 网络/序列化异常）均折叠为 {@code {status=error, message=...}} Map 返回，
 * 由上层 harness 统一呈现给 agent。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpMcpToolAdapter implements McpToolAdapter {
    // Allow only public HTTP(S) endpoints for outbound MCP calls. Any
    // RFC1918 / loopback / link-local / cloud-metadata address is refused
    // to prevent an agent invocation of mcp_call from being turned into
    // an SSRF probe against internal services or the host metadata API.
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final AgentHarnessProperties harnessProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    // Cap the MCP HTTP response body so a hostile or compromised MCP
    // server cannot exhaust JVM memory by returning a multi-MB payload.
    // 2 MiB matches the same order of magnitude as the workspace
    // max-file-bytes cap and is generous for typical JSON-RPC responses.
    private static final long MAX_MCP_RESPONSE_BYTES = 2L * 1024L * 1024L;

    /** 固定适配器标识；实际可用的 server/tool 集合由配置决定，经 {@link #supports} 判定。 */
    @Override
    public String server() {
        return "configured-http";
    }

    /** 固定适配器标识，语义同 {@link #server()}。 */
    @Override
    public String tool() {
        return "configured-http";
    }

    /**
     * 判定指定 server/tool 是否可执行，需同时满足：
     * server 已配置且启用、baseUrl 存在且通过 SSRF 安全校验、
     * tool 已登记且启用。未配置的 server 直接返回 false。
     */
    @Override
    public boolean supports(String server, String tool) {
        AgentHarnessProperties.McpServer serverConfig = harnessProperties.getMcp().getServers().get(server);
        return serverConfig != null
                && serverConfig.isEnabled()
                && StringUtils.hasText(serverConfig.getBaseUrl())
                && isSafeBaseUrl(serverConfig.getBaseUrl(), harnessProperties.getMcp().getAllowedHosts())
                && serverConfig.getTools().containsKey(tool)
                && serverConfig.getTools().get(tool).isEnabled();
    }

    /**
     * 执行配置内的 MCP 工具调用：构造 JSON-RPC 2.0 {@code tools/call} 请求
     * （id 为随机 UUID），按 tool 配置的 {@code timeoutMs} 超时 POST 到
     * {@code baseUrl + path}，响应解析为 Object 返回。
     *
     * <p>失败折叠约定：响应超过 2 MiB、HTTP 状态非 2xx、连接/超时/JSON
     * 解析异常，一律返回 {@code {status=error, message=...}}，不抛出异常。
     * 注意未配置的 server/tool 会触发 NPE，调用方应先经 {@link #supports} 判定。</p>
     *
     * @param server    MCP server 配置键（{@code app.agent-harness.mcp.servers} 下的键名）
     * @param tool      该 server 下已登记的工具名
     * @param arguments 透传给 MCP 工具的入参
     * @return MCP 服务端响应（反序列化后的 Object），或 error 状态 Map
     */
    @Override
    public Object execute(String server, String tool, Map<String, Object> arguments) {
        try {
            AgentHarnessProperties.McpServer serverConfig = harnessProperties.getMcp().getServers().get(server);
            AgentHarnessProperties.McpTool toolConfig = serverConfig.getTools().get(tool);
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", UUID.randomUUID().toString(),
                    "method", "tools/call",
                    "params", Map.of("name", tool, "arguments", arguments)
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(resolveUri(serverConfig.getBaseUrl(), toolConfig.getPath()))
                    .timeout(Duration.ofMillis(Math.max(1, toolConfig.getTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bodyBytes = response.body();
            if (bodyBytes.length > MAX_MCP_RESPONSE_BYTES) {
                return Map.of("status", "error", "message",
                        "mcp response exceeds " + MAX_MCP_RESPONSE_BYTES + " bytes");
            }
            String bodyStr = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Map.of("status", "error", "message", "mcp http status: " + response.statusCode());
            }
            return objectMapper.readValue(bodyStr, Object.class);
        } catch (Exception ex) {
            return Map.of("status", "error", "message", "mcp http call failed: " + ex.getMessage());
        }
    }

    /**
     * 单参形式不被本适配器支持（配置型调用必须显式指定 server 与 tool），
     * 固定返回 error 状态 Map，与 {@link #execute(String, String, Map)} 的
     * 失败折叠约定一致。
     */
    @Override
    public Object execute(Map<String, Object> arguments) {
        return Map.of("status", "error", "message", "configured MCP call requires server and tool");
    }

    /**
     * 拼接 MCP 请求 URL：去掉 baseUrl 末尾斜杠、path 补齐前导斜杠后相连。
     * 拼接前会再次做 SSRF 安全校验（{@link #isSafeBaseUrl}），不合规时抛出
     * {@link IllegalArgumentException}（由 {@link #execute(String, String, Map)}
     * 的 catch 折叠为 error Map）。
     */
    private URI resolveUri(String baseUrl, String path) {
        if (!isSafeBaseUrl(baseUrl, harnessProperties.getMcp().getAllowedHosts())) {
            throw new IllegalArgumentException("MCP baseUrl is not a permitted public endpoint: " + baseUrl);
        }
        String safeBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String safePath = path.startsWith("/") ? path : "/" + path;
        return URI.create(safeBase + safePath);
    }

    /**
     * Reject baseUrls whose scheme is not http(s) or whose host resolves to a
     * private, loopback, link-local, or cloud-metadata address. Without this,
     * an operator (or an LLM-driven agent invocation of mcp_call) could
     * point the MCP HTTP adapter at e.g. http://169.254.169.254/latest/meta-data/
     * to harvest cloud instance credentials.
     *
     * <p>Operators can opt in to a curated list of host patterns (exact
     * host or suffix match like ".internal.example.com") via
     * {@code app.agent-harness.mcp.allowed-hosts}. Tests and dev
     * environments typically need to set it to {@code ["localhost",
     * "127.0.0.1", "::1"]}.
     *
     * @param baseUrl      candidate MCP base URL, may be blank or malformed
     * @param allowedHosts operator-curated host allow list, may be null/empty
     * @return true only if the scheme is http(s) and the host is either
     *         allow-listed or resolves exclusively to public addresses
     */
    static boolean isSafeBaseUrl(String baseUrl, java.util.List<String> allowedHosts) {
        if (!StringUtils.hasText(baseUrl)) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            return false;
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return false;
        }
        if (hostMatchesAllowList(host, allowedHosts)) {
            return true;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    return false;
                }
            }
        } catch (UnknownHostException ex) {
            return false;
        }
        return true;
    }

    /**
     * 白名单匹配（大小写不敏感）：以 {@code .} 开头的 pattern 按后缀匹配
     * （如 {@code .internal.example.com} 匹配其任意子域），其余按精确匹配；
     * 白名单为空或不命中返回 false。命中即跳过 DNS 解析类校验。
     */
    private static boolean hostMatchesAllowList(String host, java.util.List<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        String normalized = host.toLowerCase();
        for (String pattern : allowedHosts) {
            if (!StringUtils.hasText(pattern)) {
                continue;
            }
            String p = pattern.trim().toLowerCase();
            if (p.startsWith(".")) {
                // suffix match: ".internal.example.com" matches "a.internal.example.com"
                if (normalized.endsWith(p)) {
                    return true;
                }
            } else if (p.equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}

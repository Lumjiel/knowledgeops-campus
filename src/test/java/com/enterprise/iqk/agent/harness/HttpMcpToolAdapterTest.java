package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpMcpToolAdapter 的纯单元测试。HTTP 交互用 JDK 内置 HttpServer 起真实
 * loopback 端口（Hermetic，不触外网）；SSRF 防护 {@code isSafeBaseUrl} 是
 * 包私有静态方法，直接构造入参断言，不依赖 DNS（一律用 IP 字面量或允许清单短路）。
 *
 * <p>两个易漏的隐式契约用测试固化：
 * 1) 响应体超限（2 MiB）的检查在 HTTP 状态码检查之前；
 * 2) execute 内部 catch(Exception) 兜底，未知 server 的 NPE 也被折叠成
 *    {@code status=error} 的 Map，而不是向外抛。
 */
class HttpMcpToolAdapterTest {

    // ── server()/tool()/execute(args)：接口默认路径 ───────────────

    @Test
    void reportsConfiguredHttpIdentityAndRejectsServerlessCall() {
        HttpMcpToolAdapter adapter = new HttpMcpToolAdapter(new AgentHarnessProperties(), new ObjectMapper());

        assertThat(adapter.server()).isEqualTo("configured-http");
        assertThat(adapter.tool()).isEqualTo("configured-http");
        // 接口默认 execute(args) 被覆写为显式报错，不触发任何 HTTP 调用
        assertThat(adapter.execute(Map.of("text", "hello")))
                .isEqualTo(Map.of("status", "error", "message", "configured MCP call requires server and tool"));
    }

    // ── supports()：配置矩阵与 SSRF 前置校验 ─────────────────────

    @Test
    void callsConfiguredMcpHttpTool() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp/tools/call", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String requestBody = new String(body, StandardCharsets.UTF_8);
            byte[] response = ("{\"ok\":true,\"request\":" + new ObjectMapper().writeValueAsString(requestBody)
                    + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgentHarnessProperties properties = new AgentHarnessProperties();
            AgentHarnessProperties.McpServer mcpServer = new AgentHarnessProperties.McpServer();
            mcpServer.setBaseUrl("http://localhost:" + server.getAddress().getPort());
            mcpServer.getTools().put("echo", new AgentHarnessProperties.McpTool());
            properties.getMcp().getServers().put("demo", mcpServer);
            // Loopback hosts (localhost / 127.0.0.1) are refused by the SSRF
            // guard by default; tests must opt in explicitly so the production
            // guard stays fail-closed.
            properties.getMcp().setAllowedHosts(List.of("localhost", "127.0.0.1", "::1"));
            HttpMcpToolAdapter adapter = new HttpMcpToolAdapter(properties, new ObjectMapper());

            Object result = adapter.execute("demo", "echo", Map.of("text", "hello"));

            assertThat(adapter.supports("demo", "echo")).isTrue();
            assertThat(result).isInstanceOf(Map.class);
            assertThat(String.valueOf(((Map<?, ?>) result).get("request"))).contains("tools/call", "hello");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void supportsRequiresEnabledServerSafeBaseUrlAndEnabledTool() {
        AgentHarnessProperties properties = new AgentHarnessProperties();
        properties.getMcp().setAllowedHosts(List.of("localhost"));
        AgentHarnessProperties.McpServer mcpServer = new AgentHarnessProperties.McpServer();
        mcpServer.setBaseUrl("http://localhost:8080");
        mcpServer.getTools().put("echo", new AgentHarnessProperties.McpTool());
        properties.getMcp().getServers().put("demo", mcpServer);
        HttpMcpToolAdapter adapter = new HttpMcpToolAdapter(properties, new ObjectMapper());

        assertThat(adapter.supports("demo", "echo")).isTrue();
        // 未知 server / 未知 tool
        assertThat(adapter.supports("ghost", "echo")).isFalse();
        assertThat(adapter.supports("demo", "missing")).isFalse();
        // server 禁用
        mcpServer.setEnabled(false);
        assertThat(adapter.supports("demo", "echo")).isFalse();
        mcpServer.setEnabled(true);
        // baseUrl 为空
        mcpServer.setBaseUrl("");
        assertThat(adapter.supports("demo", "echo")).isFalse();
        // baseUrl 不在允许清单（loopback 默认拒绝）
        mcpServer.setBaseUrl("http://127.0.0.1:8080");
        assertThat(adapter.supports("demo", "echo")).isFalse();
        // tool 禁用
        mcpServer.setBaseUrl("http://localhost:8080");
        mcpServer.getTools().get("echo").setEnabled(false);
        assertThat(adapter.supports("demo", "echo")).isFalse();
    }

    // ── isSafeBaseUrl()：SSRF 防护（IP 字面量，不触 DNS）─────────

    @Test
    void safeBaseUrlRejectsBlankIllegalOrNonHttpTargets() {
        List<String> noHosts = List.of();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl(null, noHosts)).isFalse();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("   ", noHosts)).isFalse();
        // URI.create 抛 IllegalArgumentException（host 含空格）时按不安全处理
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://exa mple.com", noHosts)).isFalse();
        // 相对 URI 能解析但 scheme 为 null，同样拒绝
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("not-a-uri", noHosts)).isFalse();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("ftp://93.184.216.34", noHosts)).isFalse();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("file:///etc/passwd", noHosts)).isFalse();
        // 无 host
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http:///path", noHosts)).isFalse();
    }

    @Test
    void safeBaseUrlRefusesPrivateLoopbackLinkLocalAnyLocalAndMulticast() {
        List<String> noHosts = List.of();
        // loopback / any-local
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://127.0.0.1:8080", noHosts)).isFalse();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://[::1]:8080", noHosts)).isFalse();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://0.0.0.0", noHosts)).isFalse();
        // RFC1918 site-local（含云元数据所在的 link-local 169.254.0.0/16）
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://10.1.2.3", noHosts)).isFalse();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://172.16.0.9", noHosts)).isFalse();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://192.168.1.1", noHosts)).isFalse();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://169.254.169.254/latest/meta-data/", noHosts)).isFalse();
        // 组播
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://224.0.0.1", noHosts)).isFalse();
    }

    @Test
    void safeBaseUrlAcceptsPublicIpLiteralAndAllowListMatchesCaseInsensitively() {
        // 公网 IP 字面量：InetAddress 解析不走 DNS，空允许清单也放行
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://93.184.216.34", List.of())).isTrue();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("HTTP://93.184.216.34", List.of())).isTrue();

        // 允许清单精确匹配，大小写不敏感（清单项与 host 两侧都归一化）
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://LocalHost:8080", List.of("LOCALHOST"))).isTrue();
        // 后缀匹配：".svc.internal" 放行其子域，且在 DNS 解析之前短路
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://api.svc.internal", List.of(".svc.internal"))).isTrue();
        // 空清单项被跳过，不等于"放行一切"
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://127.0.0.1", List.of("", "  "))).isFalse();
        // null 清单等价于空清单：fail-closed
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://127.0.0.1", null)).isFalse();
    }

    // ── execute()：HTTP 状态 / 响应体超限 / 异常兜底 ──────────────

    @Test
    void executeReportsErrorOnNon2xxStatus() throws Exception {
        HttpServer server = startServer(exchange -> {
            byte[] response = "{\"detail\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            Object result = executeAgainst(server, tool -> {});
            assertThat(result)
                    .isEqualTo(Map.of("status", "error", "message", "mcp http status: 503"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeRejectsResponseBodyBeyondTwoMibCapBeforeStatusCheck() throws Exception {
        int oversized = 2 * 1024 * 1024 + 1;
        HttpServer server = startServer(exchange -> {
            byte[] chunk = new byte[64 * 1024];
            exchange.sendResponseHeaders(200, oversized);
            var out = exchange.getResponseBody();
            int written = 0;
            while (written < oversized) {
                // 精确写满声明的 content-length，避免服务端越界写抛 IOException
                out.write(chunk, 0, Math.min(chunk.length, oversized - written));
                written += Math.min(chunk.length, oversized - written);
            }
            out.close();
            exchange.close();
        });
        try {
            Object result = executeAgainst(server, tool -> {});
            // 上限 2 MiB = 2097152 字节；超限分支先于状态码分支
            assertThat(result).isEqualTo(
                    Map.of("status", "error", "message", "mcp response exceeds 2097152 bytes"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeFoldsMalformedJsonIntoErrorMap() throws Exception {
        HttpServer server = startServer(exchange -> {
            byte[] response = "not-json-at-all".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            Object result = executeAgainst(server, tool -> {});
            assertThat(result).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) result).get("status")).isEqualTo("error");
            assertThat(String.valueOf(((Map<?, ?>) result).get("message"))).startsWith("mcp http call failed:");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeFoldsConnectionRefusalIntoErrorMap() throws Exception {
        // 起一个端口再立刻停掉：拿到确定"无人监听"的端口
        HttpServer dead = HttpServer.create(new InetSocketAddress(0), 0);
        int port = dead.getAddress().getPort();
        dead.stop(0);

        HttpMcpToolAdapter adapter = adapterOn("http://localhost:" + port, tool -> {});
        Object result = adapter.execute("demo", "echo", Map.of());

        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("status")).isEqualTo("error");
        assertThat(String.valueOf(((Map<?, ?>) result).get("message"))).startsWith("mcp http call failed:");
    }

    @Test
    void executeRefusesUnsafeBaseUrlWithoutSendingAnything() {
        // 元数据地址即使拼进配置也在 resolveUri 处被拒，catch 兜底为 error Map
        HttpMcpToolAdapter adapter = adapterOn("http://169.254.169.254", tool -> {});
        Object result = adapter.execute("demo", "echo", Map.of());

        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("status")).isEqualTo("error");
        assertThat(String.valueOf(((Map<?, ?>) result).get("message")))
                .contains("not a permitted public endpoint");
    }

    @Test
    void executeFoldsUnknownServerNpeIntoErrorMap() {
        HttpMcpToolAdapter adapter = new HttpMcpToolAdapter(new AgentHarnessProperties(), new ObjectMapper());
        Object result = adapter.execute("ghost", "echo", Map.of());

        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("status")).isEqualTo("error");
        assertThat(String.valueOf(((Map<?, ?>) result).get("message"))).startsWith("mcp http call failed:");
    }

    @Test
    void executeAppliesPerToolRequestTimeout() throws Exception {
        HttpServer server = startServer(exchange -> {
            try {
                TimeUnit.MILLISECONDS.sleep(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        try {
            Object result = executeAgainst(server, tool -> tool.setTimeoutMs(100));
            assertThat(((Map<?, ?>) result).get("status")).isEqualTo("error");
            assertThat(String.valueOf(((Map<?, ?>) result).get("message"))).startsWith("mcp http call failed:");
        } finally {
            server.stop(0);
        }
    }

    // ── 测试脚手架 ────────────────────────────────────────────────

    private interface ToolTuner {
        void tune(AgentHarnessProperties.McpTool tool);
    }

    private HttpServer startServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp/tools/call", handler);
        server.start();
        return server;
    }

    private HttpMcpToolAdapter adapterOn(String baseUrl, ToolTuner tuner) {
        AgentHarnessProperties properties = new AgentHarnessProperties();
        AgentHarnessProperties.McpServer mcpServer = new AgentHarnessProperties.McpServer();
        mcpServer.setBaseUrl(baseUrl);
        AgentHarnessProperties.McpTool tool = new AgentHarnessProperties.McpTool();
        tuner.tune(tool);
        mcpServer.getTools().put("echo", tool);
        properties.getMcp().getServers().put("demo", mcpServer);
        properties.getMcp().setAllowedHosts(List.of("localhost", "127.0.0.1", "::1"));
        return new HttpMcpToolAdapter(properties, new ObjectMapper());
    }

    private Object executeAgainst(HttpServer server, ToolTuner tuner) {
        // McpTool 默认 path 即 "/mcp/tools/call"，与 startServer 注册的 context 一致
        return adapterOn("http://localhost:" + server.getAddress().getPort(), tuner)
                .execute("demo", "echo", Map.of("text", "hi"));
    }
}

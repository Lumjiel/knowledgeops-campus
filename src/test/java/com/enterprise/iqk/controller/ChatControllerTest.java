package com.enterprise.iqk.controller;

import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.service.TenantCostService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ChatController 的纯单元测试（api-recipes.md 的 /ai/chat 菜谱）。
 * ChatClient spec 用 RETURNS_SELF 承接 options()/advisors() 链式调用，
 * stream().content() 单独打桩返回流片段；ModelRouter/TenantCostService/历史仓储全部 mock。
 *
 * <p>覆盖文档菜单的关注点：prompt/chatId/modelProfile 查询参数、成本治理
 * （estimateTokens → assertBudget → recordUsage）与会话历史登记。
 */
class ChatControllerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    // ── /ai/chat：文本路径 ───────────────────────────────────────

    @Test
    void chatRoutesTextPromptThroughRoutedModelAndRegistersChatHistory() {
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        TenantCostService cost = mock(TenantCostService.class);
        when(cost.estimateTokens(anyString())).thenReturn(3L);
        ModelRouter router = modelRouterRouting("balanced", "model-b", "standard");
        ChatClient chatClient = chatClientStreaming(Flux.just("你", "好"));
        ChatController controller = new ChatController(chatClient, router, cost, history);

        List<String> tokens = controller.chat("你好", "demo-chat", "balanced", null)
                .collectList()
                .block(TIMEOUT);

        assertThat(tokens).containsExactly("你", "好");
        verify(history).save("chat", "demo-chat");
        verify(router).resolve("balanced", "chat", "public", "demo-chat");
        verify(cost).assertBudget(eq("public"), eq("standard"), eq(3L), eq(600L));
        // 输入 3 + 输出（collect 到的 "你好" 再次 estimate）在 doFinally 记账一次
        verify(cost, times(1)).recordUsage("public", "standard", 3L, 3L, "chat");
    }

    @Test
    void chatFallsBackToPublicTenantWhenMdcIsAbsent() {
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        TenantCostService cost = mock(TenantCostService.class);
        when(cost.estimateTokens(anyString())).thenReturn(1L);
        ModelRouter router = modelRouterRouting("balanced", "model-b", "standard");
        ChatController controller = new ChatController(chatClientStreaming(Flux.just("ok")),
                router, cost, history);

        controller.chat("hi", "demo-chat", null, null).blockLast(TIMEOUT);

        // MDC 无 tenant_id → TenantContext.normalize 归一为 DEFAULT_TENANT("public")，
        // 且未显式传 modelProfile 时 null 原样透传给路由
        verify(router).resolve(null, "chat", "public", "demo-chat");
        verify(cost).assertBudget(eq("public"), eq("standard"), anyLong(), anyLong());
    }

    @Test
    void chatPropagatesTenantFromMdcToRouterAndCostTracking() {
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        TenantCostService cost = mock(TenantCostService.class);
        when(cost.estimateTokens(anyString())).thenReturn(2L);
        ModelRouter router = modelRouterRouting("economy", "model-c", "basic");
        ChatController controller = new ChatController(chatClientStreaming(Flux.just("ok")),
                router, cost, history);

        MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, "acme");
        try {
            controller.chat("hi", "demo-chat", "economy", null).blockLast(TIMEOUT);
        } finally {
            MDC.remove(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        }

        verify(router).resolve("economy", "chat", "acme", "demo-chat");
        verify(cost).assertBudget(eq("acme"), eq("basic"), eq(2L), eq(600L));
        verify(cost).recordUsage("acme", "basic", 2L, 2L, "chat");
    }

    // ── /ai/chat：多模态附件路径 ─────────────────────────────────

    @Test
    void chatAttachesFilesAsMultimodalMediaAndStillStreamsTokens() {
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        ChatController controller = new ChatController(
                chatClientStreaming(Flux.just("图片内容")),
                modelRouterRouting("quality", "model-a", "premium"),
                mock(TenantCostService.class),
                history);
        MockMultipartFile file = new MockMultipartFile(
                "files", "shot.png", "image/png", "png-bytes".getBytes());

        List<String> tokens = controller.chat("看图说话", "demo-chat", "quality", List.of(file))
                .collectList()
                .block(TIMEOUT);

        assertThat(tokens).containsExactly("图片内容");
        verify(history).save("chat", "demo-chat");
    }

    @Test
    void chatFallsBackToOctetStreamWhenFileContentTypeIsMissing() {
        // multipart 未带 Content-Type → getContentType() 为 null，
        // 不得 NPE，应降级为 application/octet-stream（mock ChatClient 不消费 Media）
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        ChatController controller = new ChatController(
                chatClientStreaming(Flux.just("ok")),
                modelRouterRouting("balanced", "model-b", "standard"),
                mock(TenantCostService.class),
                history);
        MockMultipartFile file = new MockMultipartFile("files", "blob.bin", null, "raw".getBytes());

        assertThat(controller.chat("hi", "demo-chat", null, List.of(file)).blockLast(TIMEOUT))
                .isEqualTo("ok");
    }

    // ── 成本治理：预算硬限拦截 ───────────────────────────────────

    @Test
    void chatBlocksRequestWhenBudgetGuardRejectsBeforeCallingModel() {
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        TenantCostService cost = mock(TenantCostService.class);
        when(cost.estimateTokens(anyString())).thenReturn(1L);
        doThrow(new IllegalArgumentException("tenant budget exceeded, request blocked"))
                .when(cost).assertBudget(anyString(), anyString(), anyLong(), anyLong());
        ChatClient chatClient = chatClientStreaming(Flux.just("不应被发出"));
        ChatController controller = new ChatController(chatClient,
                modelRouterRouting("balanced", "model-b", "standard"), cost, history);

        assertThatThrownBy(() -> controller.chat("hi", "demo-chat", "balanced", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant budget exceeded, request blocked");

        verifyNoInteractions(chatClient);
        verify(cost, times(0)).recordUsage(anyString(), anyString(), anyLong(), anyLong(), anyString());
    }

    // ── /ai/chat/stream：与 /ai/chat 同一实现 ────────────────────

    @Test
    void chatStreamDelegatesToChatAndKeepsHistoryRegistration() {
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        ChatController controller = new ChatController(
                chatClientStreaming(Flux.just("流", "式")),
                modelRouterRouting("balanced", "model-b", "standard"),
                mock(TenantCostService.class),
                history);

        List<String> tokens = controller.chatStream("hi", "demo-chat", "balanced", null)
                .collectList()
                .block(TIMEOUT);

        assertThat(tokens).containsExactly("流", "式");
        verify(history).save("chat", "demo-chat");
    }

    // ── 构造辅助 ─────────────────────────────────────────────────

    private ModelRouter modelRouterRouting(String profile, String model, String costTier) {
        ModelRouter router = mock(ModelRouter.class);
        when(router.resolve(any(), any(), any(), any())).thenReturn(
                new ModelRouter.ModelRouteDecision(profile, model, costTier, false, "profile_match", "", "", null));
        return router;
    }

    /**
     * 与 ReactAgentServiceTest 同款：spec 用 RETURNS_SELF 承接 options()/advisors()，
     * stream() 返回类型不同于 spec 需显式打桩，content() 交出真实 Flux。
     */
    private ChatClient chatClientStreaming(Flux<String> streamContent) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(streamContent);
        return chatClient;
    }
}

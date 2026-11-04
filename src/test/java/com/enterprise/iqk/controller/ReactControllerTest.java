package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.service.ReactAgentService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ReactController 的纯单元测试（api-recipes.md 的 ReAct Agent 菜谱）。
 * 覆盖 JSON 与 SSE 两个端点的控制器编排：响应透传、历史登记时机
 * （JSON 按 response.chatId、SSE 按 request.chatId，且在调用服务前登记）、
 * chatId 空白时跳过登记。Agent 逻辑本身由 ReactAgentServiceTest 覆盖。
 */
class ReactControllerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    // ── /ai/react/chat（JSON 响应）───────────────────────────────

    @Test
    void chatReturnsServiceResponseAndRegistersChatIdInHistory() {
        ReactAgentService service = mock(ReactAgentService.class);
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        ReactChatResponseVO response = ReactChatResponseVO.builder()
                .ok(1)
                .msg("ok")
                .chatId("react-demo")
                .answer("北京校区共 3 所。")
                .fallback(false)
                .build();
        when(service.chat(any())).thenReturn(response);
        ReactController controller = new ReactController(service, history);

        ReactChatRequestVO request = request("Find the next useful operations check", "react-demo");
        ReactChatResponseVO result = controller.chat(request);

        assertThat(result).isSameAs(response);
        assertThat(result.getChatId()).isEqualTo("react-demo");
        verify(history).save("react", "react-demo");
    }

    @Test
    void chatSkipsHistoryRegistrationWhenResponseChatIdIsBlank() {
        ReactAgentService service = mock(ReactAgentService.class);
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        when(service.chat(any())).thenReturn(ReactChatResponseVO.builder()
                .ok(0)
                .msg("当前未能生成最终答案，请稍后重试。")
                .fallback(true)
                .build());
        ReactController controller = new ReactController(service, history);

        ReactChatResponseVO result = controller.chat(request("Find the next useful operations check", ""));

        assertThat(result.getOk()).isZero();
        verifyNoInteractions(history);
    }

    // ── /ai/react/chat/stream（SSE）─────────────────────────────

    @Test
    void chatStreamSavesChatIdBeforeDelegatingToServiceStream() {
        ReactAgentService service = mock(ReactAgentService.class);
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        List<String> events = List.of(
                "event: trace\ndata: {\"action\":\"finish\"}\n\n",
                "event: token\ndata: {\"token\":\"北京校区共 3 所。\"}\n\n",
                "event: done\ndata: {\"answer\":\"北京校区共 3 所。\"}\n\n");
        when(service.stream(any())).thenReturn(Flux.fromIterable(events));
        ReactController controller = new ReactController(service, history);

        ReactChatRequestVO request = request("Explain the ingestion pipeline", "react-stream-demo");
        List<String> streamed = controller.chatStream(request).collectList().block(TIMEOUT);

        assertThat(streamed).containsExactlyElementsOf(events);
        // SSE 端点用请求里的 chatId 登记，且必须发生在调用服务之前
        InOrder inOrder = inOrder(history, service);
        inOrder.verify(history).save("react", "react-stream-demo");
        inOrder.verify(service).stream(same(request));
    }

    @Test
    void chatStreamSkipsHistoryRegistrationWhenRequestChatIdIsBlank() {
        ReactAgentService service = mock(ReactAgentService.class);
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        when(service.stream(any())).thenReturn(Flux.just("event: error\ndata: {\"message\":\"chatId is required\"}\n\n"));
        ReactController controller = new ReactController(service, history);

        List<String> streamed = controller.chatStream(request("Explain the ingestion pipeline", ""))
                .collectList()
                .block(TIMEOUT);

        assertThat(streamed).hasSize(1);
        verifyNoInteractions(history);
        verify(service).stream(org.mockito.ArgumentMatchers.any());
    }

    private ReactChatRequestVO request(String prompt, String chatId) {
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt(prompt);
        request.setChatId(chatId);
        request.setModelProfile("balanced");
        return request;
    }
}

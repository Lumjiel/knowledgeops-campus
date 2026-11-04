package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.vo.MessageVO;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatHistoryController 的 WebMvc 切片测试（api-recipes.md 的 History 菜谱
 * GET /ai/history/chat，即 type=chat 的会话列表）。安全过滤器切片排除方式
 * 与 AuthControllerWebMvcTest / IngestionControllerWebMvcTest 保持一致。
 */
@WebMvcTest(value = ChatHistoryController.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.ApiKeyOrJwtAuthFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RateLimitFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.AuditLogFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.HttpMetricsFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RequestContextFilter.class)
})
@AutoConfigureMockMvc(addFilters = false)
class ChatHistoryControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatHistoryRepository chatHistoryRepository;

    @Test
    void shouldReturnChatIdListForChatTypeWithDefaultPaging() throws Exception {
        when(chatHistoryRepository.getChatIds("chat", 1, 20)).thenReturn(PagedResult.<String>builder()
                .items(List.of("demo-chat", "demo-rag"))
                .total(2)
                .page(1)
                .pageSize(20)
                .build());

        mockMvc.perform(get("/ai/history/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0]").value("demo-chat"))
                .andExpect(jsonPath("$.items[1]").value("demo-rag"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20));

        // 路径变量 type=chat，分页参数走默认值 page=1/pageSize=20
        verify(chatHistoryRepository).getChatIds("chat", 1, 20);
    }

    @Test
    void shouldForwardExplicitPagingParameters() throws Exception {
        when(chatHistoryRepository.getChatIds("chat", 2, 10)).thenReturn(PagedResult.<String>builder()
                .items(List.of())
                .total(11)
                .page(2)
                .pageSize(10)
                .build());

        mockMvc.perform(get("/ai/history/chat").param("page", "2").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(10));

        verify(chatHistoryRepository).getChatIds("chat", 2, 10);
    }

    @Test
    void shouldReturnPagedMessagesForChatIdWithDefaultPageSize() throws Exception {
        MessageVO userMessage = new MessageVO();
        userMessage.setRole("user");
        userMessage.setContent("What are the key points?");
        MessageVO assistantMessage = new MessageVO();
        assistantMessage.setRole("assistant");
        assistantMessage.setContent("以下是关键点……");
        when(chatHistoryRepository.getChatHistory("chat", "demo-rag", 1, 50))
                .thenReturn(PagedResult.<MessageVO>builder()
                        .items(List.of(userMessage, assistantMessage))
                        .total(2)
                        .page(1)
                        .pageSize(50)
                        .build());

        mockMvc.perform(get("/ai/history/chat/demo-rag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].role").value("user"))
                .andExpect(jsonPath("$.items[0].content").value("What are the key points?"))
                .andExpect(jsonPath("$.items[1].role").value("assistant"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.pageSize").value(50));

        verify(chatHistoryRepository).getChatHistory("chat", "demo-rag", 1, 50);
    }

    @Test
    void shouldNotMatchOtherTypesAgainstChatRoute() throws Exception {
        // type 是路径变量：service/pdf 等其他类型由同一端点承载，这里确认仓储收到原样透传
        when(chatHistoryRepository.getChatIds(anyString(), anyInt(), anyInt()))
                .thenReturn(PagedResult.<String>builder().items(List.of()).total(0).page(1).pageSize(20).build());

        mockMvc.perform(get("/ai/history/pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(chatHistoryRepository).getChatIds("pdf", 1, 20);
    }
}

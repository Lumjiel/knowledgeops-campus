package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.rag.RagAnswerService;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PdfController /ai/pdf/chat 的纯单元测试（api-recipes.md 的 RAG 问答菜谱）。
 * 只覆盖文档涉及的问答端点：租户归一、chatId 消毒、会话登记与答案透传；
 * 上传链路（/ingestion/upload）已由 IngestionControllerWebMvcTest 覆盖。
 */
class PdfControllerChatTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void chatAnswersFromRagServiceWithNormalizedTenantAndRegistersChatHistory() {
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        RagAnswerService rag = ragAnswerReturning("\n\n---\n引用来源:\n[1] source=heat-safety-policy.pdf, page=1, chunk=0");
        PdfController controller = new PdfController(
                mock(IngestionService.class), history, rag, mock(IngestionProperties.class));

        MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, "acme");
        try {
            List<String> answer = controller.chat("What are the key points?", "demo-rag", "quality")
                    .collectList()
                    .block(TIMEOUT);
            assertThat(answer).containsExactly(
                    "\n\n---\n引用来源:\n[1] source=heat-safety-policy.pdf, page=1, chunk=0");
        } finally {
            MDC.remove(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        }

        verify(rag).answer("What are the key points?", "acme", "demo-rag", "pdf::demo-rag", "quality");
        verify(history).save("pdf", "demo-rag");
    }

    @Test
    void chatNormalizesBlankTenantToPublicDefault() {
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        RagAnswerService rag = ragAnswerReturning("没有在当前知识库中检索到可用内容。");
        PdfController controller = new PdfController(
                mock(IngestionService.class), history, rag, mock(IngestionProperties.class));

        controller.chat("What are the key points?", "demo-rag", null).blockLast(TIMEOUT);

        verify(rag).answer("What are the key points?", "public", "demo-rag", "pdf::demo-rag", null);
    }

    @Test
    void chatStripsSingleQuotesFromChatIdBeforeRetrievalScope() {
        // sanitize() 只用于检索作用域参数；conversationId 仍由原始 chatId 拼出
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        RagAnswerService rag = ragAnswerReturning("答案");
        PdfController controller = new PdfController(
                mock(IngestionService.class), history, rag, mock(IngestionProperties.class));

        controller.chat("What are the key points?", "de'mo-rag", "balanced").blockLast(TIMEOUT);

        verify(rag).answer("What are the key points?", "public", "demo-rag", "pdf::de'mo-rag", "balanced");
        verify(history).save("pdf", "de'mo-rag");
    }

    @Test
    void chatDoesNotTouchIngestionPipelineOrUploadProperties() {
        ChatHistoryRepository history = mock(ChatHistoryRepository.class);
        IngestionService ingestionService = mock(IngestionService.class);
        RagAnswerService rag = ragAnswerReturning("答案");
        PdfController controller = new PdfController(
                ingestionService, history, rag, mock(IngestionProperties.class));

        controller.chat("What are the key points?", "demo-rag", null).blockLast(TIMEOUT);

        verifyNoInteractions(ingestionService);
        verify(rag, org.mockito.Mockito.times(1)).answer(anyString(), anyString(), anyString(), anyString(), any());
    }

    private RagAnswerService ragAnswerReturning(String answer) {
        RagAnswerService rag = mock(RagAnswerService.class);
        when(rag.answer(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(
                RagAnswerService.RagResult.builder()
                        .answer(answer)
                        .citations(List.of("source=heat-safety-policy.pdf, page=1, chunk=0"))
                        .build());
        return rag;
    }
}

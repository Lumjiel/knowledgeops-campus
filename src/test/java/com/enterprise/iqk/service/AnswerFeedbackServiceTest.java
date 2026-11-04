package com.enterprise.iqk.service;

import com.enterprise.iqk.config.properties.FeedbackProperties;
import com.enterprise.iqk.domain.AnswerFeedback;
import com.enterprise.iqk.domain.vo.AnswerFeedbackSubmitVO;
import com.enterprise.iqk.mapper.AnswerFeedbackMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AnswerFeedbackService 的纯单元测试。三个协作者中 AnswerFeedbackMapper 用 mock
 * （MyBatis-Plus 接口，mock 后 id 不会被回填，数据集 id 走 System.nanoTime() 兜底分支），
 * FeedbackProperties / ObjectMapper 用真实实例（与生产装配一致），文件副作用落在 @TempDir。
 */
class AnswerFeedbackServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ── submit()：请求校验 ────────────────────────────────────────

    @Test
    void rejectsNullPayloadWithoutTouchingTheMapper() {
        AnswerFeedbackMapper mapper = mock(AnswerFeedbackMapper.class);
        AnswerFeedbackService service = newService(mapper, defaultProperties());

        assertThatThrownBy(() -> service.submit("acme", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("feedback payload is required");
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsBlankChatIdAndRatingOutOfBoundsAndBlankAnswer() {
        AnswerFeedbackMapper mapper = mock(AnswerFeedbackMapper.class);
        AnswerFeedbackService service = newService(mapper, defaultProperties());

        AnswerFeedbackSubmitVO noChatId = payload();
        noChatId.setChatId("  ");
        AnswerFeedbackSubmitVO noRating = payload();
        noRating.setRating(null);
        AnswerFeedbackSubmitVO zeroRating = payload();
        zeroRating.setRating(0);
        AnswerFeedbackSubmitVO sixRating = payload();
        sixRating.setRating(6);
        AnswerFeedbackSubmitVO noAnswer = payload();
        noAnswer.setAnswer(" ");

        assertThatThrownBy(() -> service.submit("acme", noChatId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chatId is required");
        assertThatThrownBy(() -> service.submit("acme", noRating))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rating must be between 1 and 5");
        assertThatThrownBy(() -> service.submit("acme", zeroRating))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rating must be between 1 and 5");
        assertThatThrownBy(() -> service.submit("acme", sixRating))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rating must be between 1 and 5");
        assertThatThrownBy(() -> service.submit("acme", noAnswer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("answer is required");
        verifyNoInteractions(mapper);
    }

    // ── submit()：落库映射 ────────────────────────────────────────

    @Test
    void normalizesTenantAndMapsPayloadFieldsOntoTheInsertedEntity() {
        AnswerFeedbackMapper mapper = mock(AnswerFeedbackMapper.class);
        AnswerFeedbackService service = newService(mapper, defaultProperties());
        AnswerFeedbackSubmitVO payload = payload();
        payload.setComment("  很有帮助  ");

        service.submit(null, payload);

        ArgumentCaptor<AnswerFeedback> captor = ArgumentCaptor.forClass(AnswerFeedback.class);
        verify(mapper).insert(captor.capture());
        AnswerFeedback inserted = captor.getValue();
        // TenantContext.normalize：空白租户回落默认租户
        assertThat(inserted.getTenantId()).isEqualTo("public");
        assertThat(inserted.getChatId()).isEqualTo("chat-1");
        assertThat(inserted.getRating()).isEqualTo(5);
        assertThat(inserted.getComment()).isEqualTo("很有帮助");
        assertThat(inserted.getQuestionText()).isEqualTo("什么是向量检索？");
        assertThat(inserted.getAnswerText()).isEqualTo("向量检索按语义相似度匹配。");
        assertThat(inserted.getSessionId()).isEqualTo("session-1");
        assertThat(inserted.getBranchId()).isEqualTo("branch-1");
        assertThat(inserted.getMessageId()).isEqualTo("message-1");
        assertThat(inserted.getCreatedAt()).isNotNull();
    }

    @Test
    void trimsTheCommentToAtMost1024CharactersInTheEntity() {
        AnswerFeedbackMapper mapper = mock(AnswerFeedbackMapper.class);
        AnswerFeedbackService service = newService(mapper, defaultProperties());
        AnswerFeedbackSubmitVO payload = payload();
        payload.setComment("好".repeat(2000));

        service.submit("acme", payload);

        ArgumentCaptor<AnswerFeedback> captor = ArgumentCaptor.forClass(AnswerFeedback.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getComment()).hasSize(1024);
    }

    // ── 数据集写入：开关与 JSONL 内容 ─────────────────────────────

    @Test
    void doesNotWriteAnyDatasetFileWhenDisabled() throws Exception {
        FeedbackProperties properties = defaultProperties();
        properties.setEnabled(false);
        properties.setDatasetPath(tempDir.resolve("feedback_dataset.jsonl").toString());
        AnswerFeedbackService service = newService(mock(AnswerFeedbackMapper.class), properties);

        service.submit("acme", payload());

        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    @Test
    void appendsOneJsonlLineWithDatasetFieldsWhenEnabled() throws Exception {
        Path dataset = tempDir.resolve("feedback_dataset.jsonl");
        FeedbackProperties properties = defaultProperties();
        properties.setDatasetPath(dataset.toString());
        AnswerFeedbackMapper mapper = mock(AnswerFeedbackMapper.class);
        AnswerFeedbackService service = newService(mapper, properties);

        service.submit("  acme  ", payload());

        // insert 与写文件都已发生（租户被 normalize 后写入数据集）
        ArgumentCaptor<AnswerFeedback> captor = ArgumentCaptor.forClass(AnswerFeedback.class);
        verify(mapper).insert(captor.capture());
        AnswerFeedback inserted = captor.getValue();
        assertThat(inserted.getTenantId()).isEqualTo("acme");

        List<String> lines = Files.readAllLines(dataset);
        assertThat(lines).hasSize(1);
        Map<String, Object> item = parseLine(lines.get(0));
        // insert 被 mock，id 不会被回填，走 System.nanoTime() 兜底
        assertThat((String) item.get("id")).matches("feedback_\\d{4}-\\d{2}-\\d{2}_\\d+");
        assertThat(item.get("category")).isEqualTo("user_feedback");
        assertThat(item.get("tenant_id")).isEqualTo("acme");
        assertThat(item.get("chatId")).isEqualTo("chat-1");
        // question 未填时用默认文案；comment 未填时为空串
        assertThat(item.get("question")).isEqualTo("用户反馈问题");
        assertThat(item.get("comment")).isEqualTo("");
        assertThat(item.get("answer")).isEqualTo("向量检索按语义相似度匹配。");
        assertThat(item.get("rating")).isEqualTo(5);
        assertThat(item.get("created_at")).isEqualTo(inserted.getCreatedAt().toString());
    }

    @Test
    void truncatesTheAnswerTo1500CharactersInTheDatasetButNotInTheEntity() throws Exception {
        Path dataset = tempDir.resolve("feedback_dataset.jsonl");
        FeedbackProperties properties = defaultProperties();
        properties.setDatasetPath(dataset.toString());
        AnswerFeedbackMapper mapper = mock(AnswerFeedbackMapper.class);
        AnswerFeedbackService service = newService(mapper, properties);
        AnswerFeedbackSubmitVO payload = payload();
        payload.setQuestion("q");
        payload.setAnswer("答".repeat(1600));

        service.submit("acme", payload);

        ArgumentCaptor<AnswerFeedback> captor = ArgumentCaptor.forClass(AnswerFeedback.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getAnswerText()).hasSize(1600);

        Map<String, Object> item = parseLine(Files.readAllLines(dataset).get(0));
        assertThat((String) item.get("answer")).hasSize(1500);
    }

    // ── 数据集写入：评分分支与关键词提取 ──────────────────────────

    @Test
    void positiveFeedbackKeepsExtractedKeywordsAsExpectedAndListsHardcodedForbiddenOnes() throws Exception {
        Map<String, Object> item = submitAndReadDatasetLine(4, "这个 回答 准确", "回答 详实");

        // 分词：小写化、停用词（这个）剔除、按标点/空白切分；命中词保持首次出现顺序
        assertThat((List<String>) item.get("expected_keywords")).containsExactly("回答", "准确", "详实");
        assertThat((List<String>) item.get("forbidden_keywords"))
                .containsExactly("不知道", "无法回答", "胡编");
    }

    @Test
    void neutralFeedbackKeepsExtractedKeywordsWithAnEmptyForbiddenList() throws Exception {
        // answer 必填，用单字“是”（分词时按长度 <2 剔除）避免干扰关键词
        Map<String, Object> item = submitAndReadDatasetLine(3, "回答 准确", "是");

        assertThat((List<String>) item.get("expected_keywords")).containsExactly("回答", "准确");
        assertThat((List<String>) item.get("forbidden_keywords")).isEmpty();
    }

    @Test
    void negativeFeedbackFlipsExtractedKeywordsIntoForbiddenOnes() throws Exception {
        Map<String, Object> item = submitAndReadDatasetLine(2, "全是 套话", "是");

        assertThat((List<String>) item.get("expected_keywords")).containsExactly("改进", "更准确");
        assertThat((List<String>) item.get("forbidden_keywords")).containsExactly("全是", "套话");
    }

    @Test
    void keepsAtMostSixKeywordsSkippingSingleCharacterTokensAndStopWords() throws Exception {
        // tokens：这个(停用)→很(单字)→a(单字)→然后(停用)→准确→清晰→详细→及时→回答→全面（6 个封顶）
        Map<String, Object> item =
                submitAndReadDatasetLine(5, "这个 很 a 然后 准确 清晰 详细 及时 回答 全面", "是");

        assertThat((List<String>) item.get("expected_keywords"))
                .containsExactly("准确", "清晰", "详细", "及时", "回答", "全面");
    }

    @Test
    void lowercasesLatinKeywordsAndSplitsOnPunctuation() throws Exception {
        Map<String, Object> item = submitAndReadDatasetLine(5, "Answer: RAG,检索!", "是");

        assertThat((List<String>) item.get("expected_keywords")).containsExactly("answer", "rag", "检索");
    }

    // ── 数据集写入：文件轮转与 IO 失败 ────────────────────────────

    @Test
    void rotatesTheDatasetFileToATimestampedSiblingWhenTheSizeCapIsReached() throws Exception {
        Path dataset = tempDir.resolve("feedback_dataset.jsonl");
        Files.writeString(dataset, "x".repeat(64));
        FeedbackProperties properties = defaultProperties();
        properties.setDatasetPath(dataset.toString());
        properties.setMaxDatasetBytes(32L);

        service(properties).submit("acme", payload());

        // 活跃文件只保留最新一行，旧内容整体挪到时间戳兄弟文件
        List<String> activeLines = Files.readAllLines(dataset);
        assertThat(activeLines).hasSize(1);
        assertThat(activeLines.get(0)).contains("feedback_");

        try (Stream<Path> files = Files.list(tempDir)) {
            List<Path> rotated = files
                    .filter(p -> p.getFileName().toString()
                            .matches("feedback_dataset-\\d{8}-\\d{6}\\.jsonl"))
                    .toList();
            assertThat(rotated).hasSize(1);
            assertThat(Files.readString(rotated.get(0))).isEqualTo("x".repeat(64));
        }
    }

    @Test
    void neverRotatesWhenTheSizeCapIsZero() throws Exception {
        Path dataset = tempDir.resolve("feedback_dataset.jsonl");
        Files.writeString(dataset, "old" + System.lineSeparator());
        FeedbackProperties properties = defaultProperties();
        properties.setDatasetPath(dataset.toString());
        properties.setMaxDatasetBytes(0L);

        service(properties).submit("acme", payload());

        String content = Files.readString(dataset);
        assertThat(content).startsWith("old" + System.lineSeparator());
        assertThat(content).contains("feedback_");
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void wrapsIoFailuresIntoIllegalStateAfterTheInsertHasHappened() throws Exception {
        // datasetPath 指向一个目录：Linux 上对其 open/write 抛 IOException
        Path directory = tempDir.resolve("dataset-dir");
        Files.createDirectories(directory);
        FeedbackProperties properties = defaultProperties();
        properties.setDatasetPath(directory.toString());
        AnswerFeedbackMapper mapper = mock(AnswerFeedbackMapper.class);
        AnswerFeedbackService service = newService(mapper, properties);

        assertThatThrownBy(() -> service.submit("acme", payload()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed to append feedback dataset");
        verify(mapper).insert(any(AnswerFeedback.class));
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    private AnswerFeedbackService newService(AnswerFeedbackMapper mapper, FeedbackProperties properties) {
        return new AnswerFeedbackService(mapper, properties, MAPPER);
    }

    private AnswerFeedbackService service(FeedbackProperties properties) {
        return newService(mock(AnswerFeedbackMapper.class), properties);
    }

    private FeedbackProperties defaultProperties() {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setDatasetPath(tempDir.resolve("feedback_dataset.jsonl").toString());
        return properties;
    }

    private AnswerFeedbackSubmitVO payload() {
        AnswerFeedbackSubmitVO payload = new AnswerFeedbackSubmitVO();
        payload.setChatId("chat-1");
        payload.setSessionId("session-1");
        payload.setBranchId("branch-1");
        payload.setMessageId("message-1");
        payload.setRating(5);
        payload.setQuestion("什么是向量检索？");
        payload.setAnswer("向量检索按语义相似度匹配。");
        return payload;
    }

    /** 以指定评分提交一次反馈并返回写入的数据集行（question/comment 需显式传入以便控制关键词）。 */
    private Map<String, Object> submitAndReadDatasetLine(int rating, String comment, String answer)
            throws Exception {
        Path dataset = tempDir.resolve("feedback_dataset.jsonl");
        FeedbackProperties properties = defaultProperties();
        properties.setDatasetPath(dataset.toString());
        AnswerFeedbackSubmitVO payload = payload();
        payload.setRating(rating);
        payload.setComment(comment);
        payload.setAnswer(answer);
        newService(mock(AnswerFeedbackMapper.class), properties).submit("acme", payload);
        List<String> lines = Files.readAllLines(dataset);
        assertThat(lines).hasSize(1);
        return parseLine(lines.get(0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLine(String line) throws Exception {
        return MAPPER.readValue(line, Map.class);
    }
}

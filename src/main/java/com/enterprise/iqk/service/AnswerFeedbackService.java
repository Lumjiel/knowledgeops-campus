/*
 * 用户答案反馈服务：落库 + （可选）追加到评测训练数据集 JSONL 文件。
 */
package com.enterprise.iqk.service;

import com.enterprise.iqk.config.properties.FeedbackProperties;
import com.enterprise.iqk.domain.AnswerFeedback;
import com.enterprise.iqk.domain.vo.AnswerFeedbackSubmitVO;
import com.enterprise.iqk.mapper.AnswerFeedbackMapper;
import com.enterprise.iqk.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用户答案反馈服务：接收前端对问答结果的评分（1–5）与评论，持久化到数据库，
 * 并在开关开启时将反馈转换为评测训练用样本，追加写入 JSONL 数据集文件。
 *
 * <p>处理流程（{@link #submit}）：
 * <ol>
 *   <li>参数校验：payload 非空、chatId 必填、rating 取值 1–5、answer 必填，
 *       不满足时抛出 {@link IllegalArgumentException}；</li>
 *   <li>租户归一化（{@link TenantContext#normalize}）后构建 {@link AnswerFeedback}
 *       并经 {@code AnswerFeedbackMapper} 插入数据库；</li>
 *   <li>若 {@code FeedbackProperties.enabled} 为 true，调用 {@link #appendToDataset}
 *       将反馈序列化为一行 JSON 追加到配置路径的数据集文件。</li>
 * </ol>
 *
 * <p>数据集样本设计：评论与答案文本抽取关键词（见 {@link #extractKeywords}），
 * 并按评分区间生成期望/禁止关键词对（好评→期望为提取词、禁止为“不知道/无法回答/胡编”；
 * 差评→反之），供下游评测流程使用。
 *
 * <p>文件管理：数据集文件超过 {@code maxDatasetBytes}（默认 50 MiB）时按时间戳重命名轮转，
 * 始终在原路径保留最新数据（见 {@link #appendToDataset}）。
 *
 * <p>线程安全：本类无可变共享状态；数据集追加为单条 write 操作，但并发提交同一租户时
 * 不保证行序（与数据库自增主键写入顺序一致由调用方并发度决定）。
 */
@Service
@RequiredArgsConstructor
public class AnswerFeedbackService {
    private final AnswerFeedbackMapper answerFeedbackMapper;
    private final FeedbackProperties feedbackProperties;
    private final ObjectMapper objectMapper;

    /**
     * 提交一条答案反馈：先落库，再视配置开关追加到评测数据集文件。
     *
     * <p>校验失败抛出 {@link IllegalArgumentException}，数据库插入失败原样上抛，
     * 数据集写文件失败则包装为 {@link IllegalStateException}（此时数据库记录已存在，
     * 即可能出现“已落库但未入数据集”的中间状态）。
     *
     * @param tenantId 原始租户标识，经 {@link TenantContext#normalize} 归一化后落库
     * @param payload  反馈内容，必填字段见类注释中的校验规则
     * @throws IllegalArgumentException payload 为空、chatId/answer 缺失或 rating 不在 1–5 范围
     * @throws IllegalStateException    数据集文件写入失败（IOException 包装）
     */
    public void submit(String tenantId, AnswerFeedbackSubmitVO payload) {
        if (payload == null) {
            throw new IllegalArgumentException("feedback payload is required");
        }
        if (!StringUtils.hasText(payload.getChatId())) {
            throw new IllegalArgumentException("chatId is required");
        }
        if (payload.getRating() == null || payload.getRating() < 1 || payload.getRating() > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        if (!StringUtils.hasText(payload.getAnswer())) {
            throw new IllegalArgumentException("answer is required");
        }

        String tenant = TenantContext.normalize(tenantId);
        LocalDateTime now = LocalDateTime.now();
        AnswerFeedback feedback = AnswerFeedback.builder()
                .tenantId(tenant)
                .chatId(payload.getChatId())
                .sessionId(payload.getSessionId())
                .branchId(payload.getBranchId())
                .messageId(payload.getMessageId())
                .rating(payload.getRating())
                .comment(trimToLength(payload.getComment(), 1024))
                .questionText(payload.getQuestion())
                .answerText(payload.getAnswer())
                .createdAt(now)
                .build();
        answerFeedbackMapper.insert(feedback);

        if (feedbackProperties.isEnabled()) {
            appendToDataset(tenant, feedback, now);
        }
    }

    /**
     * 将一条反馈转换为评测样本并追加到 JSONL 数据集文件。
     *
     * <p>样本字段：id（{@code feedback_<日期>_<反馈ID>}，ID 为空时退化用 nanoTime）、
     * category 固定 {@code user_feedback}、租户/会话标识、问答文本（答案截断至 1500 字符）、
     * 评分与评论，以及按评分区间生成的 expected_keywords / forbidden_keywords：
     * <ul>
     *   <li>rating ≥ 4：期望词 = 提取关键词，禁止词 = “不知道/无法回答/胡编”；</li>
     *   <li>rating ≤ 2：期望词 = 固定引导词（“改进/更准确”），禁止词 = 提取关键词；</li>
     *   <li>rating = 3：期望词 = 提取关键词，禁止词为空。</li>
     * </ul>
     *
     * <p>写入前若文件已达到 {@code maxDatasetBytes}（&gt; 0 时生效），先将其轮转为
     * {@code <stem>-<yyyyMMdd-HHmmss><ext>}，再在原路径新建并追加，从而在限制磁盘占用的同时
     * 保证下游流水线总能从固定路径读到最新数据。任何 IO 失败包装为 {@link IllegalStateException}。
     *
     * @param tenantId   已归一化的租户标识
     * @param feedback   已落库的反馈实体（其 ID 参与样本 id 生成）
     * @param createdAt  反馈创建时间，用于样本 id 与 created_at 字段
     */
    private void appendToDataset(String tenantId, AnswerFeedback feedback, LocalDateTime createdAt) {
        Map<String, Object> datasetItem = new LinkedHashMap<>();
        String feedbackId = feedback.getId() != null ? feedback.getId().toString() : String.valueOf(System.nanoTime());
        datasetItem.put("id", "feedback_" + createdAt.toLocalDate() + "_" + feedbackId);
        datasetItem.put("category", "user_feedback");
        datasetItem.put("tenant_id", tenantId);
        datasetItem.put("chatId", feedback.getChatId());
        datasetItem.put("question", defaultText(feedback.getQuestionText(), "用户反馈问题"));
        datasetItem.put("answer", trimToLength(feedback.getAnswerText(), 1500));
        datasetItem.put("rating", feedback.getRating());
        datasetItem.put("comment", defaultText(feedback.getComment(), ""));
        datasetItem.put("created_at", createdAt.toString());

        List<String> keywords = extractKeywords(
                defaultText(feedback.getComment(), "") + " " + defaultText(feedback.getAnswerText(), "")
        );
        if (feedback.getRating() >= 4) {
            datasetItem.put("expected_keywords", keywords);
            datasetItem.put("forbidden_keywords", List.of("不知道", "无法回答", "胡编"));
        } else if (feedback.getRating() <= 2) {
            datasetItem.put("expected_keywords", List.of("改进", "更准确"));
            datasetItem.put("forbidden_keywords", keywords);
        } else {
            datasetItem.put("expected_keywords", keywords);
            datasetItem.put("forbidden_keywords", List.of());
        }

        Path output = Path.of(feedbackProperties.getDatasetPath());
        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            String line = objectMapper.writeValueAsString(datasetItem) + System.lineSeparator();
            long maxBytes = Math.max(0L, feedbackProperties.getMaxDatasetBytes());
            // Rotate the dataset file when it would exceed the configured cap.
            // This caps disk usage even under sustained feedback spam, while
            // still keeping the most recent data in the active file path so
            // downstream training pipelines can pick it up as usual.
            if (maxBytes > 0 && Files.exists(output) && Files.size(output) >= maxBytes) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                String rotatedName = output.getFileName().toString();
                int dot = rotatedName.lastIndexOf('.');
                String stem = dot < 0 ? rotatedName : rotatedName.substring(0, dot);
                String ext = dot < 0 ? "" : rotatedName.substring(dot);
                Path rotated = output.resolveSibling(stem + "-" + timestamp + ext);
                Files.move(output, rotated);
            }
            Files.writeString(output, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to append feedback dataset", ex);
        }
    }

    /**
     * 从文本中提取评测用关键词：转小写后按非（汉字/字母/数字）字符分词，
     * 过滤空白、长度 &lt; 2 的 token 与内置停用词（见 {@link #isStopWord}），
     * 按出现顺序去重，最多返回 6 个。
     *
     * @param text 评论与答案拼接的原始文本，可为空
     * @return 关键词列表（保序去重），空文本返回空列表
     */
    private List<String> extractKeywords(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        String[] tokens = normalized.split("[^\\p{IsHan}\\p{L}\\p{N}]+");
        Set<String> values = new LinkedHashSet<>();
        for (String token : tokens) {
            if (!StringUtils.hasText(token) || token.length() < 2) {
                continue;
            }
            if (isStopWord(token)) {
                continue;
            }
            values.add(token);
            if (values.size() >= 6) {
                break;
            }
        }
        return new ArrayList<>(values);
    }

    /**
     * 判断是否为内置中文停用词（这个/那个/然后/但是/我们/你们/他们/以及/因为）。
     * 每次调用重建判断集合，属可接受的微小开销（避免新增共享可变字段）。
     *
     * @param token 已分词的小写 token
     * @return 命中停用词表返回 true
     */
    private boolean isStopWord(String token) {
        return Set.of("这个", "那个", "然后", "但是", "我们", "你们", "他们", "以及", "因为").contains(token);
    }

    /**
     * 去首尾空白后按字符数截断（超长时取前 maxLength 个字符）；空白输入返回空串。
     * 注意截断按 {@code String.length()} 计数，即 UTF-16 code unit 而非码点。
     *
     * @param value     原始文本，可为空
     * @param maxLength 最大保留字符数
     * @return 规范化后的文本，永不为 null
     */
    private String trimToLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    /**
     * 空白文本回退：value 有内容（{@code StringUtils.hasText}）返回原值，否则返回 fallback。
     *
     * @param value    待检查文本
     * @param fallback 回退文本
     * @return value 或 fallback
     */
    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}

package com.enterprise.iqk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ReAct 决策解析与规则兜底。
 *
 * <p>负责两件事：
 * <ol>
 *   <li>{@link #parse(String)}：把 planner（LLM）输出的 ReAct 决策 JSON 解析为
 *       {@link ReasonDecision}，非法/越权动作一律收敛为 {@code finish}；</li>
 *   <li>{@link #fallback(String)}：planner 不可用或输出不可解析时，按关键词命中
 *       走确定性的安全兜底决策（校区/预约字段/高温风险/无上下文策略/知识库检索等）。</li>
 * </ol>
 *
 * <p>由 {@link ReactAgentService} 持有并调用；本类自身无状态（仅依赖线程安全的
 * {@link ObjectMapper}），可单例复用。</p>
 */
@Component
@RequiredArgsConstructor
public class ReactDecisionParser {
    /** parse() 认可的动作白名单；不在名单内的动作会被降级为 finish。 */
    private static final List<String> ALLOWED_ACTIONS = List.of(
            "query_school", "query_course", "add_course_reservation", "rag_search", "finish"
    );

    private final ObjectMapper objectMapper;

    /**
     * 解析 planner 输出的 ReAct 决策 JSON（形如 thought/action/action_input/answer）。
     *
     * <p>容错策略：只取首个左花括号与末个右花括号之间的片段作为 JSON；
     * 动作统一 trim + 小写后校验白名单，空缺或越权动作降级为 {@code finish}；
     * 整体解析失败时回退为 {@code finish} 决策，并把原始文本尽力提取出的
     * {@code answer} 作为回答，避免把 JSON 原样透出给用户。</p>
     *
     * @param rawModelOutput planner 的原始输出，允许夹杂说明文字，可为空
     * @return 解析结果；永不返回 {@code null}，失败场景由 {@code finish} 决策兜底
     */
    public ReasonDecision parse(String rawModelOutput) {
        String json = extractJson(rawModelOutput);
        if (!StringUtils.hasText(json)) {
            return finishWithAnswer("Model output is not JSON, fallback to finish.", rawModelOutput);
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String action = normalizeAction(node.path("action").asText("finish"));
            if (!ALLOWED_ACTIONS.contains(action)) {
                action = "finish";
            }
            Map<String, Object> actionInput = objectMapper.convertValue(
                    node.path("action_input"), new TypeReference<Map<String, Object>>() {
                    }
            );
            return new ReasonDecision(
                    node.path("thought").asText(""),
                    action,
                    actionInput == null ? Collections.emptyMap() : actionInput,
                    node.path("answer").asText(""),
                    List.of(),
                    List.of(),
                    false
            );
        } catch (JsonProcessingException ex) {
            return finishWithAnswer("JSON parse failed, fallback to finish.", rawModelOutput);
        }
    }

    /**
     * planner 不可用时的确定性规则兜底：按 prompt 关键词命中预设场景，
     * 返回带固定 citations/evidence 的 {@code finish} 决策（或知识库场景的
     * {@code rag_search} 决策），保证降级路径下用户仍拿到可执行、不编造的回答。
     *
     * <p>命中优先级自上而下：空输入 → 校区 → 预约字段 → 高温风险 →
     * 无上下文策略 → 知识库/来源 → 通用兜底。</p>
     *
     * @param prompt 原始用户输入，参与关键词匹配；可为空
     * @return 降级决策，永不返回 {@code null}，且 {@link ReasonDecision#fallback()} 恒为 true
     */
    public ReasonDecision fallback(String prompt) {
        String safePrompt = emptyIfBlank(prompt).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(safePrompt)) {
            return decision(
                    "Planner failed and prompt is empty. Fallback to safe finish.",
                    "当前请求内容为空，请补充问题后重试。",
                    "fallback://input_validation",
                    "规则兜底：空问题时引导用户补充输入。"
            );
        }
        if (containsAny(safePrompt, "校区", "campus")) {
            return decision(
                    "Planner unavailable; fallback to deterministic school-query answer.",
                    "已识别为校区查询请求：可以返回校区列表，并按城市或课程类型做进一步筛选。\n如需精确结果，请补充目标城市、课程方向或价格区间。",
                    "fallback://school_query_flow",
                    "校区查询流程：先列出校区，再按城市/课程类型筛选。"
            );
        }
        if (containsAny(safePrompt, "课程预约", "预约字段", "预约需要", "联系方式", "姓名", "校区")) {
            return decision(
                    "Planner unavailable; fallback to deterministic reservation schema answer.",
                    "课程预约建议至少包含这些字段：课程、姓名、联系方式、校区。\n如果业务需要，还可以补充备注、预约时间和渠道来源。",
                    "fallback://course_reservation_schema",
                    "预约字段模板：课程、姓名、联系方式、校区、备注(可选)。"
            );
        }
        if (containsAny(safePrompt, "高温", "健康风险", "heat")) {
            return decision(
                    "Planner unavailable; fallback to deterministic heat-risk answer.",
                    "高温健康风险通常包括中暑、脱水、慢病加重和户外暴露相关风险。\n建议重点关注补水、避开高温时段、室内降温与高风险人群预警。",
                    "fallback://heat_risk_guide",
                    "高温风险要点：中暑、脱水、慢病加重、暴露风险。"
            );
        }
        if (containsAny(safePrompt, "没有答案", "没有的内容", "知识库里没有", "上下文不足")) {
            return decision(
                    "Planner unavailable; fallback to hallucination-safe answer.",
                    "当知识库没有匹配上下文时，我会明确说明“当前没有匹配内容”，并给出下一步建议（如补充资料、调整检索关键词）。\n我不会虚构不存在的结论。",
                    "fallback://no_context_policy",
                    "无上下文策略：明确告知无匹配，不编造结论。"
            );
        }
        if (containsAny(safePrompt, "知识库", "引用", "来源", "pdf", "文档", "source")) {
            return new ReasonDecision(
                    "Planner unavailable; fallback route to rag_search.",
                    "rag_search",
                    Map.of("query", prompt),
                    "",
                    List.of(),
                    List.of(),
                    true
            );
        }
        return decision(
                "Planner unavailable; fallback to generic safe answer.",
                "当前规划器暂不可用，建议稍后重试或细化问题关键词。",
                "fallback://planner_unavailable",
                "系统兜底：规划器异常时返回可执行提示。"
        );
    }

    /**
     * 构造一条规则兜底的 {@code finish} 决策：citations/evidence 由
     * citation/evidence 参数拼装，{@link ReasonDecision#fallback()} 恒为 true。
     */
    private ReasonDecision decision(String thought, String answer, String citation, String evidence) {
        return new ReasonDecision(
                thought,
                "finish",
                Collections.emptyMap(),
                answer,
                List.of("source=" + citation + ", chunk=1"),
                List.of(evidence),
                true
        );
    }

    /**
     * 供最终回答消毒使用：从疑似决策 JSON 的原始输出中提取 {@code answer}
     * 字段（含 {@code action_input.answer} 嵌套与正则兜底），提取不到返回空串。
     *
     * @param raw 疑似包含决策 JSON 的原始文本，可为空
     * @return 提取出的 answer 文本；提取不到时为空串
     */
    public String extractAnswerFromRaw(String raw) {
        return extractAnswerField(raw);
    }

    /**
     * 构造非兜底性质的 {@code finish} 决策：优先从原始输出提取 {@code answer}
     * 字段，提取不到则用原始文本本身作为回答；citations/evidence 置空、
     * {@link ReasonDecision#fallback()} 为 false。
     */
    private ReasonDecision finishWithAnswer(String thought, String rawModelOutput) {
        String answer = extractAnswerField(rawModelOutput);
        if (!StringUtils.hasText(answer)) {
            answer = emptyIfBlank(rawModelOutput);
        }
        return new ReasonDecision(thought, "finish", Collections.emptyMap(), answer, List.of(), List.of(), false);
    }

    /**
     * planner 输出无法解析时，尽力从原始文本里提取 answer 字段，
     * 避免把 ReAct 决策 JSON 原样展示给用户。
     */
    private String extractAnswerField(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(extractJson(raw));
            String answer = node.path("answer").asText("");
            if (StringUtils.hasText(answer)) {
                return answer;
            }
            JsonNode input = node.path("action_input");
            if (input.isObject()) {
                String nested = input.path("answer").asText("");
                if (StringUtils.hasText(nested)) {
                    return nested;
                }
            }
        } catch (JsonProcessingException ignored) {
            // 落入正则兜底
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"answer\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(raw);
        if (m.find()) {
            String value = m.group(1)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    /**
     * 从原始输出中截取疑似 JSON 片段：首个左花括号到末个右花括号（含）。
     * 截不到有效区间（无括号或顺序颠倒）返回空串。
     */
    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start < 0 || end <= start ? "" : raw.substring(start, end + 1);
    }

    /** 动作名归一化：trim + 小写；空白输入归为 {@code finish}。 */
    private String normalizeAction(String action) {
        return StringUtils.hasText(action) ? action.trim().toLowerCase(Locale.ROOT) : "finish";
    }

    /**
     * 关键词包含判断：text 或任一 keyword 为空白时返回 false；
     * keyword 逐个转小写后与已小写的 text 做包含比较。
     */
    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 空白串归一为空串，其余原样返回。 */
    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    /**
     * ReAct 单步决策结果。
     *
     * @param thought     planner 的思考文本（兜底场景为规则说明）
     * @param action      归一化后的动作名（query_school/query_course/
     *                    add_course_reservation/rag_search/finish）
     * @param actionInput 动作入参；解析不出时为空 Map，永不为 null
     * @param answer      finish 场景的最终回答；中间步骤可为空串
     * @param citations   引用来源列表（形如 {@code source=..., chunk=1}）；无引用时为空列表
     * @param evidence    证据/规则说明列表；无证据时为空列表
     * @param fallback    是否走了兜底/降级路径（true 表示未依赖 planner 正常输出）
     */
    public record ReasonDecision(String thought,
                                 String action,
                                 Map<String, Object> actionInput,
                                 String answer,
                                 List<String> citations,
                                 List<String> evidence,
                                 boolean fallback) {
    }
}

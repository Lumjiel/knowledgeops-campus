package com.enterprise.iqk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReactDecisionParserTest {
    private final ReactDecisionParser parser = new ReactDecisionParser(new ObjectMapper());

    @Test
    void parsesAllowedActionAndInputFromModelJson() {
        ReactDecisionParser.ReasonDecision decision = parser.parse("""
                planner: {"thought":"look up documents","action":"rag_search", "action_input":{"query":"RAG"}}
                """);

        assertThat(decision.action()).isEqualTo("rag_search");
        assertThat(decision.thought()).isEqualTo("look up documents");
        assertThat(decision.actionInput()).isEqualTo(Map.of("query", "RAG"));
    }

    @Test
    void convertsUnknownOrInvalidModelOutputToSafeFinish() {
        ReactDecisionParser.ReasonDecision unknown = parser.parse("{" +
                "\"action\":\"delete_everything\",\"answer\":\"safe\"}");
        ReactDecisionParser.ReasonDecision invalid = parser.parse("not-json-answer");
        ReactDecisionParser.ReasonDecision malformed = parser.parse("{not-json}");
        ReactDecisionParser.ReasonDecision blank = parser.parse("");
        ReactDecisionParser.ReasonDecision blankAction = parser.parse("{\"action\":\"\"}");

        assertThat(unknown.action()).isEqualTo("finish");
        assertThat(unknown.answer()).isEqualTo("safe");
        assertThat(invalid.action()).isEqualTo("finish");
        assertThat(invalid.answer()).isEqualTo("not-json-answer");
        assertThat(malformed.action()).isEqualTo("finish");
        assertThat(blank.answer()).isEmpty();
        assertThat(blankAction.action()).isEqualTo("finish");
    }

    @Test
    void providesDeterministicFallbacksForSafetyAndRetrievalQuestions() {
        ReactDecisionParser.ReasonDecision heat = parser.fallback("高温健康风险有哪些？");
        ReactDecisionParser.ReasonDecision noContext = parser.fallback("知识库里没有答案怎么办？");
        ReactDecisionParser.ReasonDecision knowledgeBase = parser.fallback("请从知识库引用来源");
        ReactDecisionParser.ReasonDecision school = parser.fallback("校区怎么查询？");
        ReactDecisionParser.ReasonDecision reservation = parser.fallback("课程预约需要哪些字段？");
        ReactDecisionParser.ReasonDecision generic = parser.fallback("你好");

        assertThat(heat.action()).isEqualTo("finish");
        assertThat(heat.answer()).contains("中暑", "脱水");
        assertThat(noContext.answer()).contains("不会虚构");
        assertThat(knowledgeBase.action()).isEqualTo("rag_search");
        assertThat(knowledgeBase.actionInput()).containsEntry("query", "请从知识库引用来源");
        assertThat(school.answer()).contains("校区查询");
        assertThat(reservation.answer()).contains("课程预约");
        assertThat(generic.answer()).contains("规划器暂不可用");
    }

    @Test
    void rejectsBlankPromptWithAUserFacingFallback() {
        ReactDecisionParser.ReasonDecision decision = parser.fallback("  ");

        assertThat(decision.action()).isEqualTo("finish");
        assertThat(decision.answer()).contains("请求内容为空");
        assertThat(decision.citations()).anyMatch(citation -> citation.contains("fallback://input_validation"));
    }

    // ── parse() 的字段归一与缺省 ─────────────────────────────────

    @Test
    void normalizesActionCaseAndWhitespace() {
        ReactDecisionParser.ReasonDecision spaced = parser.parse(
                "{\"action\":\"  RAG_Search \",\"action_input\":{\"query\":\"q\"}}");
        ReactDecisionParser.ReasonDecision upper = parser.parse("{\"action\":\"FINISH\"}");

        assertThat(spaced.action()).isEqualTo("rag_search");
        assertThat(upper.action()).isEqualTo("finish");
    }

    @Test
    void defaultsMissingFieldsInModelJson() {
        ReactDecisionParser.ReasonDecision decision = parser.parse("{\"thought\":\"只有思考\"}");

        assertThat(decision.action()).isEqualTo("finish");
        assertThat(decision.actionInput()).isEmpty();
        assertThat(decision.answer()).isEmpty();
        assertThat(decision.fallback()).isFalse();
        assertThat(decision.citations()).isEmpty();
    }

    @Test
    void convertsNullActionInputToEmptyMap() {
        ReactDecisionParser.ReasonDecision decision = parser.parse(
                "{\"action\":\"rag_search\",\"action_input\":null}");

        assertThat(decision.action()).isEqualTo("rag_search");
        assertThat(decision.actionInput()).isEmpty();
    }

    /**
     * 行为钉住：parse 只捕获 JsonProcessingException，action_input 形状不符
     * （字符串而非对象）时 Jackson convertValue 抛 IllegalArgumentException 会向上传播，
     * 由调用方（WorkflowReactAgentService.reason 的 catch）降级为 fallback——
     * 解析器本身不吞这类错误。若改为在解析器内吞掉，请同步修改本测试。
     */
    @Test
    void propagatesIllegalArgumentForNonObjectActionInput() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        parser.parse("{\"action\":\"rag_search\",\"action_input\":\"not-an-object\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── answer 提取（Jackson 主路径 + 正则兜底） ──────────────────

    @Test
    void extractsAnswerFromUnparseableRawViaRegexFallback() {
        // extractJson 截出的片段不是合法 JSON → Jackson 失败 → 正则兜底提取 answer
        ReactDecisionParser.ReasonDecision decision = parser.parse(
                "前缀 {坏json \"answer\":\"残缺答案\"} 后缀");

        assertThat(decision.action()).isEqualTo("finish");
        assertThat(decision.answer()).isEqualTo("残缺答案");
        assertThat(decision.fallback()).isFalse();
    }

    @Test
    void extractAnswerFromRawReadsNestedActionInputAnswer() {
        String answer = parser.extractAnswerFromRaw(
                "{\"action\":\"rag_search\",\"action_input\":{\"answer\":\"嵌套答案\"}}");

        assertThat(answer).isEqualTo("嵌套答案");
    }

    @Test
    void extractAnswerFromRawUnescapesViaRegexFallback() {
        // 无花括号 → readTree 无内容可用 → 正则兜底，并还原 \n 与 \" 转义
        String answer = parser.extractAnswerFromRaw(
                "模型输出异常 \"answer\": \"第一步\\n第二步 \\\"引用\\\" 完\"");

        assertThat(answer).isEqualTo("第一步\n第二步 \"引用\" 完");
    }

    @Test
    void extractAnswerFromRawReturnsEmptyWhenNothingUsable() {
        assertThat(parser.extractAnswerFromRaw("普通文本，没有任何 answer 字段")).isEmpty();
        assertThat(parser.extractAnswerFromRaw("")).isEmpty();
        assertThat(parser.extractAnswerFromRaw(null)).isEmpty();
    }

    // ── fallback() 的分支优先级与关键词语义 ──────────────────────

    @Test
    void schoolBranchWinsOverLaterKnowledgeBaseBranch() {
        // prompt 同时命中「校区」（第 2 支）与「知识库」（第 5 支）→ 先声明者优先
        ReactDecisionParser.ReasonDecision decision = parser.fallback("知识库里没有的校区内容");

        assertThat(decision.action()).isEqualTo("finish");
        assertThat(decision.answer()).contains("校区查询");
        assertThat(decision.citations()).containsExactly("source=fallback://school_query_flow, chunk=1");
    }

    @Test
    void reservationBranchWinsOverHeatKeywords() {
        ReactDecisionParser.ReasonDecision decision = parser.fallback("预约需要高温提示吗");

        assertThat(decision.answer()).contains("课程预约建议");
        assertThat(decision.citations()).containsExactly("source=fallback://course_reservation_schema, chunk=1");
    }

    @Test
    void fallbackMatchesEnglishKeywordsCaseInsensitive() {
        ReactDecisionParser.ReasonDecision campus = parser.fallback("What about CAMPUS info?");
        ReactDecisionParser.ReasonDecision pdf = parser.fallback("Please cite the PDF 来源");

        assertThat(campus.answer()).contains("校区查询");
        assertThat(pdf.action()).isEqualTo("rag_search");
        // rag_search 的 query 用原始大小写的 prompt，不做小写化
        assertThat(pdf.actionInput()).containsEntry("query", "Please cite the PDF 来源");
    }

    @Test
    void fallbackDecisionsCarryCitationEvidenceAndNoActionInput() {
        ReactDecisionParser.ReasonDecision heat = parser.fallback("高温天气注意什么？");

        assertThat(heat.action()).isEqualTo("finish");
        assertThat(heat.actionInput()).isEmpty();
        assertThat(heat.fallback()).isTrue();
        assertThat(heat.citations()).containsExactly("source=fallback://heat_risk_guide, chunk=1");
        assertThat(heat.evidence()).isNotEmpty();
        assertThat(heat.thought()).startsWith("Planner unavailable");
    }
}

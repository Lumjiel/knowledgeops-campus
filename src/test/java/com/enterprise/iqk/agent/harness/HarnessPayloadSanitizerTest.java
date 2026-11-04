package com.enterprise.iqk.agent.harness;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HarnessPayloadSanitizer 的纯单元测试（architecture-agent-harness.md：
 * 「对审计 payload 做脱敏、截断和摘要」）。三类上限以测试固化：
 * 审计事件字符串 600、observation 字符串 4000、集合 30 项 / Map 60 项。
 */
class HarnessPayloadSanitizerTest {

    private final HarnessPayloadSanitizer sanitizer = new HarnessPayloadSanitizer();

    // ── 脱敏：schema 敏感字段 + 关键词兜底 ────────────────────────

    @Test
    void redactsSchemaSensitiveFieldsInActionInput() {
        ActionSchemaRegistry registry = new ActionSchemaRegistry();
        ActionSchema schema = registry.find("add_course_reservation").orElseThrow();
        AgentAction action = new AgentAction("add_course_reservation",
                Map.of("course", "Java", "studentName", "张三", "contactInfo", "13800000000", "school", "S"),
                "prompt", "tenant", "chat", "balanced", "task-1", "step-1");

        Map<String, Object> sanitized = sanitizer.sanitizeActionInput(action, schema);

        // contactInfo 既是 schema.sensitiveFields 也命中关键词，双保险
        assertThat(sanitized).containsEntry("contactInfo", "[REDACTED]");
        assertThat(sanitized).containsEntry("course", "Java");
        assertThat(sanitized).containsEntry("studentName", "张三");
    }

    @Test
    void redactsSensitiveKeywordsEvenWithoutSchema() {
        AgentAction action = new AgentAction("query_school",
                Map.of("password", "p", "apiKey", "k", "Authorization", "Bearer x", "plain", "ok"),
                "prompt", "tenant", "chat", "balanced", "task-1", "step-1");

        Map<String, Object> sanitized = sanitizer.sanitizeActionInput(action, null);

        assertThat(sanitized)
                .containsEntry("password", "[REDACTED]")
                .containsEntry("apiKey", "[REDACTED]")
                .containsEntry("Authorization", "[REDACTED]")
                .containsEntry("plain", "ok");
    }

    @Test
    void sanitizeActionInputHandlesNullActionAndTruncatesLongValues() {
        assertThat(sanitizer.sanitizeActionInput(null, null)).isEmpty();

        AgentAction action = new AgentAction("query_course",
                Map.of("type", "x".repeat(700)), "prompt", "tenant", "chat", "balanced", "task-1", "step-1");

        Map<String, Object> sanitized = sanitizer.sanitizeActionInput(action, null);

        // 审计事件字符串上限 600 + "...[truncated]" 后缀
        assertThat((String) sanitized.get("type")).hasSize(615);
        assertThat((String) sanitized.get("type")).endsWith("...[truncated]");
    }

    // ── 摘要：observation → trace 可消费的最小 Map ───────────────

    @Test
    void summarizeObservationKeepsSourceStatusLatencyAndPayloadKeys() {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            payload.put("key" + i, i);
        }
        payload.put("message", "y".repeat(700));
        AgentObservation observation = AgentObservation.success("builtin", payload, 9);

        Map<String, Object> summary = sanitizer.summarizeObservation(observation);

        assertThat(summary)
                .containsEntry("source", "builtin")
                .containsEntry("status", "success")
                .containsEntry("latencyMs", 9L);
        // payloadKeys 按迭代序截前 30 项（k0..k29），不携带 payload 值本身
        assertThat((List<?>) summary.get("payloadKeys")).hasSize(30);
        assertThat((List<?>) summary.get("payloadKeys")).contains("key0", "key29");
        assertThat(summary).containsKey("message");
        assertThat((String) summary.get("message")).hasSize(615);
    }

    @Test
    void summarizeObservationCoversErrorAndNonMapPayloadAndNull() {
        AgentObservation failure = AgentObservation.error("workspace", "action failed: boom", 4);
        assertThat(sanitizer.summarizeObservation(failure))
                .containsEntry("status", "error")
                .containsEntry("errorMessage", "action failed: boom");

        // 非 Map payload 没有 payloadKeys 摘要
        AgentObservation listed = AgentObservation.success("builtin", List.of("a", "b"), 1);
        assertThat(sanitizer.summarizeObservation(listed)).doesNotContainKey("payloadKeys");

        assertThat(sanitizer.summarizeObservation(null)).isEmpty();
    }

    // ── 截断：limitObservation 的各类型上限 ──────────────────────

    @Test
    void limitObservationTruncatesOversizedStringPayload() {
        AgentObservation observation = AgentObservation.success("builtin", "x".repeat(5000), 3);

        AgentObservation limited = sanitizer.limitObservation(observation);

        assertThat((String) limited.payload()).hasSize(4_015); // 4000 + "...[truncated]"
        assertThat(limited.status()).isEqualTo("success");
        assertThat(limited.source()).isEqualTo("builtin");
        assertThat(limited.latencyMs()).isEqualTo(3L);
    }

    @Test
    void limitObservationCapsMapEntriesAndCollectionItemsWithMarkers() {
        Map<String, Object> bigMap = new LinkedHashMap<>();
        for (int i = 0; i < 70; i++) {
            bigMap.put("k" + i, i);
        }
        List<Integer> bigList = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            bigList.add(i);
        }

        Map<?, ?> limitedMap = (Map<?, ?>) sanitizer.limitObservation(
                AgentObservation.success("builtin", bigMap, 1)).payload();
        List<?> limitedList = (List<?>) sanitizer.limitObservation(
                AgentObservation.success("builtin", bigList, 1)).payload();

        // Map 上限 60 项 + "_truncated" 标记；List 上限 30 项 + 标记元素
        assertThat(limitedMap).hasSize(61);
        assertThat(limitedMap.get("_truncated")).isEqualTo(true);
        assertThat(limitedMap).containsKey("k0").containsKey("k59").doesNotContainKey("k60");
        assertThat(limitedList).hasSize(31);
        assertThat(limitedList.get(30)).isEqualTo(Map.of("_truncated", true));
    }

    @Test
    void limitObservationTruncatesErrorMessageAndKeepsSmallPayloadsAsIs() {
        AgentObservation observation = AgentObservation.error(
                "mcp", "z".repeat(1000), 2);

        AgentObservation limited = sanitizer.limitObservation(observation);

        assertThat(limited.errorMessage()).hasSize(615); // 事件字符串上限 600 + 后缀
        assertThat(limited.payload()).isEqualTo(Map.of());

        // 未超限的小 payload 值保持等值（limit 对 Map 会重建容器，内容不变）
        Map<String, Object> small = Map.of("result", "ok");
        assertThat(sanitizer.limitObservation(AgentObservation.success("builtin", small, 1)).payload())
                .isEqualTo(small);

        assertThat(sanitizer.limitObservation(null)).isNull();
    }
}

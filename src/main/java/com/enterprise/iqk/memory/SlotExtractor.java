package com.enterprise.iqk.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话槽位规则抽取（spec-C3）：正则 + 词表，不用 LLM（可测、零成本）。
 * 抽不到就留 null——不猜年级、不猜专业。
 */
@Component
@RequiredArgsConstructor
public class SlotExtractor {

    private static final List<String> COLLEGES = List.of(
            "计算机", "软件", "电子", "机械", "材料", "经管", "外语", "艺术", "数理", "马克思");
    private static final List<String> MAJORS = List.of(
            "软件工程", "计算机科学与技术", "网络工程", "人工智能", "数据科学");
    private static final List<String> TOPICS = List.of(
            "处分", "入党", "缓考", "转专业", "奖学金", "保研", "宿舍", "实习", "毕业");
    private static final Pattern GRADE_BY_JI = Pattern.compile("(\\d{4})\\s*级");
    private static final Pattern GRADE_BY_ENROLL = Pattern.compile("(\\d{4})\\s*年?入学");

    private final ObjectMapper objectMapper;

    public Slots extract(String text) {
        if (!StringUtils.hasText(text)) {
            return new Slots(null, null, null, List.of());
        }
        String input = text.trim();
        Integer grade = matchGrade(input);
        String college = COLLEGES.stream().filter(c -> input.contains(c + "学院")).findFirst().orElse(null);
        String major = MAJORS.stream().filter(input::contains).findFirst().orElse(null);
        List<String> topics = TOPICS.stream().filter(input::contains).toList();
        return new Slots(grade, college, major, topics);
    }

    private Integer matchGrade(String input) {
        Matcher byJi = GRADE_BY_JI.matcher(input);
        if (byJi.find()) {
            return Integer.parseInt(byJi.group(1));
        }
        Matcher byEnroll = GRADE_BY_ENROLL.matcher(input);
        if (byEnroll.find()) {
            return Integer.parseInt(byEnroll.group(1));
        }
        return null;
    }

    public boolean isEmpty(Slots slots) {
        return slots == null || (slots.grade() == null && slots.college() == null
                && slots.major() == null && slots.topics().isEmpty());
    }

    /**
     * 合并规则（spec-C3）：标量新值覆盖旧值，topics 取并集（保序）。
     */
    public Slots merge(Slots existing, Slots incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }
        LinkedHashSet<String> topics = new LinkedHashSet<>(existing.topics());
        topics.addAll(incoming.topics());
        return new Slots(
                incoming.grade() != null ? incoming.grade() : existing.grade(),
                StringUtils.hasText(incoming.college()) ? incoming.college() : existing.college(),
                StringUtils.hasText(incoming.major()) ? incoming.major() : existing.major(),
                List.copyOf(topics));
    }

    /** 槽位 JSON 序列化（存槽位，不存原文）。失败返回空串，由调用方跳过写入。 */
    public String toJson(Slots slots) {
        try {
            return objectMapper.writeValueAsString(slots);
        } catch (Exception e) {
            return "";
        }
    }

    /** 历史槽位 JSON 反序列化；解析失败返回 null（旧数据可能是任意文本）。 */
    public Slots parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Slots.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 【已知信息】单行摘要：空槽位不拼，避免污染 prompt。 */
    public String describe(Slots slots) {
        if (isEmpty(slots)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(slots.college())) {
            parts.add("学院=" + slots.college());
        }
        if (slots.grade() != null) {
            parts.add("年级=" + slots.grade());
        }
        if (StringUtils.hasText(slots.major())) {
            parts.add("专业=" + slots.major());
        }
        if (!slots.topics().isEmpty()) {
            parts.add("此前关注：" + String.join("、", slots.topics()));
        }
        return String.join("，", parts);
    }

    public record Slots(Integer grade, String college, String major, List<String> topics) {
    }
}

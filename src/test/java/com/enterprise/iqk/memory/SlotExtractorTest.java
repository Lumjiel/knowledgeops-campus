package com.enterprise.iqk.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 槽位规则抽取测试（spec-C3/C6）：正则 + 词表，不猜不编。
 */
class SlotExtractorTest {

    private final SlotExtractor extractor = new SlotExtractor(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void 学院和年级都能抽出() {
        // "我是计算机学院2024级的" → college=计算机, grade=2024
        SlotExtractor.Slots slots = extractor.extract("我是计算机学院2024级的");

        assertThat(slots.college()).isEqualTo("计算机");
        assertThat(slots.grade()).isEqualTo(2024);
    }

    @Test
    void 入学年份和专业都能抽出() {
        // "我2023年入学的，软件工程专业" → grade=2023, major=软件工程
        SlotExtractor.Slots slots = extractor.extract("我2023年入学的，软件工程专业");

        assertThat(slots.grade()).isEqualTo(2023);
        assertThat(slots.major()).isEqualTo("软件工程");
    }

    @Test
    void 话题命中但身份槽位为空() {
        // "旷课怎么处分" → topics 含 "处分"，college/grade 为 null
        SlotExtractor.Slots slots = extractor.extract("旷课怎么处分");

        assertThat(slots.topics()).contains("处分");
        assertThat(slots.college()).isNull();
        assertThat(slots.grade()).isNull();
        assertThat(slots.major()).isNull();
    }

    @Test
    void 相对年级不猜() {
        // "我大二的" → 全部 null（"大二"不抽，避免猜错年级）
        SlotExtractor.Slots slots = extractor.extract("我大二的");

        assertThat(slots.grade()).isNull();
        assertThat(slots.college()).isNull();
        assertThat(slots.major()).isNull();
        assertThat(slots.topics()).isEmpty();
    }

    @Test
    void 空字符串不抛异常() {
        assertThatCode(() -> {
            SlotExtractor.Slots slots = extractor.extract("");
            assertThat(extractor.isEmpty(slots)).isTrue();
            assertThat(extractor.extract(null).grade()).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void 合并规则_新覆盖旧_topics并集() {
        SlotExtractor.Slots existing = new SlotExtractor.Slots(2023, "机械", null, List.of("宿舍"));
        SlotExtractor.Slots incoming = new SlotExtractor.Slots(2024, null, "软件工程", List.of("入党", "宿舍"));

        SlotExtractor.Slots merged = extractor.merge(existing, incoming);

        assertThat(merged.grade()).isEqualTo(2024);
        assertThat(merged.college()).isEqualTo("机械");
        assertThat(merged.major()).isEqualTo("软件工程");
        assertThat(merged.topics()).containsExactly("宿舍", "入党");
    }

    @Test
    void 槽位JSON往返() {
        SlotExtractor.Slots slots = new SlotExtractor.Slots(2024, "计算机", null, List.of("处分", "入党"));

        String json = extractor.toJson(slots);
        SlotExtractor.Slots parsed = extractor.parse(json);

        assertThat(parsed).isEqualTo(slots);
    }

    @Test
    void 非JSON历史内容解析为null() {
        assertThat(extractor.parse("用户问过处分的事")).isNull();
    }
}

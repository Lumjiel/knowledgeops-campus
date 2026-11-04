package com.enterprise.iqk.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 对应 docs/architecture-knowledge-graph.md「GraphService API」一节：
 * 实体搜索（名称/别名）、一跳邻居（含关系方向 IN/OUT）、
 * 事实三元组检索、按类型列实体。
 * 关键词走 SqlLikeUtils.escapeForLike 转义，limit 钳制到 >= 1。
 */
class GraphServiceTest {

    private KgEntityMapper entityMapper;
    private KgRelationMapper relationMapper;
    private KgFactMapper factMapper;
    private GraphService service;

    @BeforeEach
    void setUp() {
        entityMapper = mock(KgEntityMapper.class);
        relationMapper = mock(KgRelationMapper.class);
        factMapper = mock(KgFactMapper.class);
        service = new GraphService(entityMapper, relationMapper, factMapper);
    }

    // ── 实体搜索：空白关键词短路，trim + LIKE 转义 + limit 钳制 ──

    @Test
    void searchEntitiesWithBlankKeywordShortCircuitsWithoutHittingMapper() {
        assertThat(service.searchEntities("tenant", "", 5)).isEmpty();
        assertThat(service.searchEntities("tenant", "   ", 5)).isEmpty();
        assertThat(service.searchEntities("tenant", null, 5)).isEmpty();

        verifyNoInteractions(entityMapper);
    }

    @Test
    void searchEntitiesTrimsKeywordEscapesLikeWildcardsAndClampsLimit() {
        when(entityMapper.searchByName(anyString(), anyString(), anyInt())).thenReturn(List.of());

        // trim 后转义：_ → \_，% → \%；limit 0 钳到 1
        service.searchEntities("tenant", "  Java_100%  ", 0);

        verify(entityMapper).searchByName("tenant", "Java\\_100\\%", 1);
    }

    @Test
    void searchEntitiesPassesValidKeywordAndLimitThrough() {
        List<KgEntityRecord> expected = List.of(course("ent-course-java", "Java编程实战"));
        when(entityMapper.searchByName("tenant", "Java编程实战", 5)).thenReturn(expected);

        assertThat(service.searchEntities("tenant", " Java编程实战 ", 5)).isSameAs(expected);
    }

    // ── 一跳邻居：OUT/IN 方向判定 + 缺失实体跳过 + 邻居实体缓存 ──

    @Test
    void neighborsReportOutgoingAndIncomingDirectionsWithCorrectEntity() {
        KgEntityRecord java = course("ent-course-java", "Java编程实战");
        KgEntityRecord level = skillLevel("ent-level-basic", "零基础入门");
        KgRelationRecord out = relation("ent-course-java", "ent-level-basic", "REQUIRES_LEVEL");
        KgRelationRecord in = relation("ent-course-spring", "ent-course-java", "PREREQUISITE_FOR");

        when(relationMapper.findRelations("tenant", "ent-course-java"))
                .thenReturn(List.of(out, in));
        when(entityMapper.findByEntityId("tenant", "ent-level-basic")).thenReturn(level);
        when(entityMapper.findByEntityId("tenant", "ent-course-spring")).thenReturn(java);

        List<GraphService.GraphNeighbor> neighbors = service.getNeighbors("tenant", "ent-course-java");

        assertThat(neighbors).hasSize(2);
        // 出边：邻居是 target，方向 OUT
        assertThat(neighbors.get(0).getDirection()).isEqualTo("OUT");
        assertThat(neighbors.get(0).getEntity().getEntityId()).isEqualTo("ent-level-basic");
        assertThat(neighbors.get(0).getRelationType()).isEqualTo("REQUIRES_LEVEL");
        // 入边：邻居是 source，方向 IN
        assertThat(neighbors.get(1).getDirection()).isEqualTo("IN");
        assertThat(neighbors.get(1).getEntity().getEntityId()).isEqualTo("ent-course-spring");
        assertThat(neighbors.get(1).getRelationType()).isEqualTo("PREREQUISITE_FOR");
        // 关系权重透传
        assertThat(neighbors.get(0).getWeight()).isEqualTo(0.8);
    }

    @Test
    void neighborsWithoutExistingEntityAreSkipped() {
        when(relationMapper.findRelations("tenant", "ent-course-java")).thenReturn(List.of(
                relation("ent-course-java", "ent-missing", "BELONGS_TO"),
                relation("ent-course-java", "ent-course-spring", "PREREQUISITE_FOR")));
        when(entityMapper.findByEntityId("tenant", "ent-missing")).thenReturn(null);
        when(entityMapper.findByEntityId("tenant", "ent-course-spring"))
                .thenReturn(course("ent-course-spring", "Spring Boot微服务"));

        List<GraphService.GraphNeighbor> neighbors = service.getNeighbors("tenant", "ent-course-java");

        // 实体查不到的关系被丢弃，其余照常返回
        assertThat(neighbors).hasSize(1);
        assertThat(neighbors.get(0).getEntity().getEntityId()).isEqualTo("ent-course-spring");
    }

    @Test
    void repeatedNeighborEntityIsLoadedOnceThroughCache() {
        when(relationMapper.findRelations("tenant", "ent-course-java")).thenReturn(List.of(
                relation("ent-course-java", "ent-level-basic", "REQUIRES_LEVEL"),
                relation("ent-course-java", "ent-level-basic", "BELONGS_TO")));
        when(entityMapper.findByEntityId("tenant", "ent-level-basic"))
                .thenReturn(skillLevel("ent-level-basic", "零基础入门"));

        List<GraphService.GraphNeighbor> neighbors = service.getNeighbors("tenant", "ent-course-java");

        assertThat(neighbors).hasSize(2);
        verify(entityMapper, times(1)).findByEntityId(anyString(), anyString());
    }

    @Test
    void entityWithNoRelationsYieldsNoNeighbors() {
        when(relationMapper.findRelations("tenant", "ent-course-java")).thenReturn(List.of());

        assertThat(service.getNeighbors("tenant", "ent-course-java")).isEmpty();
        verify(entityMapper, never()).findByEntityId(anyString(), anyString());
    }

    // ── 事实检索：空白关键词短路，trim + 转义 + limit 钳制 ────────

    @Test
    void searchFactsWithBlankKeywordShortCircuitsWithoutHittingMapper() {
        assertThat(service.searchFacts("tenant", "  ", 5)).isEmpty();

        verifyNoInteractions(factMapper);
    }

    @Test
    void searchFactsTrimsKeywordEscapesLikeWildcardsAndClampsNegativeLimit() {
        when(factMapper.searchByKeyword(anyString(), anyString(), anyInt())).thenReturn(List.of());

        service.searchFacts("tenant", " Spring% ", -3);

        verify(factMapper).searchByKeyword("tenant", "Spring\\%", 1);
    }

    @Test
    void searchFactsReturnsMapperResultOrderedByConfidence() {
        // 排序（ORDER BY confidence DESC）由 SQL 保证，service 原样透传
        List<KgFactRecord> expected = List.of(
                fact("Java编程实战", "PREREQUISITE_FOR", "Spring Boot微服务", 0.9),
                fact("Java编程实战", "REQUIRES_LEVEL", "零基础入门", 0.8));
        when(factMapper.searchByKeyword("tenant", "Spring", 5)).thenReturn(expected);

        assertThat(service.searchFacts("tenant", "Spring", 5)).isSameAs(expected);
    }

    // ── 按类型列实体：直接透传 ────────────────────────────────────

    @Test
    void getEntitiesByTypeDelegatesToMapper() {
        List<KgEntityRecord> courses = List.of(
                course("ent-course-java", "Java编程实战"),
                course("ent-course-python", "Python数据分析"));
        when(entityMapper.findByType("tenant", "COURSE")).thenReturn(courses);

        assertThat(service.getEntitiesByType("tenant", "COURSE")).isSameAs(courses);
    }

    // ── 测试脚手架 ────────────────────────────────────────────────

    private KgEntityRecord course(String entityId, String name) {
        return KgEntityRecord.builder()
                .entityId(entityId)
                .tenantId("tenant")
                .name(name)
                .type("COURSE")
                .aliases("[]")
                .build();
    }

    private KgEntityRecord skillLevel(String entityId, String name) {
        return KgEntityRecord.builder()
                .entityId(entityId)
                .tenantId("tenant")
                .name(name)
                .type("SKILL_LEVEL")
                .aliases("[]")
                .build();
    }

    private KgRelationRecord relation(String sourceId, String targetId, String relationType) {
        return KgRelationRecord.builder()
                .tenantId("tenant")
                .sourceEntityId(sourceId)
                .targetEntityId(targetId)
                .relationType(relationType)
                .weight(0.8)
                .build();
    }

    private KgFactRecord fact(String subject, String predicate, String object, double confidence) {
        return KgFactRecord.builder()
                .tenantId("tenant")
                .subject(subject)
                .predicate(predicate)
                .object(object)
                .confidence(confidence)
                .build();
    }
}

package com.enterprise.iqk.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryServiceTenantIsolationTest {

    private MemoryItemMapper itemMapper;
    private MemoryEventMapper eventMapper;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        itemMapper = mock(MemoryItemMapper.class);
        eventMapper = mock(MemoryEventMapper.class);
        service = new MemoryService(itemMapper, eventMapper, new ObjectMapper());
    }

    @Test
    void savesAndBuildsTenantScopedMemoryContext() {
        MemoryItemRecord saved = service.saveShortMemory(
                "tenant-a", "user-1", "  prefers Java  ", "chat-1");
        MemoryItemRecord longMemory = MemoryItemRecord.builder().content("backend engineer").build();
        MemoryItemRecord shortMemory = MemoryItemRecord.builder().content("prefers Java").build();
        MemoryItemRecord factMemory = MemoryItemRecord.builder().content("uses Spring Boot").build();
        when(itemMapper.findByUserAndType("tenant-a", "user-1", "short", 5))
                .thenReturn(List.of(shortMemory));
        when(itemMapper.findByUserAndType("tenant-a", "user-1", "long", 10))
                .thenReturn(List.of(longMemory));
        when(itemMapper.findByTypeAndConfidence("tenant-a", "fact", 0.7, 5))
                .thenReturn(List.of(factMemory));

        MemoryService.MemoryContextSnapshot context = service.buildContext("tenant-a", "user-1");

        assertThat(saved.getTenantId()).isEqualTo("tenant-a");
        assertThat(saved.getContent()).isEqualTo("prefers Java");
        assertThat(context.contextText()).contains("backend engineer", "prefers Java");
        assertThat(context.facts()).containsExactly(factMemory);
        verify(itemMapper).insert(saved);
    }

    @Test
    void taskQueryDeleteAndEventsCannotCrossTenantBoundary() {
        when(itemMapper.findByTenantAndTaskId("tenant-b", "task-a"))
                .thenReturn(Collections.emptyList());
        when(itemMapper.findByTenantAndMemoryId("tenant-b", "memory-a"))
                .thenReturn(null);

        assertThat(service.queryTaskMemory("tenant-b", "task-a")).isEmpty();
        service.deleteMemory("tenant-b", "memory-a");
        assertThat(service.getEvents("tenant-b", "memory-a")).isEmpty();

        verify(itemMapper, never()).deleteById(
                (java.io.Serializable) org.mockito.ArgumentMatchers.any());
        verify(eventMapper, never()).findByMemoryId("memory-a");
    }

    // ── 四类记忆的落库矩阵：type / confidence / source / 过期时间 ──

    @Test
    void saveLongAndFactMemoriesCarryTypeConfidenceAndNoExpiry() {
        MemoryItemRecord longMemory = service.saveLongMemory(
                "tenant-a", "user-1", "backend engineer", "profile");
        MemoryItemRecord fact = service.saveFactMemory(
                "tenant-a", "user-1", "uses Spring Boot", "doc", 0.95);

        assertThat(longMemory.getType()).isEqualTo("long");
        assertThat(longMemory.getConfidence()).isEqualTo(0.85);
        assertThat(longMemory.getSource()).isEqualTo("profile");
        assertThat(longMemory.getSourceTaskId()).isNull();
        assertThat(longMemory.getExpiresAt()).isNull();

        assertThat(fact.getType()).isEqualTo("fact");
        assertThat(fact.getConfidence()).isEqualTo(0.95);
        assertThat(fact.getExpiresAt()).isNull();
    }

    @Test
    void saveTaskMemoryTagsSourceTaskIdAndThirtyDayExpiry() {
        LocalDateTime before = LocalDateTime.now();
        MemoryItemRecord task = service.saveTaskMemory("tenant-a", "user-1", "ran migration", "task-9");

        assertThat(task.getType()).isEqualTo("task");
        assertThat(task.getSource()).isEqualTo("task:task-9");
        assertThat(task.getSourceTaskId()).isEqualTo("task-9");
        // 30 天过期：允许少量时钟漂移
        assertThat(task.getExpiresAt()).isAfter(before.plusDays(29).plusMinutes(1));
        assertThat(task.getExpiresAt()).isBefore(before.plusDays(30).plusMinutes(1));
    }

    @Test
    void saveShortMemoryExpiresWithinTwentyFourHours() {
        LocalDateTime before = LocalDateTime.now();
        MemoryItemRecord shortMemory = service.saveShortMemory("tenant-a", "user-1", "hi", "chat-1");

        assertThat(shortMemory.getType()).isEqualTo("short");
        assertThat(shortMemory.getConfidence()).isEqualTo(0.9);
        assertThat(shortMemory.getExpiresAt()).isAfter(before.plusHours(23).plusMinutes(59));
        assertThat(shortMemory.getExpiresAt()).isBefore(before.plusHours(24).plusMinutes(1));
    }

    // ── save 防御与归一化：空白内容 / 默认租户 / ID 格式 ─────────

    @Test
    void saveRejectsBlankContentWithoutTouchingMappers() {
        assertThat(service.saveShortMemory("tenant-a", "user-1", "   ", "chat-1")).isNull();
        assertThat(service.saveShortMemory("tenant-a", "user-1", null, "chat-1")).isNull();
        verifyNoInteractions(itemMapper, eventMapper);
    }

    @Test
    void saveNormalizesBlankTenantToPublicAndGeneratesMemPrefixedId() {
        MemoryItemRecord saved = service.saveLongMemory("  ", "user-1", "note", "profile");

        assertThat(saved.getTenantId()).isEqualTo("public");
        assertThat(saved.getMemoryId()).startsWith("mem-").hasSize("mem-".length() + 32);
        assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt());
        verify(itemMapper).insert(saved);
    }

    // ── 事件：CREATE 落库、失败被吞掉不影响主流程 ────────────────

    @Test
    void saveEmitsCreateEventLinkedToMemoryId() {
        MemoryItemRecord saved = service.saveShortMemory("tenant-a", "user-1", "note", "chat-1");

        ArgumentCaptor<MemoryEventRecord> captor = ArgumentCaptor.forClass(MemoryEventRecord.class);
        verify(eventMapper).insert(captor.capture());
        MemoryEventRecord event = captor.getValue();
        assertThat(event.getEventId()).startsWith("mevt-");
        assertThat(event.getMemoryId()).isEqualTo(saved.getMemoryId());
        assertThat(event.getAction()).isEqualTo("CREATE");
        assertThat(event.getReason()).isEqualTo("saved short memory");
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void saveStillSucceedsWhenEventPersistenceFails() {
        doThrow(new RuntimeException("event store down"))
                .when(eventMapper).insert(any(MemoryEventRecord.class));

        MemoryItemRecord saved = service.saveLongMemory("tenant-a", "user-1", "note", "profile");

        assertThat(saved).isNotNull();
        assertThat(saved.getMemoryId()).startsWith("mem-");
        verify(itemMapper).insert(saved);
    }

    // ── buildContext：段落顺序与 facts 不进正文 ──────────────────

    @Test
    void buildContextOmitsLongSectionWhenEmptyAndKeepsFactsOutOfText() {
        MemoryItemRecord shortMemory = MemoryItemRecord.builder().content("prefers Java").build();
        MemoryItemRecord fact = MemoryItemRecord.builder().content("uses Spring Boot").build();
        when(itemMapper.findByUserAndType("tenant-a", "user-1", "short", 5))
                .thenReturn(List.of(shortMemory));
        when(itemMapper.findByUserAndType("tenant-a", "user-1", "long", 10))
                .thenReturn(Collections.emptyList());
        when(itemMapper.findByTypeAndConfidence("tenant-a", "fact", 0.7, 5))
                .thenReturn(List.of(fact));

        MemoryService.MemoryContextSnapshot context = service.buildContext("tenant-a", "user-1");

        assertThat(context.contextText())
                .doesNotContain("用户长期记忆")
                .doesNotContain("uses Spring Boot") // facts 进快照但不进正文
                .startsWith("近期对话要点")
                .contains("- prefers Java\n");
        assertThat(context.shortMemories()).containsExactly(shortMemory);
        assertThat(context.longMemories()).isEmpty();
        assertThat(context.facts()).containsExactly(fact);
    }

    @Test
    void buildContextWithNoMemoriesYieldsEmptyTextButTypedLists() {
        when(itemMapper.findByUserAndType(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Collections.emptyList());
        when(itemMapper.findByTypeAndConfidence(any(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(Collections.emptyList());

        MemoryService.MemoryContextSnapshot context = service.buildContext("tenant-a", "user-1");

        assertThat(context.contextText()).isEmpty();
        assertThat(context.shortMemories()).isEmpty();
        assertThat(context.longMemories()).isEmpty();
        assertThat(context.facts()).isEmpty();
    }

    // ── 删除与事件查询：命中即删并记 DELETE 事件 ─────────────────

    @Test
    void deleteMemoryRemovesByIdAndEmitsDeleteEventWhenFound() {
        MemoryItemRecord item = MemoryItemRecord.builder().id(5L).memoryId("mem-1").build();
        when(itemMapper.findByTenantAndMemoryId("tenant-a", "mem-1")).thenReturn(item);

        service.deleteMemory("tenant-a", "mem-1");

        verify(itemMapper).deleteById(5L);
        ArgumentCaptor<MemoryEventRecord> captor = ArgumentCaptor.forClass(MemoryEventRecord.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("DELETE");
        assertThat(captor.getValue().getReason()).isEqualTo("manual deletion");
        assertThat(captor.getValue().getMemoryId()).isEqualTo("mem-1");
    }

    @Test
    void getEventsReturnsMapperResultOnlyWhenItemExists() {
        MemoryItemRecord item = MemoryItemRecord.builder().id(7L).memoryId("mem-2").build();
        MemoryEventRecord event = MemoryEventRecord.builder().eventId("mevt-1").memoryId("mem-2").build();
        when(itemMapper.findByTenantAndMemoryId("tenant-a", "mem-2")).thenReturn(item);
        when(eventMapper.findByMemoryId("mem-2")).thenReturn(List.of(event));

        assertThat(service.getEvents("tenant-a", "mem-2")).containsExactly(event);
    }

    // ── 维护任务：清过期只打 itemMapper ──────────────────────────

    @Test
    void cleanExpiredMemoriesDeletesAndEmitsNothing() {
        when(itemMapper.deleteExpired()).thenReturn(3);

        service.cleanExpiredMemories();

        verify(itemMapper).deleteExpired();
        verifyNoInteractions(eventMapper);
    }
}

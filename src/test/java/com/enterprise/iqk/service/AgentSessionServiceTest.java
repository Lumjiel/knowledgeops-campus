package com.enterprise.iqk.service;

import com.enterprise.iqk.domain.AgentSessionStateRecord;
import com.enterprise.iqk.domain.vo.AgentSessionBranchVO;
import com.enterprise.iqk.domain.vo.AgentSessionMessageVO;
import com.enterprise.iqk.domain.vo.AgentSessionStateVO;
import com.enterprise.iqk.domain.vo.BranchCompareResultVO;
import com.enterprise.iqk.domain.vo.BranchMergeResultVO;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.mapper.AgentSessionStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AgentSessionService 的纯单元测试。真实协作者仅 {@link ObjectMapper}（与生产
 * 装配一致），数据库访问 {@link AgentSessionStateMapper} 全部 mock。
 *
 * <p>两个需要特别注意的 mock 时序约定（源于被测实现，勿改测试语义）：
 * <ul>
 *   <li>upsert 成功路径最后会回调 {@code get()} 重新读库，因此
 *       {@code findByTenantAndSessionId} 的桩需要按调用次数依次返回
 *       （首次命中插入/更新前的查询，后续命中读回）；</li>
 *   <li>复用同一个 record 桩对象时，upsert 更新路径的 applyUpsertFields 会
 *       原地改写字段（pinned/archived 等），后续断言读的是改写后的值——
 *       这正好用来验证落库字段。</li>
 * </ul>
 */
class AgentSessionServiceTest {

    private static final String TENANT = "t1";
    private static final String SESSION_ID = "s1";

    private final AgentSessionStateMapper mapper = mock(AgentSessionStateMapper.class);
    private final AgentSessionService service = new AgentSessionService(mapper, new ObjectMapper());

    // ── list()：租户归一化、分页钳制与 LIKE 转义 ──────────────────

    @Test
    void listEscapesLikeWildcardsInKeywordBeforeQuerying() {
        AgentSessionStateRecord record = record(payload());
        when(mapper.countByTenant(eq(TENANT), eq("\\%"), eq("ws1"), eq(false))).thenReturn(1L);
        when(mapper.findByTenant(eq(TENANT), eq("\\%"), eq("ws1"), eq(false), eq(0L), eq(20)))
                .thenReturn(List.of(record));

        PagedResult<AgentSessionStateVO> result = service.list(TENANT, "%", "ws1", false, 1, 20);

        assertThat(result.getItems()).hasSize(1);
        // 通配符必须在进 mapper 前转义，否则 "%" 会匹配租户内全部行
        verify(mapper).countByTenant(TENANT, "\\%", "ws1", false);
    }

    @Test
    void listNormalizesMissingTenantAndClampsPagination() {
        when(mapper.countByTenant("public", null, "all", true)).thenReturn(0L);

        PagedResult<AgentSessionStateVO> result = service.list(null, "   ", "all", true, 0, -5);

        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(1);
        // 租户缺省回落 public、空白关键字折叠为 null、页码/页大小钳制到 ≥1
        verify(mapper).countByTenant("public", null, "all", true);
        verify(mapper, never()).findByTenant(any(), any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void listShortCircuitsToEmptyPageWithoutQueryingRowsWhenTotalIsZero() {
        when(mapper.countByTenant(TENANT, null, null, false)).thenReturn(0L);

        PagedResult<AgentSessionStateVO> result = service.list(TENANT, null, null, false, 2, 10);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(mapper, never()).findByTenant(any(), any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void listMapsRecordColumnsOverPayloadFields() {
        AgentSessionStateRecord record = record("{\"id\":\"ignored\",\"title\":\"载荷标题\"}");
        record.setTitle("列标题");
        record.setStreaming(0);
        record.setPinned(1);
        record.setArchived(1);
        when(mapper.countByTenant(TENANT, null, null, false)).thenReturn(1L);
        when(mapper.findByTenant(TENANT, null, null, false, 0L, 10)).thenReturn(List.of(record));

        PagedResult<AgentSessionStateVO> result = service.list(TENANT, null, null, false, 1, 10);

        AgentSessionStateVO state = result.getItems().get(0);
        assertThat(state.getId()).isEqualTo(SESSION_ID);
        assertThat(state.getTitle()).isEqualTo("列标题");
        assertThat(state.getStreaming()).isFalse();
        assertThat(state.getPinned()).isTrue();
        assertThat(state.getArchived()).isTrue();
        // 载荷未写 branches 时补空列表而不是 null
        assertThat(state.getBranches()).isNotNull().isEmpty();
        long expectedEpoch = LocalDateTime.of(2026, 1, 1, 0, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(state.getUpdatedAt()).isEqualTo(expectedEpoch);
    }

    // ── get()：参数校验与损坏载荷 ────────────────────────────────

    @Test
    void getRejectsBlankSessionId() {
        assertThatThrownBy(() -> service.get(TENANT, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("session id is required");
        verifyNoInteractions(mapper);
    }

    @Test
    void getThrowsWhenSessionMissing() {
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.get(TENANT, SESSION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("session not found");
    }

    @Test
    void getWrapsCorruptPayloadJsonAsIllegalArgument() {
        AgentSessionStateRecord record = record("not-json{");
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);

        assertThatThrownBy(() -> service.get(TENANT, SESSION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failed to parse session payload");
    }

    // ── upsert()：插入路径、乐观锁与重试 ─────────────────────────

    @Test
    void upsertRejectsNullPayloadBeforeTouchingMapper() {
        assertThatThrownBy(() -> service.upsert(TENANT, SESSION_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("session payload is required");
        verifyNoInteractions(mapper);
    }

    @Test
    void upsertRejectsBlankSessionId() {
        assertThatThrownBy(() -> service.upsert(TENANT, "  ", new AgentSessionStateVO()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("session id is required");
        verifyNoInteractions(mapper);
    }

    @Test
    void upsertInsertsWithNormalizedDefaultsWhenSessionIsNew() {
        AgentSessionStateRecord readBack = record(payload());
        // 第一次查询：upsert 前置探测（无记录）；第二次：插入成功后的 get() 读回
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(null, readBack);
        when(mapper.insert(any(AgentSessionStateRecord.class))).thenReturn(1);

        AgentSessionStateVO payload = new AgentSessionStateVO();
        payload.setTitle("   ");
        payload.setWorkspaceId(null);
        payload.setModelProfile(null);
        AgentSessionBranchVO branch = new AgentSessionBranchVO();
        branch.setId("b1");
        payload.setBranches(new ArrayList<>(List.of(branch)));

        AgentSessionStateVO result = service.upsert(" " + TENANT + " ", " " + SESSION_ID + " ", payload);

        ArgumentCaptor<AgentSessionStateRecord> captor =
                ArgumentCaptor.forClass(AgentSessionStateRecord.class);
        verify(mapper).insert(captor.capture());
        AgentSessionStateRecord inserted = captor.getValue();
        assertThat(inserted.getTenantId()).isEqualTo(TENANT);
        assertThat(inserted.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(inserted.getTitle()).isEqualTo("新会话");
        assertThat(inserted.getWorkspaceId()).isEqualTo("default");
        assertThat(inserted.getModelProfile()).isEqualTo("balanced");
        // 载荷未给 streaming 时归一化为 true；pinned/archived 只认 TRUE
        assertThat(inserted.getStreaming()).isEqualTo(1);
        assertThat(inserted.getPinned()).isZero();
        assertThat(inserted.getArchived()).isZero();
        assertThat(inserted.getLockVersion()).isZero();
        // activeBranchId 缺省时回落到首个分支
        assertThat(inserted.getActiveBranchId()).isEqualTo("b1");
        assertThat(result.getId()).isEqualTo(SESSION_ID);
    }

    @Test
    void upsertUpdatesExistingWithExpectedLockVersion() {
        AgentSessionStateRecord existing = record(payload());
        existing.setLockVersion(null); // 旧数据 lock_version 为空按 0 处理
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(existing);
        when(mapper.updateSessionStateIfUnchanged(same(existing), eq(TENANT), eq(SESSION_ID), eq(0L)))
                .thenReturn(1);

        AgentSessionStateVO payload = new AgentSessionStateVO();
        payload.setTitle("更新后的标题");
        payload.setPinned(true);

        AgentSessionStateVO result = service.upsert(TENANT, SESSION_ID, payload);

        verify(mapper, never()).insert(any(AgentSessionStateRecord.class));
        // 更新路径把归一化后的字段回填到 existing 记录上落库
        assertThat(existing.getTitle()).isEqualTo("更新后的标题");
        assertThat(existing.getPinned()).isEqualTo(1);
        assertThat(existing.getUpdatedAt()).isNotNull();
        assertThat(result.getId()).isEqualTo(SESSION_ID);
    }

    @Test
    void upsertRetriesViaUpdatePathWhenInsertRacesOnDuplicateKey() {
        AgentSessionStateRecord existing = record(payload());
        // 第一次探测无记录 → 插入撞唯一键；重试后探测命中已有记录走更新
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(null, existing, existing);
        when(mapper.insert(any(AgentSessionStateRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_agent_session_tenant_session"));
        when(mapper.updateSessionStateIfUnchanged(same(existing), eq(TENANT), eq(SESSION_ID), eq(1L)))
                .thenReturn(1);

        AgentSessionStateVO result = service.upsert(TENANT, SESSION_ID, payloadWithTitle("并发插入"));

        verify(mapper, times(1)).insert(any(AgentSessionStateRecord.class));
        verify(mapper, times(1)).updateSessionStateIfUnchanged(any(), any(), any(), anyLong());
        assertThat(result.getId()).isEqualTo(SESSION_ID);
    }

    @Test
    void upsertRereadsStateAndRetriesWhenOptimisticLockMovedOn() {
        AgentSessionStateRecord existing = record(payload());
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(existing);
        // 前两次 lock_version 冲突（更新 0 行），第三次成功
        when(mapper.updateSessionStateIfUnchanged(same(existing), eq(TENANT), eq(SESSION_ID), eq(1L)))
                .thenReturn(0, 0, 1);

        AgentSessionStateVO result = service.upsert(TENANT, SESSION_ID, payloadWithTitle("重试"));

        verify(mapper, times(3))
                .updateSessionStateIfUnchanged(same(existing), eq(TENANT), eq(SESSION_ID), eq(1L));
        assertThat(result.getId()).isEqualTo(SESSION_ID);
    }

    @Test
    void upsertThrowsIllegalStateAfterExhaustedRetries() {
        AgentSessionStateRecord existing = record(payload());
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(existing);
        when(mapper.updateSessionStateIfUnchanged(same(existing), eq(TENANT), eq(SESSION_ID), eq(1L)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.upsert(TENANT, SESSION_ID, payloadWithTitle("死锁")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session update conflict")
                .hasMessageContaining(SESSION_ID);

        verify(mapper, times(3))
                .updateSessionStateIfUnchanged(same(existing), eq(TENANT), eq(SESSION_ID), eq(1L));
        verify(mapper, never()).insert(any(AgentSessionStateRecord.class));
    }

    // ── setPinned() / setArchived()：先置位再走 upsert 落库 ───────

    @Test
    void setPinnedPersistsFlagThroughUpsert() {
        AgentSessionStateRecord record = record(payload());
        when(mapper.updatePinned(TENANT, SESSION_ID, 1)).thenReturn(1);
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);
        when(mapper.updateSessionStateIfUnchanged(same(record), eq(TENANT), eq(SESSION_ID), eq(1L)))
                .thenReturn(1);

        AgentSessionStateVO result = service.setPinned(TENANT, SESSION_ID, true);

        verify(mapper).updatePinned(TENANT, SESSION_ID, 1);
        // upsert 更新路径把 pinned=1 回填到 record，最终 get() 读回即 true
        assertThat(result.getPinned()).isTrue();
    }

    @Test
    void setPinnedThrowsWhenUpdateTouchesNoRow() {
        when(mapper.updatePinned(TENANT, SESSION_ID, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.setPinned(TENANT, SESSION_ID, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("session not found");
        // 未命中记录时不得再触发读库/落库
        verify(mapper, never()).findByTenantAndSessionId(any(), any());
    }

    @Test
    void setArchivedClearsFlagThroughUpsert() {
        AgentSessionStateRecord record = record(payload());
        record.setArchived(1);
        when(mapper.updateArchived(TENANT, SESSION_ID, 0)).thenReturn(1);
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);
        when(mapper.updateSessionStateIfUnchanged(same(record), eq(TENANT), eq(SESSION_ID), eq(1L)))
                .thenReturn(1);

        AgentSessionStateVO result = service.setArchived(TENANT, SESSION_ID, false);

        verify(mapper).updateArchived(TENANT, SESSION_ID, 0);
        assertThat(result.getArchived()).isFalse();
    }

    // ── compareBranches()：指纹口径与预览截断 ────────────────────

    @Test
    void compareBranchesCountsByRoleAndWhitespaceNormalizedContent() {
        AgentSessionStateRecord record = record(payload(
                branch("source",
                        message("m1", "user", "你好"),
                        message("m2", "assistant", "回答 A"),
                        message("m3", "user", "独有问题")),
                branch("target",
                        message("m4", "user", "你好"),        // 指纹相同 → common
                        message("m5", "assistant", "回答  A"), // 多个空格归一后与 m2 相同
                        message("m6", "assistant", "目标独有"))));

        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);

        BranchCompareResultVO result = service.compareBranches(TENANT, SESSION_ID, "source", "target");

        assertThat(result.getSourceMessageCount()).isEqualTo(3);
        assertThat(result.getTargetMessageCount()).isEqualTo(3);
        assertThat(result.getCommonMessageCount()).isEqualTo(2);
        assertThat(result.getSourceOnlyCount()).isEqualTo(1);
        assertThat(result.getTargetOnlyCount()).isEqualTo(1);
        assertThat(result.getSourceOnlyPreview()).containsExactly("独有问题");
        assertThat(result.getTargetOnlyPreview()).containsExactly("目标独有");
    }

    @Test
    void compareBranchesLimitsPreviewToFiveEntriesAndTruncatesLongContent() {
        AgentSessionBranchVO source = new AgentSessionBranchVO();
        source.setId("source");
        List<AgentSessionMessageVO> sourceMessages = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            sourceMessages.add(message("sm" + i, "user", "长内容" + i + "x".repeat(150)));
        }
        source.setMessages(sourceMessages);
        AgentSessionStateRecord record = record(payload(source, branch("target")));
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);

        BranchCompareResultVO result = service.compareBranches(TENANT, SESSION_ID, "source", "target");

        assertThat(result.getSourceOnlyCount()).isEqualTo(7);
        assertThat(result.getSourceOnlyPreview()).hasSize(5);
        assertThat(result.getSourceOnlyPreview().get(0))
                .hasSize(123)
                .endsWith("...");
    }

    @Test
    void compareBranchesRejectsBlankBranchId() {
        AgentSessionStateRecord record = record(payload(branch("b1")));
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);

        assertThatThrownBy(() -> service.compareBranches(TENANT, SESSION_ID, " ", "b1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("branch id is required");
    }

    @Test
    void compareBranchesThrowsWhenBranchMissing() {
        AgentSessionStateRecord record = record(payload(branch("b1")));
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);

        assertThatThrownBy(() -> service.compareBranches(TENANT, SESSION_ID, "b1", "ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("branch not found");
    }

    // ── mergeBranches()：去重、ID 改名与合并分支结构 ──────────────

    @Test
    void mergeBranchesDeduplicatesByFingerprintAndPutsMergedBranchFirst() {
        AgentSessionStateRecord record = record(payload(
                branch("target",
                        message("m1", "user", "你好")),
                branch("source",
                        message("m2", "user", "你好 "),   // 与 m1 指纹相同 → 去重
                        message(null, "assistant", "回答")))); // 无 id → 生成 merged-* id
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);
        when(mapper.updateSessionStateIfUnchanged(any(), eq(TENANT), eq(SESSION_ID), eq(1L)))
                .thenReturn(1);

        BranchMergeResultVO result = service.mergeBranches(TENANT, SESSION_ID, "source", "target", "合并结果");

        AgentSessionBranchVO merged = result.getMergedBranch();
        assertThat(merged.getTitle()).isEqualTo("合并结果");
        assertThat(merged.getParentBranchId()).isEqualTo("target");
        assertThat(merged.getMessages()).hasSize(2); // 1 条 target 原有 + 1 条去重后的 source
        assertThat(merged.getMessages().get(1).getId()).startsWith("merged-");

        assertThat(result.getMergedMessageCount()).isEqualTo(2);
        // 合并分支插到最前并激活，随后通过 upsert 持久化
        assertThat(result.getSession().getBranches().get(0).getId()).isEqualTo(merged.getId());
        assertThat(result.getSession().getActiveBranchId()).isEqualTo(merged.getId());
        verify(mapper, times(1))
                .updateSessionStateIfUnchanged(any(), eq(TENANT), eq(SESSION_ID), eq(1L));
    }

    @Test
    void mergeBranchesRenamesSourceMessageIdsCollidingWithTarget() {
        AgentSessionStateRecord record = record(payload(
                branch("target", message("m1", "user", "已有消息")),
                branch("source", message("m1", "user", "来源消息不同内容"))));
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);
        when(mapper.updateSessionStateIfUnchanged(any(), eq(TENANT), eq(SESSION_ID), eq(1L)))
                .thenReturn(1);

        BranchMergeResultVO result = service.mergeBranches(TENANT, SESSION_ID, "source", "target", null);

        // 指纹不同所以要合并，但 id 撞名 → 追加 -m<当前已知数> 后缀
        AgentSessionBranchVO merged = result.getMergedBranch();
        assertThat(merged.getMessages()).hasSize(2);
        assertThat(merged.getMessages().get(1).getId()).isEqualTo("m1-m1");
        // 未显式给标题时回落 target 分支标题 + " · merge"
        assertThat(merged.getTitle()).isEqualTo("target · merge");
    }

    // ── attachWorkflowSnapshot()：消息快照回填 ───────────────────

    @Test
    void attachWorkflowSnapshotStampsMatchingMessageAndPersists() {
        AgentSessionMessageVO target = message("m1", "user", "你好");
        AgentSessionStateRecord record = record(payload(
                branch("b1", target),
                branch("b2", message("m1", "user", "别的分支同 id 不应被改"))));
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);
        when(mapper.updateSessionStateIfUnchanged(any(), eq(TENANT), eq(SESSION_ID), eq(1L)))
                .thenReturn(1);

        List<Map<String, Object>> snapshot = List.of(Map.of("k", "v"));
        AgentSessionStateVO saved = service.attachWorkflowSnapshot(
                TENANT, SESSION_ID, "b1", "m1", "task-9", "trace-9", snapshot, Map.of("step", 1));

        assertThat(target.getTaskId()).isEqualTo("task-9");
        assertThat(target.getTraceId()).isEqualTo("trace-9");
        assertThat(target.getMemorySnapshot()).isEqualTo(snapshot);
        assertThat(target.getWorkflowState()).containsEntry("step", 1);
        // 落库后的读回载荷同样带快照字段（序列化往返验证）
        AgentSessionMessageVO persisted = saved.getBranches().get(0).getMessages().get(0);
        assertThat(persisted.getTaskId()).isEqualTo("task-9");
        assertThat(persisted.getTraceId()).isEqualTo("trace-9");
        verify(mapper, times(1))
                .updateSessionStateIfUnchanged(any(), eq(TENANT), eq(SESSION_ID), eq(1L));
    }

    @Test
    void attachWorkflowSnapshotSkipsPersistenceWhenSessionHasNoBranches() {
        AgentSessionStateRecord record = record("{\"id\":\"s1\"}");
        when(mapper.findByTenantAndSessionId(TENANT, SESSION_ID)).thenReturn(record);

        AgentSessionStateVO result = service.attachWorkflowSnapshot(
                TENANT, SESSION_ID, "b1", "m1", "task-9", "trace-9", null, null);

        assertThat(result).isNotNull();
        verify(mapper, never()).insert(any(AgentSessionStateRecord.class));
        verify(mapper, never())
                .updateSessionStateIfUnchanged(any(), any(), any(), anyLong());
    }

    // ── 测试脚手架 ──────────────────────────────────────────────

    private AgentSessionMessageVO message(String id, String role, String content) {
        AgentSessionMessageVO message = new AgentSessionMessageVO();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private AgentSessionBranchVO branch(String id, AgentSessionMessageVO... messages) {
        AgentSessionBranchVO branch = new AgentSessionBranchVO();
        branch.setId(id);
        branch.setTitle(id);
        branch.setMessages(new ArrayList<>(Arrays.asList(messages)));
        return branch;
    }

    private AgentSessionStateVO payload(AgentSessionBranchVO... branches) {
        AgentSessionStateVO state = new AgentSessionStateVO();
        state.setId(SESSION_ID);
        state.setTitle("载荷标题");
        state.setBranches(new ArrayList<>(Arrays.asList(branches)));
        return state;
    }

    private AgentSessionStateVO payloadWithTitle(String title) {
        AgentSessionStateVO state = new AgentSessionStateVO();
        state.setTitle(title);
        return state;
    }

    private AgentSessionStateRecord record(AgentSessionStateVO payload) {
        try {
            return record(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("测试载荷序列化失败", ex);
        }
    }

    private AgentSessionStateRecord record(String payloadJson) {
        return AgentSessionStateRecord.builder()
                .sessionId(SESSION_ID)
                .tenantId(TENANT)
                .title("列标题")
                .workspaceId("default")
                .modelProfile("balanced")
                .streaming(1)
                .pinned(0)
                .archived(0)
                .sessionPayload(payloadJson)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .lockVersion(1L)
                .build();
    }
}

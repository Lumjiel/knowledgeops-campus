package com.enterprise.iqk.service;

import com.enterprise.iqk.domain.AgentSessionStateRecord;
import com.enterprise.iqk.domain.vo.AgentSessionBranchVO;
import com.enterprise.iqk.domain.vo.AgentSessionMessageVO;
import com.enterprise.iqk.domain.vo.AgentSessionStateVO;
import com.enterprise.iqk.domain.vo.BranchCompareResultVO;
import com.enterprise.iqk.domain.vo.BranchMergeResultVO;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.mapper.AgentSessionStateMapper;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.util.SqlLikeUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * File: AgentSessionService.java
 * <p>
 * Tenant-scoped CRUD and branch operations for agent chat sessions. The full
 * session state (branches, messages, trace steps) is stored as a JSON payload
 * in {@code agent_session_state}, with a few hot columns (title, workspace,
 * model profile, pinned/archived flags, {@code lock_version}) promoted to
 * real columns for indexing and optimistic locking.
 * <p>
 * All reads and writes are keyed by tenant via {@link TenantContext#normalize};
 * missing sessions or branches raise {@link IllegalArgumentException}. Writes
 * go through {@link #upsert}, which retries on insert races and
 * {@code lock_version} conflicts, so concurrent updates never silently drop
 * each other.
 * <p>
 * Branch operations ({@link #compareBranches}, {@link #mergeBranches}) treat
 * a message as identical when its {@code role::normalized-content} fingerprint
 * matches, and always write merges into a new branch instead of mutating the
 * originals.
 */
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    /** How many times {@link #upsert} retries before reporting a persistent update conflict. */
    private static final int UPSERT_MAX_ATTEMPTS = 3;

    /** Upper bound of one {@link #list} page; larger requested page sizes are clamped down. */
    private static final int MAX_PAGE_SIZE = 200;

    private final AgentSessionStateMapper agentSessionStateMapper;
    private final ObjectMapper objectMapper;

    /**
     * Lists sessions for a tenant, optionally filtered by title keyword
     * (LIKE-escaped), workspace and archived status. Page and page size are
     * floored at 1 and the page size is capped at {@value #MAX_PAGE_SIZE};
     * ordering and paging are applied in the mapper.
     */
    public PagedResult<AgentSessionStateVO> list(String tenantId,
                                                 String keyword,
                                                 String workspaceId,
                                                 boolean includeArchived,
                                                 int page,
                                                 int pageSize) {
        String tenant = TenantContext.normalize(tenantId);
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        // Escape SQL LIKE wildcards in the user-supplied keyword so a search
        // for "%" or "_" cannot widen the result set beyond the intended rows.
        String safeKeyword = SqlLikeUtils.escapeForLike(emptyToNull(keyword));
        long total = agentSessionStateMapper.countByTenant(tenant, safeKeyword, workspaceId, includeArchived);
        if (total == 0) {
            return new PagedResult<>(Collections.emptyList(), 0, safePage, safePageSize);
        }
        long offset = (long) (safePage - 1) * safePageSize;
        List<AgentSessionStateVO> items = agentSessionStateMapper.findByTenant(tenant, safeKeyword, workspaceId, includeArchived, offset, safePageSize)
                .stream()
                .map(this::toSessionState)
                .toList();
        return new PagedResult<>(items, total, safePage, safePageSize);
    }

    /**
     * Returns one session, mapped from its stored record; throws
     * {@link IllegalArgumentException} if the session does not exist.
     */
    public AgentSessionStateVO get(String tenantId, String sessionId) {
        AgentSessionStateRecord record = findRecord(tenantId, sessionId);
        return toSessionState(record);
    }

    /**
     * Creates or updates a session with defaults filled in
     * ({@link #normalizeSessionPayload}) and the payload serialized to JSON.
     * Uses a bounded optimistic-lock loop: a lost insert race falls back to the
     * update path, and a failed conditional update re-reads the latest
     * {@code lock_version}; after {@value #UPSERT_MAX_ATTEMPTS} failed attempts
     * an {@link IllegalStateException} tells the caller to retry.
     */
    public AgentSessionStateVO upsert(String tenantId, String sessionId, AgentSessionStateVO payload) {
        if (payload == null) {
            throw new IllegalArgumentException("session payload is required");
        }
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("session id is required");
        }
        String tenant = TenantContext.normalize(tenantId);
        String normalizedSessionId = sessionId.trim();

        AgentSessionStateVO state = normalizeSessionPayload(normalizedSessionId, payload);
        String serialized = writeJson(state);

        // Optimistic-lock loop: the previous read-modify-write silently lost concurrent
        // updates (and duplicate inserts raced on uk_agent_session_tenant_session).
        for (int attempt = 0; attempt < UPSERT_MAX_ATTEMPTS; attempt++) {
            AgentSessionStateRecord existing = agentSessionStateMapper.findByTenantAndSessionId(tenant, normalizedSessionId);
            if (existing == null) {
                try {
                    int inserted = agentSessionStateMapper.insert(
                            buildInsertRecord(tenant, normalizedSessionId, state, serialized));
                    if (inserted <= 0) {
                        // A silent zero-row insert would only surface later as a
                        // misleading "session not found" from the read-back get();
                        // report the real cause here instead.
                        throw new IllegalStateException(
                                "session insert affected no rows (possible race): " + normalizedSessionId);
                    }
                    return get(tenant, normalizedSessionId);
                } catch (DuplicateKeyException raced) {
                    // another writer inserted first; retry via the update path
                }
            } else {
                long expectedLockVersion = existing.getLockVersion() == null ? 0L : existing.getLockVersion();
                applyUpsertFields(existing, state, serialized);
                int updated = agentSessionStateMapper.updateSessionStateIfUnchanged(
                        existing, tenant, normalizedSessionId, expectedLockVersion);
                if (updated > 0) {
                    return get(tenant, normalizedSessionId);
                }
                // lock_version moved on: re-read the latest state before writing again
            }
        }
        throw new IllegalStateException("session update conflict, please retry: " + normalizedSessionId);
    }

    /** Builds the insert record with column defaults and {@code lock_version = 0}. */
    private AgentSessionStateRecord buildInsertRecord(String tenant, String sessionId,
                                                      AgentSessionStateVO state, String serialized) {
        LocalDateTime now = LocalDateTime.now();
        return AgentSessionStateRecord.builder()
                .sessionId(sessionId)
                .tenantId(tenant)
                .title(defaultText(state.getTitle(), "新会话"))
                .workspaceId(defaultText(state.getWorkspaceId(), "default"))
                .modelProfile(defaultText(state.getModelProfile(), "balanced"))
                .streaming(Boolean.TRUE.equals(state.getStreaming()) ? 1 : 0)
                .pinned(Boolean.TRUE.equals(state.getPinned()) ? 1 : 0)
                .archived(Boolean.TRUE.equals(state.getArchived()) ? 1 : 0)
                .activeBranchId(state.getActiveBranchId())
                .sessionPayload(serialized)
                .createdAt(now)
                .updatedAt(now)
                .lockVersion(0L)
                .build();
    }

    /**
     * Copies the normalized payload onto the freshly-read record before a
     * conditional update; {@code lockVersion} and {@code createdAt} are left
     * untouched so the WHERE clause and audit trail stay intact.
     */
    private void applyUpsertFields(AgentSessionStateRecord existing,
                                   AgentSessionStateVO state, String serialized) {
        existing.setTitle(defaultText(state.getTitle(), "新会话"));
        existing.setWorkspaceId(defaultText(state.getWorkspaceId(), "default"));
        existing.setModelProfile(defaultText(state.getModelProfile(), "balanced"));
        existing.setStreaming(Boolean.TRUE.equals(state.getStreaming()) ? 1 : 0);
        existing.setPinned(Boolean.TRUE.equals(state.getPinned()) ? 1 : 0);
        existing.setArchived(Boolean.TRUE.equals(state.getArchived()) ? 1 : 0);
        existing.setActiveBranchId(state.getActiveBranchId());
        existing.setSessionPayload(serialized);
        existing.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Flips the pinned flag in its hot column first (which also validates that
     * the session exists), then rewrites the full payload so the JSON blob and
     * the column stay consistent.
     */
    public AgentSessionStateVO setPinned(String tenantId, String sessionId, boolean pinned) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("session id is required");
        }
        String id = sessionId.trim();
        String tenant = TenantContext.normalize(tenantId);
        int updated = agentSessionStateMapper.updatePinned(tenant, id, pinned ? 1 : 0);
        if (updated <= 0) {
            throw new IllegalArgumentException("session not found");
        }
        AgentSessionStateVO state = get(tenant, id);
        state.setPinned(pinned);
        return upsert(tenant, id, state);
    }

    /**
     * Flips the archived flag in its hot column first (which also validates
     * that the session exists), then rewrites the full payload so the JSON
     * blob and the column stay consistent.
     */
    public AgentSessionStateVO setArchived(String tenantId, String sessionId, boolean archived) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("session id is required");
        }
        String id = sessionId.trim();
        String tenant = TenantContext.normalize(tenantId);
        int updated = agentSessionStateMapper.updateArchived(tenant, id, archived ? 1 : 0);
        if (updated <= 0) {
            throw new IllegalArgumentException("session not found");
        }
        AgentSessionStateVO state = get(tenant, id);
        state.setArchived(archived);
        return upsert(tenant, id, state);
    }

    /**
     * Read-only diff of two branches: compares message fingerprints to produce
     * common / source-only / target-only counts plus a short preview of the
     * source-only and target-only messages. Neither branch is modified.
     */
    public BranchCompareResultVO compareBranches(String tenantId, String sessionId, String sourceBranchId, String targetBranchId) {
        AgentSessionStateVO state = get(tenantId, sessionId);
        AgentSessionBranchVO source = findBranch(state, sourceBranchId);
        AgentSessionBranchVO target = findBranch(state, targetBranchId);

        Set<String> sourceFingerprints = toMessageFingerprintSet(source.getMessages());
        Set<String> targetFingerprints = toMessageFingerprintSet(target.getMessages());

        Set<String> common = new LinkedHashSet<>(sourceFingerprints);
        common.retainAll(targetFingerprints);

        Set<String> sourceOnly = new LinkedHashSet<>(sourceFingerprints);
        sourceOnly.removeAll(targetFingerprints);

        Set<String> targetOnly = new LinkedHashSet<>(targetFingerprints);
        targetOnly.removeAll(sourceFingerprints);

        return BranchCompareResultVO.builder()
                .sourceBranchId(sourceBranchId)
                .targetBranchId(targetBranchId)
                .sourceMessageCount(sizeOf(source.getMessages()))
                .targetMessageCount(sizeOf(target.getMessages()))
                .commonMessageCount(common.size())
                .sourceOnlyCount(sourceOnly.size())
                .targetOnlyCount(targetOnly.size())
                .sourceOnlyPreview(previewContents(source.getMessages(), sourceOnly))
                .targetOnlyPreview(previewContents(target.getMessages(), targetOnly))
                .build();
    }

    /**
     * Merges the source branch into a brand-new branch (originals untouched):
     * starts from a copy of the target's messages, appends source-only messages
     * (deduplicated by fingerprint, with missing or colliding message ids
     * re-generated), parents the merged branch to the target, makes it the
     * active branch, and persists via {@link #upsert}.
     */
    public BranchMergeResultVO mergeBranches(String tenantId,
                                             String sessionId,
                                             String sourceBranchId,
                                             String targetBranchId,
                                             String title) {
        String tenant = TenantContext.normalize(tenantId);
        AgentSessionStateVO state = get(tenant, sessionId);
        AgentSessionBranchVO source = findBranch(state, sourceBranchId);
        AgentSessionBranchVO target = findBranch(state, targetBranchId);
        if (source == target || Objects.equals(source.getId(), target.getId())) {
            // Merging a branch into itself would clone it as a "new" branch with
            // zero new messages; refuse instead of silently duplicating.
            throw new IllegalArgumentException("source and target branch must be different");
        }

        List<AgentSessionMessageVO> targetMessages = copyMessages(target.getMessages());
        List<AgentSessionMessageVO> sourceMessages = copyMessages(source.getMessages());

        Set<String> targetFingerprints = toMessageFingerprintSet(targetMessages);
        Set<String> existingIds = messageIdSet(targetMessages);
        for (AgentSessionMessageVO message : sourceMessages) {
            String fingerprint = fingerprint(message);
            if (targetFingerprints.contains(fingerprint)) {
                continue;
            }
            ensureUniqueMessageId(message, existingIds);
            targetMessages.add(message);
            targetFingerprints.add(fingerprint);
        }

        // Register the generated id against the existing branch ids so a merge
        // cannot collide with an already-present branch id.
        Set<String> existingBranchIds = new LinkedHashSet<>();
        if (state.getBranches() != null) {
            for (AgentSessionBranchVO branch : state.getBranches()) {
                if (branch != null && StringUtils.hasText(branch.getId())) {
                    existingBranchIds.add(branch.getId());
                }
            }
        }
        String mergedBranchId = buildMergedBranchId();
        while (!existingBranchIds.add(mergedBranchId)) {
            mergedBranchId = buildMergedBranchId();
        }

        AgentSessionBranchVO mergedBranch = new AgentSessionBranchVO();
        mergedBranch.setId(mergedBranchId);
        mergedBranch.setTitle(StringUtils.hasText(title) ? title.trim() : defaultText(target.getTitle(), "分支") + " · merge");
        mergedBranch.setParentBranchId(target.getId());
        mergedBranch.setParentMessageId(target.getParentMessageId());
        mergedBranch.setUpdatedAt(System.currentTimeMillis());
        mergedBranch.setMessages(targetMessages);
        // Copy instead of sharing the target's list so later mutations of the
        // merged branch cannot bleed into the original branch.
        mergedBranch.setTraceSteps(target.getTraceSteps() == null ? null : new ArrayList<>(target.getTraceSteps()));

        List<AgentSessionBranchVO> branches = state.getBranches() == null ? new ArrayList<>() : new ArrayList<>(state.getBranches());
        branches.add(0, mergedBranch);
        state.setBranches(branches);
        state.setActiveBranchId(mergedBranch.getId());
        state.setUpdatedAt(System.currentTimeMillis());

        AgentSessionStateVO saved = upsert(tenant, sessionId, state);
        return BranchMergeResultVO.builder()
                .session(saved)
                .mergedBranch(mergedBranch)
                .mergedMessageCount(sizeOf(targetMessages))
                .build();
    }

    /** Loads one tenant-scoped record; throws if the session id is blank or not found. */
    private AgentSessionStateRecord findRecord(String tenantId, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("session id is required");
        }
        AgentSessionStateRecord record = agentSessionStateMapper.findByTenantAndSessionId(TenantContext.normalize(tenantId), sessionId.trim());
        if (record == null) {
            throw new IllegalArgumentException("session not found");
        }
        return record;
    }

    /**
     * Maps a stored record to the API VO: the JSON payload is the base, then
     * hot columns take precedence for title/workspace/model/flags, and missing
     * values fall back to the payload or current time.
     */
    private AgentSessionStateVO toSessionState(AgentSessionStateRecord record) {
        AgentSessionStateVO payload = readJson(record.getSessionPayload());
        payload.setId(record.getSessionId());
        payload.setTitle(defaultText(record.getTitle(), payload.getTitle()));
        payload.setWorkspaceId(defaultText(record.getWorkspaceId(), payload.getWorkspaceId()));
        payload.setModelProfile(defaultText(record.getModelProfile(), payload.getModelProfile()));
        payload.setStreaming(Objects.equals(record.getStreaming(), 1));
        payload.setPinned(Objects.equals(record.getPinned(), 1));
        payload.setArchived(Objects.equals(record.getArchived(), 1));
        payload.setActiveBranchId(defaultText(record.getActiveBranchId(), payload.getActiveBranchId()));
        payload.setUpdatedAt(record.getUpdatedAt() == null ? System.currentTimeMillis() : record.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        payload.setBranches(payload.getBranches() == null ? new ArrayList<>() : payload.getBranches());
        return payload;
    }

    /**
     * Fills in defaults for every field of the incoming payload (title, model
     * profile, workspace, booleans) and falls back the active branch to the
     * first branch when unset, so stored JSON is always self-consistent.
     */
    private AgentSessionStateVO normalizeSessionPayload(String sessionId, AgentSessionStateVO payload) {
        AgentSessionStateVO state = new AgentSessionStateVO();
        state.setId(sessionId);
        state.setTitle(defaultText(payload.getTitle(), "新会话"));
        state.setUpdatedAt(payload.getUpdatedAt() == null ? System.currentTimeMillis() : payload.getUpdatedAt());
        state.setModelProfile(defaultText(payload.getModelProfile(), "balanced"));
        state.setStreaming(payload.getStreaming() == null ? Boolean.TRUE : payload.getStreaming());
        state.setPinned(Boolean.TRUE.equals(payload.getPinned()));
        state.setArchived(Boolean.TRUE.equals(payload.getArchived()));
        state.setWorkspaceId(defaultText(payload.getWorkspaceId(), "default"));
        state.setActiveBranchId(payload.getActiveBranchId());
        state.setBranches(payload.getBranches() == null ? new ArrayList<>() : payload.getBranches());
        if (!StringUtils.hasText(state.getActiveBranchId()) && !state.getBranches().isEmpty()) {
            state.setActiveBranchId(state.getBranches().get(0).getId());
        }
        return state;
    }

    /** Resolves a branch by id within the session; throws {@link IllegalArgumentException} otherwise. */
    private AgentSessionBranchVO findBranch(AgentSessionStateVO state, String branchId) {
        if (!StringUtils.hasText(branchId)) {
            throw new IllegalArgumentException("branch id is required");
        }
        if (state.getBranches() == null || state.getBranches().isEmpty()) {
            throw new IllegalArgumentException("branch not found");
        }
        return state.getBranches()
                .stream()
                .filter(item -> Objects.equals(item.getId(), branchId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("branch not found"));
    }

    /** {@return the fingerprint of every message, null-safe} */
    private Set<String> toMessageFingerprintSet(List<AgentSessionMessageVO> messages) {
        Set<String> values = new LinkedHashSet<>();
        if (messages == null) {
            return values;
        }
        for (AgentSessionMessageVO message : messages) {
            values.add(fingerprint(message));
        }
        return values;
    }

    /** Collects the non-blank message ids of a list, used to detect id collisions on merge. */
    private Set<String> messageIdSet(List<AgentSessionMessageVO> messages) {
        Set<String> ids = new LinkedHashSet<>();
        if (messages == null) {
            return ids;
        }
        for (AgentSessionMessageVO message : messages) {
            if (message != null && StringUtils.hasText(message.getId())) {
                ids.add(message.getId());
            }
        }
        return ids;
    }

    /** Contents of the first 5 messages whose fingerprints are in {@code selected}, trimmed for preview. */
    private List<String> previewContents(List<AgentSessionMessageVO> messages, Set<String> selected) {
        if (messages == null || messages.isEmpty() || selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (AgentSessionMessageVO message : messages) {
            if (message == null) {
                continue;
            }
            String fingerprint = fingerprint(message);
            if (!selected.contains(fingerprint)) {
                continue;
            }
            values.add(trimPreview(message.getContent()));
            if (values.size() >= 5) {
                break;
            }
        }
        return values;
    }

    /** Collapses whitespace and clips content to 120 characters plus an ellipsis. */
    private String trimPreview(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 120) + "...";
    }

    /**
     * Identity of a message for compare/merge: {@code role::content} with
     * whitespace normalized, so copies of the same message match even if ids
     * or formatting differ.
     */
    private String fingerprint(AgentSessionMessageVO message) {
        if (message == null) {
            return "null";
        }
        String role = defaultText(message.getRole(), "unknown");
        String content = defaultText(message.getContent(), "");
        String normalizedContent = content.replaceAll("\\s+", " ").trim();
        return role + "::" + normalizedContent;
    }

    /** Deep-ish copy of a message list so merges never mutate the source branch. */
    private List<AgentSessionMessageVO> copyMessages(List<AgentSessionMessageVO> original) {
        if (original == null || original.isEmpty()) {
            return new ArrayList<>();
        }
        List<AgentSessionMessageVO> copied = new ArrayList<>(original.size());
        for (AgentSessionMessageVO item : original) {
            if (item == null) {
                // Corrupt payload entries must not NPE the whole merge; drop them.
                continue;
            }
            AgentSessionMessageVO value = new AgentSessionMessageVO();
            value.setId(item.getId());
            value.setRole(item.getRole());
            value.setContent(item.getContent());
            value.setCreatedAt(item.getCreatedAt());
            value.setState(item.getState());
            value.setCitations(item.getCitations() == null ? List.of() : new ArrayList<>(item.getCitations()));
            value.setEvidence(item.getEvidence() == null ? List.of() : new ArrayList<>(item.getEvidence()));
            copied.add(value);
        }
        return copied;
    }

    /**
     * Guarantees the merged message has an id that is unique within the target
     * branch: generates one when missing, suffixes {@code -m<n>} on collision,
     * and registers the final id in {@code existingIds}.
     */
    private void ensureUniqueMessageId(AgentSessionMessageVO message, Set<String> existingIds) {
        if (message == null) {
            return;
        }
        String id = message.getId();
        if (!StringUtils.hasText(id)) {
            id = "merged-" + System.currentTimeMillis() + "-" + (fingerprint(message).hashCode() & Integer.MAX_VALUE);
            message.setId(id);
        }
        if (existingIds.contains(id)) {
            // The old single-shot "-m<size>" suffix could itself collide with an
            // already-suffixed id; keep incrementing until the id is free.
            int suffix = existingIds.size();
            String candidate = id + "-m" + suffix;
            while (existingIds.contains(candidate)) {
                suffix++;
                candidate = id + "-m" + suffix;
            }
            id = candidate;
            message.setId(id);
        }
        existingIds.add(id);
    }

    /** Timestamp + random suffix branch id for merge results. */
    private String buildMergedBranchId() {
        return "branch-merge-" + System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(100000);
    }

    /** Null-safe {@code List#size}. */
    private int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /** Serializes the session payload; a Jackson failure becomes {@link IllegalArgumentException} with the original cause attached. */
    private String writeJson(AgentSessionStateVO payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            // Message text is asserted by tests; the cause chain carries the
            // actual Jackson reason for logs.
            throw new IllegalArgumentException("failed to serialize session payload", ex);
        }
    }

    /** Deserializes the stored JSON payload; blank or corrupt payloads raise {@link IllegalArgumentException} with the original cause attached. */
    private AgentSessionStateVO readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new AgentSessionStateVO();
        }
        try {
            return objectMapper.readValue(raw, AgentSessionStateVO.class);
        } catch (Exception ex) {
            // Message text is asserted by tests; the cause chain carries the
            // actual Jackson reason for logs.
            throw new IllegalArgumentException("failed to parse session payload", ex);
        }
    }

    /**
     * Attach a workflow task snapshot to a session branch message.
     * This links the conversational message to its agent execution trace.
     *
     * @throws IllegalArgumentException when branchId/messageId is blank, or no
     *                                  message in the given branch matches
     *                                  {@code messageId} (nothing is persisted
     *                                  in that case instead of a silent no-op)
     */
    public AgentSessionStateVO attachWorkflowSnapshot(String tenantId, String sessionId,
                                                       String branchId, String messageId,
                                                       String taskId, String traceId,
                                                       List<Map<String, Object>> memorySnapshot,
                                                       Map<String, Object> workflowState) {
        if (!StringUtils.hasText(branchId)) {
            throw new IllegalArgumentException("branch id is required");
        }
        if (!StringUtils.hasText(messageId)) {
            throw new IllegalArgumentException("message id is required");
        }
        AgentSessionStateVO state = get(tenantId, sessionId);
        if (state.getBranches() == null) return state;

        boolean matched = false;
        for (AgentSessionBranchVO branch : state.getBranches()) {
            if (branch == null || !Objects.equals(branchId, branch.getId())) continue;
            if (branch.getMessages() == null) continue;
            for (AgentSessionMessageVO msg : branch.getMessages()) {
                if (msg == null || !Objects.equals(messageId, msg.getId())) continue;
                msg.setTaskId(taskId);
                msg.setTraceId(traceId);
                msg.setMemorySnapshot(memorySnapshot);
                msg.setWorkflowState(workflowState);
                matched = true;
            }
        }
        if (!matched) {
            // The previous silent no-op still ran upsert, rewriting an unchanged
            // payload and advancing lock_version as if something was persisted;
            // report the miss explicitly instead.
            throw new IllegalArgumentException("message not found in branch: sessionId=" + sessionId
                    + ", branchId=" + branchId + ", messageId=" + messageId);
        }
        return upsert(tenantId, sessionId, state);
    }

    /** Trims to null so blank filters are treated as "no filter". */
    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** {@return value if it has text, otherwise the fallback} */
    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}

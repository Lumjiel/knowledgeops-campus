package com.enterprise.iqk.memory;

import com.enterprise.iqk.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 分租户记忆服务：短期/长期/任务/事实四类记忆的写入、查询、上下文组装与生命周期维护。
 *
 * <p>记忆按 {@code (tenantId, type)} 维度落库（{@link MemoryItemRecord}），
 * 所有读写均以 {@link TenantContext#normalize} 归一后的租户标识做隔离。
 * 每次写入/手动删除会同步落一条审计事件（{@link MemoryEventRecord}，
 * 经 {@link #emitEvent}，尽力而为、失败仅记日志）。</p>
 *
 * <p>四类记忆的默认策略：
 * <ul>
 *   <li>short —— 保留 24 小时，置信度 0.9（近期对话要点）；</li>
 *   <li>long —— 永不过期，置信度 0.85（用户画像/偏好）；</li>
 *   <li>task —— 关联 taskId，保留 30 天，置信度 0.9；</li>
 *   <li>fact —— 置信度由调用方指定，永不过期。</li>
 * </ul>
 *
 * <p>对外由 {@code MemoryController}（REST）与 {@code RagAnswerService}
 * （组装上下文）调用；过期清理由 {@link #cleanExpiredMemories()} 每日定时执行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final MemoryItemMapper itemMapper;
    private final MemoryEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    // ── Save ─────────────────────────────────────────────────────

    /** 保存短期记忆：默认置信度 0.9，24 小时后过期。 */
    public MemoryItemRecord saveShortMemory(String tenantId, String userId,
                                             String content, String source) {
        return save(tenantId, userId, "short", content, source, null, 0.9,
                LocalDateTime.now().plusHours(24));
    }

    /** 保存长期记忆：默认置信度 0.85，永不过期。 */
    public MemoryItemRecord saveLongMemory(String tenantId, String userId,
                                            String content, String source) {
        return save(tenantId, userId, "long", content, source, null, 0.85, null);
    }

    /** 保存任务记忆：source 记为 {@code task:<taskId>} 并回填 sourceTaskId，保留 30 天。 */
    public MemoryItemRecord saveTaskMemory(String tenantId, String userId,
                                            String content, String taskId) {
        return save(tenantId, userId, "task", content, "task:" + taskId, taskId, 0.9,
                LocalDateTime.now().plusDays(30));
    }

    /** 保存事实记忆：置信度由调用方指定，永不过期。 */
    public MemoryItemRecord saveFactMemory(String tenantId, String userId,
                                            String content, String source,
                                            double confidence) {
        return save(tenantId, userId, "fact", content, source, null,
                confidence, null);
    }

    /**
     * 四类 save* 的统一落库入口：生成 {@code mem-<uuid>} 标识、归一租户、
     * trim 正文后 insert，并同步落一条 CREATE 审计事件。
     *
     * @return 新建的记忆记录；content 为空白时返回 {@code null}（不落库）
     */
    private MemoryItemRecord save(String tenantId, String userId, String type,
                                   String content, String source, String sourceTaskId,
                                   double confidence, LocalDateTime expiresAt) {
        if (!StringUtils.hasText(content)) return null;
        String memoryId = "mem-" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        MemoryItemRecord item = MemoryItemRecord.builder()
                .memoryId(memoryId)
                .tenantId(TenantContext.normalize(tenantId))
                .userId(userId)
                .type(type)
                .content(content.trim())
                .source(source)
                .sourceTaskId(sourceTaskId)
                .confidence(confidence)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        itemMapper.insert(item);
        emitEvent(memoryId, "CREATE", "saved " + type + " memory");
        return item;
    }

    // ── Query ─────────────────────────────────────────────────────

    /** 查询用户短期记忆，按租户隔离，最多返回 {@code limit} 条。 */
    public List<MemoryItemRecord> queryShortMemory(String tenantId, String userId, int limit) {
        return itemMapper.findByUserAndType(TenantContext.normalize(tenantId), userId, "short", limit);
    }

    /** 查询用户长期记忆（画像/偏好），按租户隔离，最多返回 {@code limit} 条。 */
    public List<MemoryItemRecord> queryLongMemory(String tenantId, String userId, int limit) {
        return itemMapper.findByUserAndType(TenantContext.normalize(tenantId), userId, "long", limit);
    }

    /** 查询指定任务关联的全部任务记忆，按租户隔离。 */
    public List<MemoryItemRecord> queryTaskMemory(String tenantId, String taskId) {
        return itemMapper.findByTenantAndTaskId(TenantContext.normalize(tenantId), taskId);
    }

    /** 查询置信度不低于 {@code minConfidence} 的事实记忆，按租户隔离。 */
    public List<MemoryItemRecord> queryFactMemory(String tenantId, double minConfidence, int limit) {
        return itemMapper.findByTypeAndConfidence(
                TenantContext.normalize(tenantId), "fact", minConfidence, limit);
    }

    /**
     * Query all relevant memories for a user:
     * - Recent short-term memories
     * - Long-term profile/preferences
     * - High-confidence facts
     *
     * <p>拼装规则：contextText 仅包含长期记忆（上限 10 条）与短期记忆
     * （上限 5 条）两段；facts 按置信度 ≥ 0.7、上限 5 条查询，随快照返回
     * 但不拼入 contextText，由调用方自行取舍。</p>
     *
     * @param tenantId 租户标识（内部归一化）
     * @param userId   用户标识
     * @return 上下文快照；无任何记忆时 contextText 为空串，各列表为空
     */
    public MemoryContextSnapshot buildContext(String tenantId, String userId) {
        List<MemoryItemRecord> shortMem = queryShortMemory(tenantId, userId, 5);
        List<MemoryItemRecord> longMem = queryLongMemory(tenantId, userId, 10);
        List<MemoryItemRecord> facts = queryFactMemory(tenantId, 0.7, 5);

        StringBuilder context = new StringBuilder();
        if (!longMem.isEmpty()) {
            context.append("用户长期记忆:\n");
            for (MemoryItemRecord m : longMem) {
                context.append("- ").append(m.getContent()).append("\n");
            }
            context.append("\n");
        }
        if (!shortMem.isEmpty()) {
            context.append("近期对话要点:\n");
            for (MemoryItemRecord m : shortMem) {
                context.append("- ").append(m.getContent()).append("\n");
            }
        }
        return new MemoryContextSnapshot(context.toString(), shortMem, longMem, facts);
    }

    // ── Maintenance ──────────────────────────────────────────────

    /**
     * 定时清理（每日凌晨 3 点）：物理删除 {@code expires_at} 已过期的记忆条目，
     * 有删除量时输出 INFO 日志。
     */
    @Scheduled(cron = "0 0 3 * * ?") // daily at 3am
    public void cleanExpiredMemories() {
        int deleted = itemMapper.deleteExpired();
        if (deleted > 0) {
            log.info("Cleaned {} expired memory items", deleted);
        }
    }

    /**
     * 手动删除单条记忆：先按（归一化租户 + memoryId）确认归属，命中才物理
     * 删除并落 DELETE 审计事件；不存在时静默返回（幂等）。
     */
    public void deleteMemory(String tenantId, String memoryId) {
        MemoryItemRecord item = itemMapper.findByTenantAndMemoryId(TenantContext.normalize(tenantId), memoryId);
        if (item != null) {
            itemMapper.deleteById(item.getId());
            emitEvent(memoryId, "DELETE", "manual deletion");
        }
    }

    // ── Event ─────────────────────────────────────────────────────

    /**
     * 落一条记忆审计事件（CREATE/DELETE），尽力而为：任何异常仅记录 ERROR
     * 日志，不影响主流程。事件本身不携带租户标识，归属经 memoryId 间接关联。
     */
    private void emitEvent(String memoryId, String action, String reason) {
        try {
            MemoryEventRecord event = MemoryEventRecord.builder()
                    .eventId("mevt-" + UUID.randomUUID().toString().replace("-", ""))
                    .memoryId(memoryId)
                    .action(action)
                    .reason(reason)
                    .createdAt(LocalDateTime.now())
                    .build();
            eventMapper.insert(event);
        } catch (Exception e) {
            log.error("Failed to persist memory event", e);
        }
    }

    /**
     * 查询记忆的审计事件流：先校验记忆确实归属该租户（防止跨租户探测），
     * 记忆不存在返回空列表，存在则返回其全部事件。
     */
    public List<MemoryEventRecord> getEvents(String tenantId, String memoryId) {
        MemoryItemRecord item = itemMapper.findByTenantAndMemoryId(TenantContext.normalize(tenantId), memoryId);
        return item == null ? Collections.emptyList() : eventMapper.findByMemoryId(memoryId);
    }

    /**
     * 组装好的记忆上下文快照。
     *
     * @param contextText   已拼装的纯文本上下文（长期 + 短期两段），可直接注入 prompt
     * @param shortMemories 近期对话要点（结构化，上限 5 条）
     * @param longMemories  用户画像/偏好（结构化，上限 10 条）
     * @param facts         高置信度事实（置信度 ≥ 0.7，上限 5 条）
     */
    public record MemoryContextSnapshot(String contextText,
                                         List<MemoryItemRecord> shortMemories,
                                         List<MemoryItemRecord> longMemories,
                                         List<MemoryItemRecord> facts) {}
}

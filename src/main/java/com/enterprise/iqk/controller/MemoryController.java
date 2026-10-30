package com.enterprise.iqk.controller;

import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.memory.SlotExtractor;
import com.enterprise.iqk.security.AuthIdentity;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆管理接口（spec-C5）：nginx 侧以 /api/memory 访问（/api 前缀被反代剥掉）。
 * fact 只允许人工写入（confidence ≥ 0.8），不从对话自动写入——防记忆污染。
 */
@RestController
@RequestMapping("/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;
    private final SlotExtractor slotExtractor;

    @GetMapping
    public Map<String, Object> memory(@RequestParam(value = "userId", required = false) String userId) {
        String tenantId = TenantContext.currentTenantId();
        String uid = resolveUserId(userId);
        List<MemoryItemRecord> longMems = memoryService.queryLongMemory(tenantId, uid, 1);
        SlotExtractor.Slots slots = longMems.isEmpty() ? null : slotExtractor.parse(longMems.get(0).getContent());
        List<MemoryItemRecord> facts = memoryService.queryFactMemory(tenantId, 0.8, 5);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slots", slotExtractor.isEmpty(slots) ? Map.of() : slots);
        payload.put("facts", facts);
        payload.put("memoryId", longMems.isEmpty() ? "" : longMems.get(0).getMemoryId());
        return payload;
    }

    @DeleteMapping("/{memoryId}")
    public Map<String, Object> delete(@PathVariable("memoryId") String memoryId) {
        memoryService.deleteMemory(TenantContext.currentTenantId(), memoryId);
        return Map.of("ok", 1);
    }

    @PostMapping("/fact")
    public Map<String, Object> saveFact(@RequestBody Map<String, Object> body) {
        double confidence = body.get("confidence") instanceof Number number ? number.doubleValue() : 0.0;
        if (confidence < 0.8) {
            return Map.of("ok", 0, "msg", "confidence must be >= 0.8");
        }
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        if (!StringUtils.hasText(content)) {
            return Map.of("ok", 0, "msg", "content is required");
        }
        String source = body.get("source") == null ? "manual" : String.valueOf(body.get("source"));
        String uid = resolveUserId(body.get("userId") == null ? null : String.valueOf(body.get("userId")));
        MemoryItemRecord saved = memoryService.saveFactMemory(
                TenantContext.currentTenantId(), uid, content, source, confidence);
        return Map.of("ok", 1, "memoryId", saved == null ? "" : saved.getMemoryId());
    }

    /**
     * userId 优先取调用方传入；否则取 JWT 身份（AuthIdentity principal）；
     * 演示模式（安全关闭）无身份，退化为 anonymous。
     */
    private String resolveUserId(String explicit) {
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthIdentity identity
                && StringUtils.hasText(identity.getPrincipal())) {
            return identity.getPrincipal();
        }
        return "anonymous";
    }
}

package com.enterprise.iqk.controller;

import com.enterprise.iqk.rag.RagAnswerService;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检索调试端点：只跑「多路召回 + 两段重排」，不进 LLM。
 * 供离线评测（Hit@K / MRR）与 bad case 定位使用，返回带 metadata 的 Top-K 文档。
 */
@RestController
@RequestMapping("/ai/retrieval")
@RequiredArgsConstructor
public class RetrievalDebugController {

    private final RagAnswerService ragAnswerService;

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        String prompt = String.valueOf(body.getOrDefault("prompt", ""));
        String chatId = String.valueOf(body.getOrDefault("chatId", "eval-harness"));
        int topK = body.get("topK") == null ? 10 : Integer.parseInt(String.valueOf(body.get("topK")));
        String tenantId = TenantContext.normalize((String) body.get("tenantId"));

        RagAnswerService.PipelineDebugResult result =
                ragAnswerService.debugRetrieve(prompt, tenantId, chatId, topK);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", 1);
        resp.put("query", prompt);
        resp.put("recallCount", result.recallCount());
        resp.put("degraded", result.degraded());
        resp.put("recallMs", result.recallMs());
        resp.put("coarseMs", result.coarseMs());
        resp.put("fineMs", result.fineMs());
        resp.put("documents", result.documents());
        return resp;
    }
}

package com.enterprise.iqk.controller;

import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.retrieval.ScoredDocument;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索调试端点：只跑「多路召回 + 两段重排」，不进 LLM。
 * 供离线评测（Hit@K / MRR）与 bad case 定位使用，返回带 metadata 的 Top-K 文档。
 */
@RestController
@RequestMapping("/ai/retrieval")
@RequiredArgsConstructor
public class RetrievalDebugController {

    private final HybridRetrievalService hybridRetrievalService;

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        String prompt = String.valueOf(body.getOrDefault("prompt", ""));
        String chatId = String.valueOf(body.getOrDefault("chatId", "eval-harness"));
        int topK = body.get("topK") == null ? 10 : Integer.parseInt(String.valueOf(body.get("topK")));
        String tenantId = TenantContext.normalize((String) body.get("tenantId"));

        HybridRetrievalService.HybridRetrievalResult result =
                hybridRetrievalService.retrieve(prompt, tenantId, chatId, topK);

        List<Map<String, Object>> docs = new ArrayList<>();
        int rank = 1;
        for (ScoredDocument doc : result.documents()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("sourceType", doc.getSourceType());
            row.put("title", doc.getTitle());
            row.put("chunkId", doc.getChunkId());
            row.put("retrievalScore", doc.getRetrievalScore());
            row.put("finalScore", doc.getFinalScore());
            row.put("metadata", doc.getMetadata());
            row.put("contentHead", doc.getContent() == null ? "" : doc.getContent().substring(0, Math.min(160, doc.getContent().length())));
            docs.add(row);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", 1);
        resp.put("query", prompt);
        resp.put("totalBeforeDedup", result.totalBeforeDedup());
        resp.put("documents", docs);
        return resp;
    }
}

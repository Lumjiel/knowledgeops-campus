package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.config.properties.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-Encoder 精排：调用 DashScope gte-rerank（生产级 CE 服务）。
 * 与 RRF（只看排名融合）不同，CE 把 (query, chunk) 成对输入模型做深度语义打分，
 * 是 Rerank 阶段的标准实现（见概念网络《bi-encoder 与 Cross-Encoder》《Rerank》）。
 * 失败/超时由 RerankPipeline 的预算机制兜底降级。
 */
@Slf4j
@Component
public class GteReranker implements Reranker {

    private static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final RagProperties ragProperties;
    private final RestClient restClient;

    public GteReranker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public List<ScoredDocument> rerank(String query, List<ScoredDocument> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return documents == null ? List.of() : documents;
        }
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[gte-rerank] no api key configured, skip CE rerank");
            return documents.stream().limit(Math.max(0, topK)).toList();
        }

        List<String> docTexts = new ArrayList<>();
        for (ScoredDocument doc : documents) {
            String content = doc.getContent() == null ? "" : doc.getContent();
            // CE 输入截断：gte-rerank 单文档上限有限，保留头部语义
            docTexts.add(content.length() > 1500 ? content.substring(0, 1500) : content);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", docTexts);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("return_documents", false);
        parameters.put("top_n", Math.max(0, topK));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", ragProperties.getRerankModel());
        payload.put("input", input);
        payload.put("parameters", parameters);

        Map<?, ?> resp = restClient.post()
                .uri(ragProperties.getRerankEndpoint())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Map.class);

        List<ScoredDocument> ranked = applyScores(documents, resp);
        ranked.sort(Comparator.comparingDouble(ScoredDocument::getFinalScore).reversed());
        return ranked.stream().limit(Math.max(0, topK)).toList();
    }

    /** 把 API 返回的 relevance_score 写回 finalScore（index 对应提交时的文档顺序）。 */
    @SuppressWarnings("unchecked")
    private List<ScoredDocument> applyScores(List<ScoredDocument> documents, Map<?, ?> resp) {
        List<ScoredDocument> out = new ArrayList<>(documents);
        if (resp == null) {
            return out;
        }
        Object outputObj = ((Map<String, Object>) resp).get("output");
        if (!(outputObj instanceof Map)) {
            return out;
        }
        Object resultsObj = ((Map<String, Object>) outputObj).get("results");
        if (!(resultsObj instanceof List<?> results)) {
            return out;
        }
        for (Object r : results) {
            if (!(r instanceof Map)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) r;
            int index = row.get("index") == null ? -1 : Integer.parseInt(String.valueOf(row.get("index")));
            double score = row.get("relevance_score") == null
                    ? 0.0 : Double.parseDouble(String.valueOf(row.get("relevance_score")));
            if (index >= 0 && index < out.size()) {
                ScoredDocument doc = out.get(index);
                doc.setFinalScore(score);
                // 让下游（evidence judge / citation 排序）沿用 CE 分数
                doc.setRetrievalScore(score);
            }
        }
        return out;
    }

    private String resolveApiKey() {
        String key = System.getenv("RERANK_API_KEY");
        return key != null && !key.isBlank() ? key : System.getenv("OPENAI_API_KEY");
    }

    @Override
    public String getName() {
        return "gte-rerank:" + ragProperties.getRerankModel();
    }
}

package com.enterprise.iqk.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 精排（spec-B4）：把 BM25/粗排名次与向量相似度名次做倒数排名融合。
 * 纯本地计算，无外部调用（半夜跑不能依赖 API）。
 */
@Component
public class RrfReranker implements Reranker {

    static final int K = 60;

    @Override
    public List<ScoredDocument> rerank(String query, List<ScoredDocument> documents, int topK) {
        // 接口形态：向量名次取 retrievalScore 排序，输入顺序名次取列表位次
        List<ScoredDocument> byVector = new ArrayList<>(documents);
        byVector.sort(Comparator.comparingDouble(ScoredDocument::getRetrievalScore).reversed());
        return rerankFusion(byVector, documents, topK);
    }

    /**
     * 管线专用：显式传入两份排名（向量序、粗排序），RRF 融合后取 topK。
     * finalScore = Σ 1 / (K + rank)，K=60。
     */
    public List<ScoredDocument> rerankFusion(List<ScoredDocument> vectorRanked,
                                             List<ScoredDocument> coarseRanked,
                                             int topK) {
        if (vectorRanked == null || vectorRanked.isEmpty()) {
            return List.of();
        }
        Map<String, ScoredDocument> byDocId = new HashMap<>();
        Map<String, Double> rrfScore = new HashMap<>();
        accumulate(vectorRanked, byDocId, rrfScore);
        accumulate(coarseRanked, byDocId, rrfScore);

        List<ScoredDocument> merged = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rrfScore.entrySet()) {
            ScoredDocument copy = copyOf(byDocId.get(entry.getKey()));
            copy.setFinalScore(entry.getValue());
            merged.add(copy);
        }
        merged.sort(Comparator.comparingDouble(ScoredDocument::getFinalScore).reversed());
        return merged.stream().limit(Math.max(0, topK)).toList();
    }

    private void accumulate(List<ScoredDocument> ranked,
                            Map<String, ScoredDocument> byDocId,
                            Map<String, Double> rrfScore) {
        if (ranked == null) {
            return;
        }
        for (int rank = 0; rank < ranked.size(); rank++) {
            ScoredDocument d = ranked.get(rank);
            String key = d.getDocId();
            if (key == null) {
                continue;
            }
            byDocId.putIfAbsent(key, d);
            rrfScore.merge(key, 1.0 / (K + rank + 1), Double::sum);
        }
    }

    private ScoredDocument copyOf(ScoredDocument source) {
        return ScoredDocument.builder()
                .docId(source.getDocId())
                .sourceType(source.getSourceType())
                .title(source.getTitle())
                .url(source.getUrl())
                .chunkId(source.getChunkId())
                .content(source.getContent())
                .retrievalScore(source.getRetrievalScore())
                .finalScore(source.getFinalScore())
                .metadata(source.getMetadata())
                .build();
    }

    @Override
    public String getName() {
        return "rrf";
    }
}

package com.enterprise.iqk.retrieval;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从本次候选集统计 DF 的简易实现（spec-B3）。
 * simplified: DF 来自候选集，非全量语料——同一候选集内可比，跨请求不可比。
 */
@Component
public class CandidateCorpusStats implements CorpusStats {

    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final int docCount;
    private final double avgDocLength;

    public CandidateCorpusStats(List<List<String>> tokenizedDocs) {
        this.docCount = tokenizedDocs.size();
        long totalTokens = 0;
        for (List<String> tokens : tokenizedDocs) {
            totalTokens += tokens.size();
            new java.util.LinkedHashSet<>(tokens).forEach(term ->
                    documentFrequency.merge(term, 1, Integer::sum));
        }
        this.avgDocLength = docCount == 0 ? 0.0 : (double) totalTokens / docCount;
    }

    @Override
    public double idf(String term) {
        int df = documentFrequency.getOrDefault(term, 0);
        // Lucene 风格平滑，保证 idf 恒 > 0
        return Math.log((docCount - df + 0.5) / (df + 0.5) + 1.0);
    }

    @Override
    public int docCount() {
        return docCount;
    }

    @Override
    public double avgDocLength() {
        return avgDocLength;
    }
}

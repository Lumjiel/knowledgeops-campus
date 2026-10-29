package com.enterprise.iqk.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 粗排（spec-B3）：k1=1.2, b=0.75，DF 从本次候选集统计（CandidateCorpusStats）。
 * 分词复用 Tokenizer（regex|bigram 配置驱动），无外部依赖。
 */
@Component
public class Bm25Reranker implements Reranker {

    static final double K1 = 1.2;
    static final double B = 0.75;

    private final Tokenizer tokenizer;

    public Bm25Reranker(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    @Override
    public List<ScoredDocument> rerank(String query, List<ScoredDocument> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = List.copyOf(tokenizer.tokenize(query));
        List<List<String>> docsTokens = documents.stream()
                .map(d -> List.copyOf(tokenizer.tokenize(d.getContent())))
                .toList();
        CandidateCorpusStats stats = new CandidateCorpusStats(docsTokens);

        List<ScoredDocument> scored = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            double score = bm25Score(queryTerms, docsTokens.get(i), stats);
            ScoredDocument copy = copyOf(documents.get(i));
            copy.setFinalScore(score);
            scored.add(copy);
        }
        scored.sort(Comparator.comparingDouble(ScoredDocument::getFinalScore).reversed());
        return scored.stream().limit(Math.max(0, topK)).toList();
    }

    private double bm25Score(List<String> queryTerms, List<String> docTokens, CandidateCorpusStats stats) {
        if (queryTerms.isEmpty() || docTokens.isEmpty() || stats.docCount() == 0) {
            return 0.0;
        }
        Map<String, Integer> termFrequency = new HashMap<>();
        for (String token : docTokens) {
            termFrequency.merge(token, 1, Integer::sum);
        }
        double score = 0.0;
        for (String term : queryTerms) {
            int tf = termFrequency.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            double idf = stats.idf(term);
            double dl = docTokens.size();
            score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / Math.max(stats.avgDocLength(), 1e-9)));
        }
        return score;
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
        return "bm25";
    }
}

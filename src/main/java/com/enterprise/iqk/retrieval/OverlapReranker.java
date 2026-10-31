package com.enterprise.iqk.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 原 RagAnswerService 私有重排逻辑原样外迁（spec-B1）。
 * 打分算法逐字节不变：overlap / docTokens.size()，空文档 0.0；分词走 Tokenizer
 * （regex|bigram 配置驱动，regex 档与旧实现完全一致）。
 */
@Component
public class OverlapReranker implements Reranker {

    private final Tokenizer tokenizer;

    public OverlapReranker(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    @Override
    public List<ScoredDocument> rerank(String query, List<ScoredDocument> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Set<String> queryTokens = new HashSet<>(tokenizer.tokenize(query));
        List<ScoredDocument> scored = new ArrayList<>(documents.size());
        for (ScoredDocument d : documents) {
            ScoredDocument copy = ScoredDocument.builder()
                    .docId(d.getDocId())
                    .sourceType(d.getSourceType())
                    .title(d.getTitle())
                    .url(d.getUrl())
                    .chunkId(d.getChunkId())
                    .content(d.getContent())
                    .retrievalScore(d.getRetrievalScore())
                    .finalScore(score(queryTokens, d.getContent()))
                    .metadata(d.getMetadata())
                    .build();
            scored.add(copy);
        }
        // 稳定排序：与原 Collections.sort 语义一致，同分保持原顺序
        scored.sort(Comparator.comparingDouble(ScoredDocument::getFinalScore).reversed());
        return scored.stream().limit(Math.max(0, topK)).toList();
    }

    /**
     * Document 形态的等价入口：内部转 ScoredDocument 打分，按 docId 映射回原 Document。
     * 算法与 rerank 完全一致。
     */
    public List<Document> rerankDocuments(String query, List<Document> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<ScoredDocument> scored = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document d = documents.get(i);
            scored.add(ScoredDocument.builder()
                    .docId("doc-" + i)
                    .content(d.getText())
                    .retrievalScore(readScore(d, i))
                    .finalScore(0.0)
                    .metadata(d.getMetadata())
                    .build());
        }
        List<ScoredDocument> ranked = rerank(query, scored, documents.size());
        return ranked.stream()
                .limit(Math.max(0, topK))
                .map(s -> documents.get(Integer.parseInt(s.getDocId().substring(4))))
                .toList();
    }

    private double score(Set<String> queryTokens, String content) {
        Set<String> docTokens = new HashSet<>(tokenizer.tokenize(content));
        if (docTokens.isEmpty()) {
            return 0.0;
        }
        long overlap = queryTokens.stream().filter(docTokens::contains).count();
        return (double) overlap / docTokens.size();
    }

    private double readScore(Document d, int index) {
        Object raw = d.getMetadata().get("score");
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        // 无显式分数时按排名衰减，保序即可
        return 1.0 / (index + 1);
    }

    @Override
    public String getName() {
        return "overlap";
    }
}

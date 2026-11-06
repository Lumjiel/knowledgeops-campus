package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.config.properties.RagProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 三级重排管线（spec-B4）：召回候选 → 粗排（overlap|bm25）→ 精排（RRF）。
 * 精排超时降级：返回粗排 top finalTopK 并标 degraded=true。
 * 召回本身由调用方完成（VectorStore.similaritySearch），本管线只处理候选集。
 */
@Component
public class RerankPipeline {

    private final RagProperties ragProperties;
    private final OverlapReranker overlapReranker;
    private final Bm25Reranker bm25Reranker;
    private final RrfReranker rrfReranker;

    private final GteReranker gteReranker;

    private final ExecutorService fineExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "rerank-fine");
        thread.setDaemon(true);
        return thread;
    });

    public RerankPipeline(RagProperties ragProperties,
                          OverlapReranker overlapReranker,
                          Bm25Reranker bm25Reranker,
                          RrfReranker rrfReranker,
                          GteReranker gteReranker) {
        this.ragProperties = ragProperties;
        this.overlapReranker = overlapReranker;
        this.bm25Reranker = bm25Reranker;
        this.rrfReranker = rrfReranker;
        this.gteReranker = gteReranker;
    }

    public PipelineResult rerank(String query, List<Document> candidates, int finalTopK) {
        return rerank(query, candidates, finalTopK, null);
    }

    /**
     * fineOverride: null = 按 ragProperties.fineReranker 配置走；"none" = 强制跳过精排（只粗排，零 API 成本）。
     */
    public PipelineResult rerank(String query, List<Document> candidates, int finalTopK, String fineOverride) {
        if (candidates == null || candidates.isEmpty()) {
            return new PipelineResult(List.of(), false, 0, 0, 0, 0);
        }
        List<ScoredDocument> scored = toScored(candidates);

        long coarseStart = System.nanoTime();
        Reranker coarse = "bm25".equalsIgnoreCase(ragProperties.getReranker())
                ? bm25Reranker
                : overlapReranker;
        List<ScoredDocument> coarseRanked = coarse.rerank(query, scored, ragProperties.getCoarseTopK());
        long coarseMs = (System.nanoTime() - coarseStart) / 1_000_000;

        List<ScoredDocument> fineRanked;
        boolean degraded = false;
        long fineMs = 0;
        boolean isCe = "gte".equalsIgnoreCase(ragProperties.getFineReranker());
        String effectiveFine = fineOverride != null ? fineOverride : ragProperties.getFineReranker();
        boolean fineEnabled = !"none".equalsIgnoreCase(effectiveFine)
                && ("rrf".equalsIgnoreCase(effectiveFine) || "gte".equalsIgnoreCase(effectiveFine))
                && coarseRanked.size() > 1;
        if (!fineEnabled) {
            fineRanked = coarseRanked;
        } else {
            List<ScoredDocument> byVector = new ArrayList<>(scored);
            byVector.sort(Comparator.comparingDouble(ScoredDocument::getRetrievalScore).reversed());
            long fineStart = System.nanoTime();
            try {
                if (isCe) {
                    // Cross-Encoder 精排：粗排候选成对送 gte-rerank 深度打分
                    fineRanked = CompletableFuture
                            .supplyAsync(() -> gteReranker.rerank(query, coarseRanked, finalTopK), fineExecutor)
                            .orTimeout(ragProperties.getRerankBudgetMs(), TimeUnit.MILLISECONDS)
                            .join();
                } else {
                fineRanked = CompletableFuture
                        .supplyAsync(() -> rrfReranker.rerankFusion(byVector, coarseRanked, finalTopK), fineExecutor)
                        .orTimeout(ragProperties.getRerankBudgetMs(), TimeUnit.MILLISECONDS)
                        .join();
                }
            } catch (CompletionException ex) {
                // 超时或精排异常：跳过精排，降级返回粗排结果
                degraded = true;
                fineRanked = coarseRanked.stream().limit(Math.max(0, finalTopK)).toList();
            }
            fineMs = (System.nanoTime() - fineStart) / 1_000_000;
        }

        List<Document> documents = fineRanked.stream()
                .limit(Math.max(0, finalTopK))
                .map(this::toDocument)
                .toList();
        return new PipelineResult(documents, degraded, scored.size(), coarseRanked.size(), coarseMs, fineMs);
    }

    private List<ScoredDocument> toScored(List<Document> documents) {
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
        return scored;
    }

    private double readScore(Document d, int index) {
        Object raw = d.getMetadata().get("score");
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        Object distance = d.getMetadata().get("distance");
        if (distance instanceof Number dist) {
            return Math.max(0.0, 1.0 - dist.doubleValue());
        }
        // 无显式分数时按排名衰减，保序即可
        return 1.0 / (index + 1);
    }

    private Document toDocument(ScoredDocument s) {
        return Document.builder()
                .text(s.getContent() == null ? "" : s.getContent())
                .metadata(s.getMetadata() == null ? java.util.Map.of() : s.getMetadata())
                .build();
    }

    @PreDestroy
    void shutdownFineExecutor() {
        fineExecutor.shutdownNow();
    }

    /**
     * @param documents   管线最终输出（已按 finalTopK 截断）
     * @param degraded    true = 精排超时/异常，返回的是粗排结果
     * @param recallCount 一级召回条数
     * @param coarseCount 二级粗排保留条数
     * @param coarseMs    粗排耗时
     * @param fineMs      精排耗时（降级时为发生超时前的等待时间）
     */
    public record PipelineResult(List<Document> documents,
                                 boolean degraded,
                                 int recallCount,
                                 int coarseCount,
                                 long coarseMs,
                                 long fineMs) {
    }
}

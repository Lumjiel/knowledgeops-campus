/*
 * 混合检索服务：并行聚合向量、关键词、图谱、Web 四路召回，经去重与加权排序后返回 top-K 文档。
 */
package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 混合检索服务：并行发起向量、关键词、图谱、Web 四路召回，对结果做加权融合、指纹去重与降序排序，
 * 返回最终 top-K 文档。
 *
 * <p>设计要点：
 * <ul>
 *   <li>各召回源为阻塞 IO，统一投递到专用守护线程池 {@code hybrid-retrieval} 执行，
 *       避免占用 {@code ForkJoinPool.commonPool()} 导致 JVM 内并行流饥饿；</li>
 *   <li>单源召回超时 {@value #SOURCE_TIMEOUT_SECONDS} 秒，超时或抛出 {@link RuntimeException}
 *       时按空结果降级，不阻断整体检索（见 {@link #retrieveAsync}）;</li>
 *   <li>融合分 = 源内检索分 × 归一化后权重，权重可通过 {@link HybridWeights} 配置并归一化到和为 1.0；</li>
 *   <li>按内容指纹（正文前 200 字符）去重，重复时保留融合分最高的一条（见 {@link #deduplicate}）;</li>
 *   <li>通过 Micrometer 指标 {@code retrieval.hybrid.latency} 记录整体延迟，
 *       以 outcome（success / empty / error）打标，并发布百分位直方图。</li>
 * </ul>
 *
 * <p>线程安全：本类无可变共享状态（检索结果对象在各线程内独立创建与修改），
 * {@code retrievalExecutor} 在 Bean 销毁时通过 {@link #shutdownRetrievalExecutor()} 关闭。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final GraphRetriever graphRetriever;
    private final WebRetriever webRetriever;
    private final MeterRegistry meterRegistry;

    // Retrieval sources perform blocking IO; running them on ForkJoinPool.commonPool()
    // could starve parallel streams elsewhere in the JVM, so use a dedicated pool.
    private final ExecutorService retrievalExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "hybrid-retrieval");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Bean 销毁钩子：立即中断仍在执行的召回任务并关闭专用线程池，
     * 防止应用停机时残留守护线程阻塞 JVM 退出或在半关闭状态下继续发起 IO。
     */
    @PreDestroy
    void shutdownRetrievalExecutor() {
        retrievalExecutor.shutdownNow();
    }

    private static final double VECTOR_WEIGHT = 0.40;
    private static final double KEYWORD_WEIGHT = 0.25;
    private static final double GRAPH_WEIGHT = 0.20;
    private static final double WEB_WEIGHT = 0.15;
    private static final long SOURCE_TIMEOUT_SECONDS = 3;

    /**
     * Retrieve documents using hybrid search with default source weights.
     * Equivalent to calling {@link #retrieve(String, String, String, int, HybridWeights)}
     * with default weights (vector=0.40, keyword=0.25, graph=0.20, web=0.15).
     *
     * @param query    search query
     * @param tenantId tenant identifier for filtering
     * @param chatId   chat/conversation identifier for filtering
     * @param topK     number of top results to return
     * @return hybrid retrieval result with deduplicated, scored documents
     */
    public HybridRetrievalResult retrieve(String query, String tenantId, String chatId, int topK) {
        return retrieve(query, tenantId, chatId, topK, HybridWeights.DEFAULT);
    }

    /**
     * Retrieve documents using hybrid search with configurable per-source weights.
     * Each source runs in parallel via {@link CompletableFuture} and results are
     * merged, deduplicated by content fingerprint, and sorted by final weighted score.
     *
     * Tuning tip: factual/definitional queries benefit from higher vector weight;
     * exact-match/lookup queries benefit from higher keyword weight.
     *
     * @param query    search query
     * @param tenantId tenant identifier for filtering
     * @param chatId   chat/conversation identifier for filtering
     * @param topK     number of top results to return
     * @param weights  per-source weight configuration; weights are normalized to sum to 1.0
     * @return hybrid retrieval result with deduplicated, scored documents
     */
    public HybridRetrievalResult retrieve(String query, String tenantId, String chatId, int topK, HybridWeights weights) {

        // Normalize weights to sum to 1.0
        HybridWeights normalized = weights.normalize();

        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            CompletableFuture<List<ScoredDocument>> vectorFuture = retrieveAsync("vector",
                    () -> vectorRetriever.retrieve(query, tenantId, chatId), normalized.vectorWeight());
            CompletableFuture<List<ScoredDocument>> keywordFuture = retrieveAsync("keyword",
                    () -> keywordRetriever.retrieve(query, tenantId, chatId, topK), normalized.keywordWeight());
            CompletableFuture<List<ScoredDocument>> graphFuture = retrieveAsync("graph",
                    () -> graphRetriever.retrieve(query, tenantId, topK), normalized.graphWeight());
            CompletableFuture<List<ScoredDocument>> webFuture = retrieveAsync("web",
                    () -> webRetriever.retrieve(query, topK), normalized.webWeight());

            List<ScoredDocument> allDocs = Stream.of(vectorFuture, keywordFuture, graphFuture, webFuture)
                    .flatMap(future -> future.join().stream())
                    .toList();

            // Deduplicate by content fingerprint
            List<ScoredDocument> deduped = deduplicate(allDocs);

            // Sort by weighted score descending
            deduped.sort(Comparator.comparingDouble(ScoredDocument::getFinalScore).reversed());

            List<ScoredDocument> top = deduped.stream().limit(topK).toList();

            outcome = top.isEmpty() ? "empty" : "success";
            return new HybridRetrievalResult(top, allDocs.size(), deduped.size());
        } finally {
            sample.stop(Timer.builder("retrieval.hybrid.latency")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /**
     * 就地设置每篇文档的融合分：finalScore = 源内检索分 × 该源权重。
     *
     * @param docs   某一召回源返回的文档列表
     * @param weight 该源在归一化权重中所占比例
     * @return 同一列表（已写入 finalScore），便于在调用处链式使用
     */
    private List<ScoredDocument> applyWeight(List<ScoredDocument> docs, double weight) {
        for (ScoredDocument d : docs) {
            d.setFinalScore(d.getRetrievalScore() * weight);
        }
        return docs;
    }

    /**
     * 将单路召回提交到专用线程池异步执行，并做双重降级：
     * <ul>
     *   <li>任务抛出 {@link RuntimeException} 时记录 warn 日志并返回空列表；</li>
     *   <li>超过 {@value #SOURCE_TIMEOUT_SECONDS} 秒未完成时以空列表超时完成
     *       （{@code completeOnTimeout}，原任务线程不中断）。</li>
     * </ul>
     *
     * @param source    召回源标识，用于日志定位（vector / keyword / graph / web）
     * @param retrieval 阻塞召回逻辑（调用方负责按各自签名适配参数）
     * @param weight    该源的归一化权重
     * @return 必不抛出异常、必在超时内完成的召回 Future
     */
    private CompletableFuture<List<ScoredDocument>> retrieveAsync(String source,
                                                                  Supplier<List<ScoredDocument>> retrieval,
                                                                  double weight) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return applyWeight(retrieval.get(), weight);
            } catch (RuntimeException ex) {
                log.warn("hybrid retrieval source failed: source={}, reason={}", source, ex.toString());
                return List.<ScoredDocument>of();
            }
        }, retrievalExecutor).completeOnTimeout(List.of(), SOURCE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 按内容指纹去重：指纹相同的文档仅保留融合分最高的一条。
     * 使用 {@link LinkedHashMap} 保持首次出现顺序，保证同分场景下结果确定性。
     *
     * @param docs 四路召回合并后的全部文档
     * @return 去重后的新列表（不修改入参）
     */
    private List<ScoredDocument> deduplicate(List<ScoredDocument> docs) {
        Map<String, ScoredDocument> seen = new LinkedHashMap<>();
        for (ScoredDocument d : docs) {
            String fingerprint = fingerprint(d);
            ScoredDocument existing = seen.get(fingerprint);
            if (existing == null || d.getFinalScore() > existing.getFinalScore()) {
                seen.put(fingerprint, d);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * 计算去重指纹：取正文（null 视为空串），将连续空白折叠为单个空格并去首尾空白，
     * 截取前 200 个字符作为指纹。
     *
     * @param d 待计算指纹的文档
     * @return 规范化后的指纹串，永不为 null
     */
    private String fingerprint(ScoredDocument d) {
        String content = d.getContent() != null ? d.getContent() : "";
        // Use first 200 chars as dedup key
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    /**
     * 混合检索结果。
     *
     * @param documents         去重、加权排序并截取后的 top-K 文档
     * @param totalBeforeDedup  四路召回合并后的文档总数（去重前）
     * @param totalAfterDedup   指纹去重后的文档总数（排序截取前）
     */
    public record HybridRetrievalResult(List<ScoredDocument> documents, int totalBeforeDedup,
                                         int totalAfterDedup) {}
}

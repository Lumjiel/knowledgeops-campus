package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private int retrieveTopK = 12;
    private int rerankTopK = 6;
    private double similarityThreshold = 0.45;
    private double temperature = 0.2;
    private Split split = new Split();
    // spec-B：三级重排管线配置（application.yml 未列出的字段全部走代码默认值）
    private String tokenizer = "regex";          // regex | bigram
    private String reranker = "overlap";         // overlap | bm25（粗排，默认 overlap 保 A/B 基线）
    private String fineReranker = "rrf";         // rrf | none（精排）
    private int retrievalTopN = 50;              // 召回条数（管线一级）
    private int coarseTopK = 20;                 // 粗排保留条数（管线二级）
    private long rerankBudgetMs = 800;           // 精排延迟预算，超时降级
    private String sharedCorpusChatId = "shared-corpus"; // 全局共享语料作用域，检索与会话级文档合并召回

    @Data
    public static class Split {
        private int chunkSize = 800;
        private int minChunkSize = 120;
        private int maxNumChunks = 100;
    }
}

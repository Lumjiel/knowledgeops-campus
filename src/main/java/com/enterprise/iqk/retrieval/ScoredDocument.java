package com.enterprise.iqk.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * File: ScoredDocument.java
 * <p>
 * 检索管道内统一流转的打分文档：各召回源产出 {@code retrievalScore}（源内分），
 * 融合层写入 {@code finalScore}（加权融合分），证据评分层再据此计算三维分。
 * {@code HybridRetrievalService}、{@code EvidenceJudgeService} 均以它为输入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoredDocument {
    /** 文档标识，前缀标来源（vec- / kw- / graph-entity- / graph-fact- / web-）。 */
    private String docId;
    /** 来源类型：vector | keyword | graph | web。 */
    private String sourceType;
    /** 标题（文件名 / 实体名 / 三元组文本 / 网页标题）。 */
    private String title;
    /** 来源 URL（仅 web 来源有值）。 */
    private String url;
    /** chunk 标识（chunk-序号 / 实体 ID / 事实 ID）。 */
    private String chunkId;
    /** 正文内容。 */
    private String content;
    /** 源内检索分（0-1，各检索器原始评分）。 */
    private double retrievalScore;
    /** 最终分（0-1，融合层写入：源内分 × 源权重）。 */
    private double finalScore;
    /** 源元数据（chunk_index、entityType、created_at 等，时效性评分读取 created_at/timestamp）。 */
    private Map<String, Object> metadata;
}

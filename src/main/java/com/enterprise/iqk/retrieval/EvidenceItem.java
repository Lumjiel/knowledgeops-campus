package com.enterprise.iqk.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * File: EvidenceItem.java
 * <p>
 * 证据评分结果（对应 docs/architecture-hybrid-retrieval.md 返回结构中的
 * evidence 元素）：在文档基础上附三维评分、综合分与可读理由。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceItem {
    /** 来源类型：vector | keyword | graph | web。 */
    private String sourceType;
    /** 标题。 */
    private String title;
    /** 来源 URL（web 来源）。 */
    private String url;
    /** chunk 标识。 */
    private String chunkId;
    /** 综合评分（0-1）：relevance×0.50 + authority×0.30 + timeliness×0.20。 */
    private double score;
    /** 可读评分理由（如「向量语义匹配，相关度92%…」）。 */
    private String reason;
    /** 相关性分（0-1，权重 0.50）。 */
    private double relevanceScore;
    /** 权威性分（0-1，权重 0.30）。 */
    private double authorityScore;
    /** 时效性分（0-1，权重 0.20）。 */
    private double timelinessScore;
    /** 正文预览（折叠空白后截断 180 字符）。 */
    private String snippet;
}

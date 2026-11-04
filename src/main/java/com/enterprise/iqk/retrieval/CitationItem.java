package com.enterprise.iqk.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * File: CitationItem.java
 * <p>
 * 编号引用项（对应 docs/architecture-hybrid-retrieval.md「引用溯源」的 JSON 结构）：
 * 由 {@code CitationService} 从证据生成，随答案返回并渲染「引用来源」尾注。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationItem {
    /** 引用编号（从 1 起，对应答案中的 [1][2] 标记）。 */
    private int index;
    /** 来源类型：vector | keyword | graph | web。 */
    private String sourceType;
    /** 来源标题（文件名 / 网页标题等）。 */
    private String title;
    /** 来源 URL。 */
    private String url;
    /** chunk 标识。 */
    private String chunkId;
    /** 可信度（0-1，取证据综合分）。 */
    private double confidence;
    /** 引文摘录（取证据 snippet）。 */
    private String excerpt;

    /** 兼容别名：id 即 index。 */
    public int getId() {
        return index;
    }

    /** 兼容别名：source 即 sourceType。 */
    public String getSource() {
        return sourceType;
    }

    /** 兼容别名：snippet 即 excerpt。 */
    public String getSnippet() {
        return excerpt;
    }
}

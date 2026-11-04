package com.enterprise.iqk.retrieval;

/**
 * File: HybridWeights.java
 * <p>
 * 混合检索各召回源的权重配置（docs/architecture-hybrid-retrieval.md「融合策略」：
 * 加权评分）——默认 vector 0.40 / keyword 0.25 / graph 0.20 / web 0.15，
 * 另有语义侧重 / 关键词侧重 / 均衡三组预设，均可经 {@link #normalize} 归一化。
 *
 * @param vectorWeight  向量语义检索权重
 * @param keywordWeight 关键词检索权重
 * @param graphWeight   知识图谱检索权重
 * @param webWeight     外部搜索权重
 */
public record HybridWeights(double vectorWeight, double keywordWeight,
                             double graphWeight, double webWeight) {

    /** 默认权重（与架构文档图中的 0.40/0.25/0.20/0.15 一致）。 */
    public static final HybridWeights DEFAULT = new HybridWeights(0.40, 0.25, 0.20, 0.15);
    /** 语义侧重预设：事实/定义类查询适用。 */
    public static final HybridWeights SEMANTIC = new HybridWeights(0.55, 0.20, 0.15, 0.10);
    /** 关键词侧重预设：精确术语/查找类查询适用。 */
    public static final HybridWeights KEYWORD = new HybridWeights(0.20, 0.50, 0.15, 0.15);
    /** 四源均等预设。 */
    public static final HybridWeights BALANCED = new HybridWeights(0.25, 0.25, 0.25, 0.25);

    /** 按四源顺序构造权重组。 */
    public static HybridWeights of(double vector, double keyword, double graph, double web) {
        return new HybridWeights(vector, keyword, graph, web);
    }

    /** 语义侧重预设的便捷取值。 */
    public static HybridWeights semantic() { return SEMANTIC; }
    /** 关键词侧重预设的便捷取值。 */
    public static HybridWeights keyword() { return KEYWORD; }
    /** 均衡预设的便捷取值。 */
    public static HybridWeights balanced() { return BALANCED; }

    /**
     * 归一化：权重和 ≤ 0 时回退 {@link #BALANCED}；已和为 1（误差 1e-9 内）返回自身；
     * 否则按比例缩放至和为 1.0（融合分因此落在可比较的 0-1 区间）。
     */
    public HybridWeights normalize() {
        double sum = vectorWeight + keywordWeight + graphWeight + webWeight;
        if (sum <= 0) return BALANCED;
        if (Math.abs(sum - 1.0) < 1e-9) return this;
        return new HybridWeights(vectorWeight / sum, keywordWeight / sum, graphWeight / sum, webWeight / sum);
    }
}

package com.enterprise.iqk.retrieval;

/**
 * BM25 语料统计（spec-B3）：idf / docCount / avgDocLength。
 */
public interface CorpusStats {

    double idf(String term);

    int docCount();

    double avgDocLength();
}

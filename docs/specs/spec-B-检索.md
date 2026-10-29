# 规格 B · 检索重构（三级重排管线 + 评测）

> 给执行者读。**这是简历数字的唯一来源，做不对后面全白搭。**
> 前置：规格 A 已通过验收（`mvn test` 绿、`npm run build` 过）。

---

## B0 · 硬约束

- 不改 `Reranker` 接口签名（原有公共契约）
- 不改 `HybridWeights`、`AnswerFeedbackService`（对照基线）
- 不改任何**已存在**的测试
- 不新增依赖（`pom.xml` 的 `git diff` 必须为空）→ **所以不能引 HanLP / jieba**
- 不改 `citations` / `citationCards` 字段（A 已定）

---

## B1 · 抽 `OverlapReranker`（行为逐字节不变）

`rag/RagAnswerService.java:151-166` 有三个私有方法：`rerank` / `scoreDoc` / `tokenize`。

搬进 `retrieval/OverlapReranker implements Reranker`：

- 额外提供 `rerankDocuments(String query, List<Document> docs, int topK)`，逻辑原样搬
- 接口方法 `rerank(String, List<ScoredDocument>, int)` 做类型转换后转调
- 算法一个字不改：`Set` 存 token、`overlap / docTokens.size()`、`split("[^\\p{L}\\p{Nd}]+")`、空 token 返回 0.0
- `RagAnswerService.rerankWithMetrics` 的 Micrometer Timer（`rag.rerank.latency`）必须保留
- 原私有方法删除，改为注入调用

**锁基线测试** `OverlapRerankerTest` 四条：
1. 长文档被压低（**故意断言当前缺陷行为**，注释 `// 锁基线：B3 修复后需更新`）
2. 空文档返回 0.0
3. 零重叠顺序不变
4. 中英混排能切出 token（中文仍整句一个 token，注释标明）

**反向验证**：把 `scoreDoc` 的除数去掉（`return overlap`），确认第 1 条变红，贴输出，改回来贴全绿。

---

## B2 · 中文分词（不引依赖，用 bigram）

pom 里没有分词库，且不许加依赖。**用自写二元切分**：

```java
// 规则：连续汉字串 → 每 2 字一个 token（长度 1 的串保留原样）；英文数字按原正则切
// "旷课50学时" → ["旷课","课5","50","学时"]（标点先归一化为空格）
```

放在 `retrieval/Tokenizer.java`（接口）+ `retrieval/BigramTokenizer.java` + `retrieval/RegexTokenizer.java`（保留旧实现做 A/B）。

替换 4 处调用点：
- `rag/RagAnswerService.java:171`
- `retrieval/GraphRetriever.java:85`
- `retrieval/EvidenceJudgeService.java:73`
- `retrieval/KeywordRetriever.java:92`

**不许动** `service/AnswerFeedbackService.java:127`（它用的是 `\p{IsHan}`，对的）。

配置：`RagProperties` 加 `private String tokenizer = "regex";`（`regex | bigram`），默认 `regex` 保证行为不变。

**反向验证**：把 `BigramTokenizer.tokenize("旷课")` 临时改成返回单元素集合，确认「中文能切出 >1 token」的测试变红，还原。

---

## B3 · `Bm25Reranker`（粗排）

- `retrieval/CorpusStats.java` 接口：`double idf(String term)`、`int docCount()`、`double avgDocLength()`
- 实现从**本次候选集**统计 DF，类里标 `// simplified: DF 来自候选集，非全量语料`
- `retrieval/Bm25Reranker implements Reranker`：k1=1.2, b=0.75，分词复用 `Tokenizer`
- 配置：`RagProperties` 加 `private String reranker = "overlap";`（`overlap | bm25`）

测试 `Bm25RerankerTest` 五条：词频高者分高／长文档不被过度惩罚／低频词权重大于高频词／空候选返回空／纯停用词不崩。

**反向验证**：IDF 临时恒返回 `1.0`，确认「低频词权重大于高频词」变红，还原。

---

## B4 · 三级管线 + 延迟预算 + 降级

新建 `retrieval/RerankPipeline.java`：

```
召回（VectorStore.similaritySearch，topN=50）
  → 粗排（Bm25Reranker，50 → 20）
  → 精排（可插拔；第一期用 RrfReranker，20 → 6）
  → 超时则跳过精排，返回粗排结果 + degraded=true
```

- `RagProperties` 加：`retrievalTopN=50`、`coarseTopK=20`、`rerankBudgetMs=800`
- 精排接口 `Reranker`，第一期实现 `RrfReranker`（倒数排名融合：把 BM25 排名与向量相似度排名做 RRF，纯本地，无外部调用）
- **不要用 LLM 做精排**（会引入 API 依赖，半夜跑必挂）
- 降级：`CompletableFuture` + `orTimeout(budgetMs)`，超时返回粗排 top6 并标 `degraded=true`

测试 `RerankPipelineTest` 三条：
1. 正常路径：输出 6 条，`degraded=false`
2. 超时降级：精排 mock 睡 900ms，输出 6 条，`degraded=true`，且内容等于粗排 top6
3. 空候选：返回空，不抛异常

**反向验证**：把 `orTimeout` 去掉，确认超时那条测试挂住/变红（说明预算真的生效），还原。

---

## B5 · 评测脚本（出数字）

新建 `eval/` 目录（**这是新目录，允许**）：

- `eval/dataset.jsonl`：20 条，格式
  ```json
  {"id":1,"query":"旷课 50 学时会被怎么处分？","gold_snippets":["一学期旷课累计达到五十学时"],"cross_file":true}
  ```
  **判据用答案原文片段匹配，不用 chunk id**（chunk_index 随切片参数变化，不可比）
- `eval/run_eval.py`：Python 脚本，通过 HTTP 调 `/api/react/chat`（或实际问答接口），取返回的 `citationCards[].snippet`，与 `gold_snippets` 做归一化子串匹配，算 Recall@3 / Recall@6 / NDCG@6
- 输出 `eval/raw.json` + `eval/report.md`（4 个配置各一行：`regex+overlap` / `bigram+overlap` / `bigram+bm25` / `bigram+bm25+rrf`）

**注意**：20 条 query 需要人工标 `gold_snippets`。执行者只负责**搭脚本 + 用现有 `语料/评测集.md` 里的问题做占位**，标注由人补。**不许自己编标准答案。**

---

## B6 · 验收

### 机器可判

| # | 命令 | 期望 |
|---|---|---|
| 1 | `mvn -q compile` | 退出码 0 |
| 2 | `mvn -q test` | BUILD SUCCESS，Failures 0，skipped 0 |
| 3 | `grep -c "implements Reranker" .../OverlapReranker.java .../Bm25Reranker.java .../RrfReranker.java` | 各 1 |
| 4 | `grep -cE "private double scoreDoc\|private Set<String> tokenize" .../RagAnswerService.java` | 0 |
| 5 | `grep -c "rag.rerank.latency" .../RagAnswerService.java` | 1 |
| 6 | `grep -rn 'p{L}' src/main/java --include="*.java" \| grep -v IsHan \| grep -v RegexTokenizer` | 0 行 |
| 7 | `ls eval/run_eval.py eval/raw.json eval/report.md` | 三个都存在 |
| 8 | `git diff --stat pom.xml` | 空 |

### 人工抽查

- `eval/report.md` 里 4 个配置的数字是否**有差异**（全一样说明开关没生效）
- `Bm25Reranker` 里有没有真的 `Math.log`（恒返回常数的 IDF 是假的）

---

## B7 · 禁止

- ❌ 加任何 Maven 依赖
- ❌ 用 LLM 做精排
- ❌ 编造评测集的 `gold_snippets`
- ❌ 改已有测试
- ❌ 把 `reranker` 默认值改成 `bm25`（默认必须保持 `overlap`，A/B 才有基线）

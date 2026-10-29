# RAG 检索评测报告（spec-B5）

> 判据：citationCards[].snippet 与 gold_snippets 归一化子串匹配；
> gold_snippets 未标注的用例跳过，不计入指标。**本报告由 eval/raw.json 生成，禁止手改数字。**

| 配置 | Recall@3 | Recall@6 | NDCG@6 | 平均延迟 | 有效用例 | 状态 |
|---|---|---|---|---|---|---|
| regex+overlap | 待实测 | 待实测 | 待实测 | — | 0/20 | 服务部署后执行 |
| bigram+overlap | 待实测 | 待实测 | 待实测 | — | 0/20 | 服务部署后执行 |
| bigram+bm25 | 待实测 | 待实测 | 待实测 | — | 0/20 | 服务部署后执行 |
| bigram+bm25+rrf | 待实测 | 待实测 | 待实测 | — | 0/20 | 服务部署后执行 |

## 执行步骤（服务器阶段）

1. 部署 lite 版并完成种子导入（见 docs/deploy-云服务器手册.md）
2. 对每个配置重启 app（环境变量 APP_SEED_CHATID 同步，语料不变）：
   - `regex+overlap`：`RAG_TOKENIZER=regex` `RAG_RERANKER=overlap` `RAG_FINE_RERANKER=none`
   - `bigram+overlap`：`RAG_TOKENIZER=bigram` `RAG_RERANKER=overlap` `RAG_FINE_RERANKER=none`
   - `bigram+bm25`：`RAG_TOKENIZER=bigram` `RAG_RERANKER=bm25` `RAG_FINE_RERANKER=none`
   - `bigram+bm25+rrf`：`RAG_TOKENIZER=bigram` `RAG_RERANKER=bm25` `RAG_FINE_RERANKER=rrf`
3. 每个配置执行 `python eval/run_eval.py run --label <配置名> ...`
4. `python eval/run_eval.py report` 重新生成本文件

## 待人工补标

- `eval/dataset.jsonl` 的 `gold_snippets`（答案原文片段，翻 PDF 确认，不许编造）

# eval/ —— 检索层离线评测（Hit@K / MRR）

与 `evaluation/`（后端评测模块：数据集/运行/综合分的 Spring 实现）不同，本目录是**轻量脚本**，
直接打 `/ai/retrieval/search` 只读端点，只测「多路召回 + 两段精排」、不进 LLM——
用于把检索质量问题与生成质量问题分开定位（面试叙事见 `docs/rag-面试叙事.md` 第六节）。

## 资产

- `annotations-handbook-v1.json`：28 条标注（LLM 从《学生手册》语料反向生成，含 expected_text，
  支持跨 chunk 粒度的文本包含匹配）
- `run_retrieval_eval.py`：Hit@K / MRR 评测脚本

## 用法

```bash
JWT=$(curl -s -X POST $BASE/auth/token -H "X-API-Key: $KEY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
BASE=http://127.0.0.1:8080 JWT=$JWT python3 eval/run_retrieval_eval.py \
    --annotations eval/annotations-handbook-v1.json --topk 6
```

## 已跑结果（chunk=800，精排可切换）

| 精排 | Hit@5 | MRR | e2e p50 | 说明 |
|---|---|---|---|---|
| RRF | 1.000 | 0.827 | 191ms | 排名融合 |
| gte-rerank-v2 (CE) | 1.000 | **0.982** | 359ms | 生产采用，fine 中位 166ms < 800ms 预算 |

切换精排：服务器 `.env` 的 `RAG_FINE_RERANKER=rrf|gte`，`docker compose up -d app`。

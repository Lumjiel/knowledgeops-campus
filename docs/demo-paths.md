# V1 演示路径

三条固定演示链路，覆盖有章的全部核心能力。每条链路 3-5 分钟可完成。

## 链路一：DeepResearch 行业研究

**展示能力**：AgentWorkflowEngine → ResearchPlannerAgent → 混合检索 → EvidenceJudgeAgent → ReportWriterAgent

**演示步骤**：

```bash
# 1. 启动服务
./scripts/demo.sh

# 2. 创建研究任务
curl -X POST http://localhost:8080/ai/research/tasks \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: public" \
  -d '{
    "topic": "2025年AI Agent在企業服務領域的應用趨勢",
    "enableWebSearch": false,
    "enableRagSearch": true,
    "enableGraphSearch": true,
    "maxSearchRounds": 3
  }'

# 3. 查询任务状态与步骤
curl http://localhost:8080/ai/research/tasks/{taskId} \
  -H "X-Tenant-Id: public"

# 4. 获取研究报告
curl http://localhost:8080/ai/research/tasks/{taskId}/report \
  -H "X-Tenant-Id: public"
```

**验证点**：
| 检查项 | 预期结果 |
|---|---|
| 任务创建 | 返回 taskId，status=CREATED→PLANNING→...→DONE |
| 主题拆解 | agent_step 中 ResearchPlannerAgent 输出 3-5 个子问题 |
| 检索召回 | 每个子问题触发混合检索（vector + keyword + graph） |
| 证据评分 | evidence 数组中每条记录带 relevanceScore/authorityScore/timelinessScore |
| 报告生成 | final_output 包含 Executive Summary、Key Findings、Detailed Analysis |
| 事件溯源 | GET /tasks/{taskId}/events 返回完整 STATE_CHANGED/STEP_STARTED/STEP_COMPLETED 序列 |

## 链路二：检索层精度实验（精排 A/B）

**展示能力**：独立检索端点 → 多路召回 + 两段重排 → Hit@5 / MRR / p50 延迟。
不进 LLM，先把「检索层的锅」和「生成层的锅」分开定位。

**演示步骤**：

```bash
BASE=http://127.0.0.1:8080

# 1. 取一个免凭证的游客 token（也可换成 API Key 换 JWT）
JWT=$(curl -s -X POST $BASE/auth/guest | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

# 2. 跑 28 条标注集的检索评测（只打 /ai/retrieval/search）
BASE=$BASE JWT=$JWT python3 eval/run_retrieval_eval.py \
    --annotations eval/annotations-handbook-v1.json --topk 6

# 3. 切换精排策略再跑一次：服务器 .env 里 RAG_FINE_RERANKER=rrf|gte
```

**预期结果**（同一生产链路实测）：

| 精排 | Hit@5 | MRR | e2e p50 |
|---|---|---|---|
| RRF（排名融合） | 1.000 | 0.827 | 191ms |
| gte-rerank-v2（Cross-Encoder） | 1.000 | **0.982** | 359ms |

**验证点**：
| 检查项 | 预期结果 |
|---|---|
| 召回命中 | 每条标注的 expected_text 能在 Top-6 chunk 中匹配 |
| 精排增益 | MRR 明显高于 RRF，Hit@5 不下降 |
| 延迟预算 | fine 精排中位 < 800ms，超时自动降级 |
| 与生成层解耦 | 该端点不调用 LLM，结果可复现 |

> 标注集由 LLM 从《学生手册》语料反向生成，带 expected_text，支持跨 chunk 粒度的文本包含匹配；
> 脚本与说明见 `eval/README.md`。

## 链路三：知识库问答（混合检索 + 引用溯源）

**展示能力**：PDF 上传 → 异步入库 → VectorRetriever + KeywordRetriever + GraphRetriever → EvidenceJudgeService → CitationService

**演示步骤**：

```bash
# 1. 上传 PDF 到知识库
curl -X POST http://localhost:8080/ai/pdf/upload/demo-chat-1 \
  -H "X-Tenant-Id: public" \
  -F "file=@demo-data/njit_student_handbook_2023.pdf"

# 2. 等待入库完成（查询 ingestion job 状态）
curl http://localhost:8080/ingestion/jobs?chatId=demo-chat-1 \
  -H "X-Tenant-Id: public"

# 3. 混合检索问答
curl -X POST http://localhost:8080/ai/rag/search \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: public" \
  -d '{
    "prompt": "文档中提到的核心概念是什么",
    "chatId": "demo-chat-1"
  }'

# 4. 通过工作流接口（带 trace）
curl -X POST http://localhost:8080/ai/workflow/react/chat \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: public" \
  -d '{
    "prompt": "文档中提到的核心概念是什么",
    "chatId": "demo-chat-1",
    "modelProfile": "balanced"
  }'
```

**返回结构示例**：
```json
{
  "answer": "根據文檔分析，核心概念包括...",
  "citations": [
    {"index": 1, "sourceType": "vector", "title": "sample.pdf", "confidence": 0.92}
  ],
  "evidence": [
    {
      "sourceType": "vector",
      "title": "sample.pdf",
      "chunkId": "chunk-3",
      "score": 0.86,
      "relevanceScore": 0.92,
      "authorityScore": 0.75,
      "timelinessScore": 0.70,
      "reason": "向量语义匹配，相关度92%，权威度75%，时效度70%"
    }
  ],
  "traceId": "trace-a1b2c3d4",
  "memoryUsed": []
}
```

**验证点**：
| 检查项 | 预期结果 |
|---|---|
| PDF 上传 | 返回 jobId，状态 pending→running→success |
| 向量检索 | VectorRetriever 返回 topK 文档，score>0.35（门控阈值） |
| 关键词检索 | KeywordRetriever 返回标题/内容关键词匹配结果 |
| 图谱检索 | GraphRetriever 返回 kg_entity 匹配实体和一跳邻居 |
| 融合去重 | HybridRetrievalService 合并四路结果并去重 |
| 证据评分 | 每条 evidence 带三维评分和中文理由 |
| 引用溯源 | citations 数组带 index/sourceType/chunkId/confidence |
| 工作流追踪 | agent_task→agent_step→agent_event 完整链路可查 |

## 异常场景验证

| 场景 | 预期行为 |
|---|---|
| 知识库无匹配 | answer="没有在当前知识库中检索到可用内容"，pipelineOutcome=empty |
| 模型超时 | Resilience4j TimeLimiter 触发，返回降级回答 |
| 工具调用失败 | observation 中 status=error，不阻断流程 |
| 用户中断生成 | SSE stream 在 STOP 事件后正常关闭 |
| 重复提交 | ingestion 幂等键去重，不重复入库 |

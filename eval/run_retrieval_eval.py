#!/usr/bin/env python3
"""检索层评测：Hit@K / MRR。

用法（在能访问后端的环境）:
  JWT=<token> BASE=http://127.0.0.1:8080 python3 eval/run_retrieval_eval.py \
      --annotations eval/annotations-handbook-v1.json --topk 10

标注集构造：从 shared-corpus 均匀抽样 chunk，LLM 反向生成学生视角问题，
记录 expected_chunk_index（LLM 生成标注，非人工精标，结论看相对趋势）。
"""
import argparse, json, os, re, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--annotations", default="eval/annotations-handbook-v1.json")
ap.add_argument("--topk", type=int, default=10)
ap.add_argument("--hitk", type=int, default=5)
args = ap.parse_args()

base = os.environ.get("BASE", "http://127.0.0.1:8080")
jwt = os.environ.get("JWT", "")
anns = json.load(open(args.annotations))

hits = 0
mrr = 0.0
misses = []
for a in anns:
    body = json.dumps({"prompt": a["question"], "chatId": "eval-harness", "topK": args.topk}).encode()
    req = urllib.request.Request(base + "/ai/retrieval/search", data=body,
        headers={"Authorization": "Bearer " + jwt, "Content-Type": "application/json"})
    d = json.load(urllib.request.urlopen(req, timeout=60))
    rank = None
    expected_text = a.get("expected_text", "")
    for doc in d.get("documents") or []:
        md = doc.get("metadata") or {}
        if md.get("chunk_index") == a["expected_chunk_index"]:
            rank = doc["rank"]
            break
        # 跨切法匹配：不同 chunk 粒度下编号会变，用文本包含关系判定
        content = re.sub(r"\s+", "", doc.get("content") or "")
        if expected_text and content and (content in expected_text or expected_text[:200] in content):
            rank = doc["rank"]
            break
    if rank and rank <= args.hitk:
        hits += 1
    mrr += (1.0 / rank) if rank else 0.0
    if rank is None or rank > args.hitk:
        misses.append({"chunk": a["expected_chunk_index"], "page": a["expected_page"], "rank": rank, "q": a["question"]})

n = len(anns)
print(f"n={n}  Hit@{args.hitk}={hits/n:.3f}  MRR={mrr/n:.3f}")
for m in misses:
    print("miss:", m)

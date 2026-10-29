#!/usr/bin/env python3
"""RAG 检索评测脚本（spec-B5）。

用法（单配置一轮）：
    python eval/run_eval.py run --base-url http://localhost:8080 \
        --token "<JWT 或留空用 X-API-Key>" --api-key "<演示 API Key>" \
        --label "regex+overlap" --dataset eval/dataset.jsonl --out eval/raw.json

汇总生成报告：
    python eval/run_eval.py report --raw eval/raw.json --out eval/report.md

判据：citationCards[].snippet 与 gold_snippets 做归一化子串匹配（去空白/全半角），
不用 chunk id（chunk_index 随切片参数变化，不可比）。
服务不可达时逐条记录 error，不伪造数字。
"""

import argparse
import json
import math
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

CONFIGS = ["regex+overlap", "bigram+overlap", "bigram+bm25", "bigram+bm25+rrf"]


def normalize(text: str) -> str:
    """归一化：去空白、全角转半角、小写。"""
    if not text:
        return ""
    table = str.maketrans("，。！？；：（）【】“”‘’ＡＢＣａｂｃ１２３", ",.!?;:()[]\"\"''ABCabc123")
    return re.sub(r"\s+", "", str(text).translate(table)).lower()


def call_chat(base_url: str, query: str, token: str, api_key: str, chat_id: str, timeout: int = 60):
    url = base_url.rstrip("/") + "/api/react/chat"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if api_key:
        headers["X-API-Key"] = api_key
    body = json.dumps({"prompt": query, "chatId": chat_id}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    started = time.time()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return payload, (time.time() - started) * 1000


def hit_at(snippets: list, gold_list: list, top_n: int) -> bool:
    """前 top_n 张卡片的 snippet 是否含任一 gold 片段（归一化子串匹配）。"""
    for snippet in snippets[:top_n]:
        norm = normalize(snippet)
        for gold in gold_list:
            g = normalize(gold)
            if g and g in norm:
                return True
    return False


def ndcg_at6(snippets: list, gold_list: list) -> float:
    """二值相关性的 NDCG@6：命中 gold 的卡片按位置折损计增益。"""
    if not gold_list:
        return 0.0
    dcg = 0.0
    for rank, snippet in enumerate(snippets[:6], start=1):
        norm = normalize(snippet)
        if any(normalize(g) and normalize(g) in norm for g in gold_list):
            dcg += 1.0 / math.log2(rank + 1)
    ideal = sum(1.0 / math.log2(i + 1) for i in range(1, min(6, len(gold_list)) + 1))
    return dcg / ideal if ideal > 0 else 0.0


def run_cmd(args) -> int:
    dataset_path = Path(args.dataset)
    cases = [json.loads(line) for line in dataset_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    raw_path = Path(args.out)
    raw = json.loads(raw_path.read_text(encoding="utf-8")) if raw_path.exists() else {"runs": []}
    if not isinstance(raw, dict) or "runs" not in raw:
        raw = {"runs": []}

    results = []
    for case in cases:
        gold = case.get("gold_snippets") or []
        entry = {"id": case.get("id"), "query": case.get("query"), "gold_pending": not gold}
        if not gold:
            entry["status"] = "skipped"  # gold_snippets 未人工标注，不计入指标
            results.append(entry)
            continue
        try:
            payload, latency_ms = call_chat(args.base_url, case["query"], args.token, args.api_key, case.get("chatId", "campus-rules"))
            cards = payload.get("citationCards") or []
            snippets = [c.get("snippet", "") for c in cards if isinstance(c, dict)]
            entry.update({
                "status": "ok",
                "latencyMs": round(latency_ms, 1),
                "hit3": hit_at(snippets, gold, 3),
                "hit6": hit_at(snippets, gold, 6),
                "ndcg6": round(ndcg_at6(snippets, gold), 4),
                "citationCount": len(cards),
            })
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            entry.update({"status": "error", "error": str(exc)[:200]})
        results.append(entry)
        time.sleep(args.interval / 1000.0)

    raw["runs"] = [r for r in raw["runs"] if r.get("label") != args.label]
    ok_cases = [r for r in results if r.get("status") == "ok"]
    raw["runs"].append({
        "label": args.label,
        "ranAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "total": len(results),
        "okCount": len(ok_cases),
        "errorCount": sum(1 for r in results if r.get("status") == "error"),
        "skippedCount": sum(1 for r in results if r.get("status") == "skipped"),
        "recall3": round(sum(1 for r in ok_cases if r["hit3"]) / len(ok_cases), 4) if ok_cases else None,
        "recall6": round(sum(1 for r in ok_cases if r["hit6"]) / len(ok_cases), 4) if ok_cases else None,
        "ndcg6": round(sum(r["ndcg6"] for r in ok_cases) / len(ok_cases), 4) if ok_cases else None,
        "avgLatencyMs": round(sum(r["latencyMs"] for r in ok_cases) / len(ok_cases), 1) if ok_cases else None,
        "cases": results,
    })
    raw_path.write_text(json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[{args.label}] ok={len(ok_cases)} err={raw['runs'][-1]['errorCount']} "
          f"skipped={raw['runs'][-1]['skippedCount']} -> {raw_path}")
    return 0


def report_cmd(args) -> int:
    raw_path = Path(args.raw)
    raw = json.loads(raw_path.read_text(encoding="utf-8")) if raw_path.exists() else {"runs": []}
    runs = {r.get("label"): r for r in raw.get("runs", [])}
    lines = [
        "# RAG 检索评测报告（spec-B5）",
        "",
        "> 判据：citationCards[].snippet 与 gold_snippets 归一化子串匹配；",
        "> gold_snippets 未标注的用例跳过，不计入指标。**本报告由 eval/raw.json 生成，禁止手改数字。**",
        "",
        "| 配置 | Recall@3 | Recall@6 | NDCG@6 | 平均延迟 | 有效用例 | 状态 |",
        "|---|---|---|---|---|---|---|",
    ]
    for label in CONFIGS:
        run = runs.get(label)
        if not run or run.get("recall3") is None:
            lines.append(f"| {label} | 待实测 | 待实测 | 待实测 | — | 0/{run.get('total', 20) if run else 20} | 服务部署后执行 |")
        else:
            lines.append(
                f"| {label} | {run['recall3']} | {run['recall6']} | {run['ndcg6']} "
                f"| {run['avgLatencyMs']}ms | {run['okCount']}/{run['total']} | {run.get('ranAt', '')} |"
            )
    lines += [
        "",
        "## 执行步骤（服务器阶段）",
        "",
        "1. 部署 lite 版并完成种子导入（见 docs/deploy-云服务器手册.md）",
        "2. 对每个配置重启 app（环境变量 APP_SEED_CHATID 同步，语料不变）：",
        "   - `regex+overlap`：`RAG_TOKENIZER=regex` `RAG_RERANKER=overlap` `RAG_FINE_RERANKER=none`",
        "   - `bigram+overlap`：`RAG_TOKENIZER=bigram` `RAG_RERANKER=overlap` `RAG_FINE_RERANKER=none`",
        "   - `bigram+bm25`：`RAG_TOKENIZER=bigram` `RAG_RERANKER=bm25` `RAG_FINE_RERANKER=none`",
        "   - `bigram+bm25+rrf`：`RAG_TOKENIZER=bigram` `RAG_RERANKER=bm25` `RAG_FINE_RERANKER=rrf`",
        "3. 每个配置执行 `python eval/run_eval.py run --label <配置名> ...`",
        "4. `python eval/run_eval.py report` 重新生成本文件",
        "",
        "## 待人工补标",
        "",
        "- `eval/dataset.jsonl` 的 `gold_snippets`（答案原文片段，翻 PDF 确认，不许编造）",
        "",
    ]
    Path(args.out).write_text("\n".join(lines), encoding="utf-8")
    print(f"report -> {args.out}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="RAG 检索评测（spec-B5）")
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="对一个配置跑一轮评测")
    run.add_argument("--base-url", default="http://localhost:8080")
    run.add_argument("--token", default="")
    run.add_argument("--api-key", default="")
    run.add_argument("--label", required=True, choices=CONFIGS)
    run.add_argument("--dataset", default="eval/dataset.jsonl")
    run.add_argument("--out", default="eval/raw.json")
    run.add_argument("--interval", type=int, default=500, help="两次请求间隔 ms")
    run.set_defaults(func=run_cmd)

    report = sub.add_parser("report", help="从 raw.json 生成报告")
    report.add_argument("--raw", default="eval/raw.json")
    report.add_argument("--out", default="eval/report.md")
    report.set_defaults(func=report_cmd)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

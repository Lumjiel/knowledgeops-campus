#!/usr/bin/env python3
"""Generate deterministic fixtures for testing the regression evaluator contract.

This script intentionally reads expected fields. Its output must never be used as
evidence of model quality; live quality gates use eval_live_runner.py instead.

用途：为回归评测器（evaluator contract）生成确定性的合成预测结果文件，
不发起任何网络请求，仅做本地数据变换。生成规则（逐条确定，可复现）：

- answer：由 expected_keywords 以空格拼接；无关键词时使用兜底文案
  「已执行并返回结果。」——保证能命中各自的期望关键词；
- citations：优先取 expected_citations 中非空项；RAG 类用例缺省时
  生成 "source=contract/<id>.md, chunk=1" 占位引用；非 RAG 类为空；
- status 恒为 "ok"，prediction_source 恒为 "synthetic_contract_fixture"；
- 延迟字段由用例下标经取模运算确定性生成（first_token 180~299ms，
  total 再加 380~469ms），不含任何随机性。

用法示例：
    python scripts/generate_eval_contract_fixture.py \
        --dataset evaluation/dataset.large.json \
        --output evaluation/predictions.contract.json

输出：JSON 数组（ensure_ascii=False，2 空格缩进）写入 --output，
每条记录含 id / prediction_source / status / answer / citations /
first_token_latency_ms / total_latency_ms，与 eval_live_runner.py 的
预测记录结构对齐，可直接作为评测器回归比对的输入夹具（fixture）。
"""

import argparse
import json
from pathlib import Path


def main():
    """读取数据集 JSON，逐条生成合成预测记录并写入输出文件。

    流程：
    - 读取 --dataset（默认 evaluation/dataset.large.json）的用例数组；
    - 按模块 docstring 所述的确定性规则为每条用例构造 answer、
      citations、status 与延迟字段，组装为预测记录列表；
    - 以 ensure_ascii=False、2 空格缩进写入 --output
      （默认 evaluation/predictions.contract.json）；
    - 成功后向标准输出打印落盘路径与记录条数。

    注意：本函数只读取期望字段做本地变换，任何一步失败（文件缺失、
    JSON 非法）向上抛异常由解释器呈现，不产生部分写入之外的重试。
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="evaluation/dataset.large.json")
    parser.add_argument("--output", default="evaluation/predictions.contract.json")
    args = parser.parse_args()

    dataset = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    predictions = []

    for index, case in enumerate(dataset):
        keywords = case.get("expected_keywords", [])
        answer = " ".join(keywords) if keywords else "已执行并返回结果。"

        expected_citations = case.get("expected_citations", [])
        is_rag = str(case.get("category", "")).startswith("rag")
        if expected_citations:
            citations = [str(item) for item in expected_citations if str(item).strip()]
        elif is_rag:
            citations = [f"source=contract/{case.get('id', index)}.md, chunk=1"]
        else:
            citations = []

        first_token_latency_ms = 180 + (index % 120)
        total_latency_ms = first_token_latency_ms + 380 + (index % 90)

        predictions.append({
            "id": case.get("id"),
            "prediction_source": "synthetic_contract_fixture",
            "status": "ok",
            "answer": answer,
            "citations": citations,
            "first_token_latency_ms": first_token_latency_ms,
            "total_latency_ms": total_latency_ms,
        })

    out = Path(args.output)
    out.write_text(json.dumps(predictions, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"written contract fixture: {out} ({len(predictions)} records)")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""RAG 评测演示脚本（make eval-demo 的后端实现）。

完整走通一次评测闭环，共四个阶段：

1. 加载用例：读取 --dataset 指定的 JSON 文件，逐条归一化字段
   （驼峰/下划线双命名兼容），丢弃缺 question 的条目；
2. 创建数据集：POST /ai/evaluation/datasets，把用例集注册到后端；
3. 启动评测运行：POST /ai/evaluation/datasets/{datasetId}/runs，
   指定 modelProfile 与 chatIdPrefix；
4. 拉取报告：GET /ai/evaluation/runs/{runId}/report，把返回的
   Markdown 报告写入 --output 路径（自动创建父目录）。

用法示例：
    EVAL_DEMO_BASE_URL=http://localhost:8080 python scripts/eval_demo.py

凭据/租户：--api-key 默认取 E2E_API_KEY（再退回 dev-admin-key-2026），
--tenant-id 默认取 EVAL_TENANT_ID（再退回 public），经 X-API-Key /
X-Tenant-ID 请求头传递。后端不可达（URLError）时以非零码退出并提示。
"""

import argparse
import json
import os
import urllib.error
import urllib.request
from pathlib import Path


def request_json(url, method, payload=None, headers=None, timeout=120):
    """发送 HTTP 请求并返回解析后的 JSON 响应体（响应为空时返回 {}）。

    payload 非 None 时序列化为 UTF-8 JSON 作为请求体并追加
    Content-Type: application/json；headers 中同名键可覆盖默认的
    Accept 头。响应体按 UTF-8 严格解码。超时默认 120 秒（评测运行
    可能耗时较长）；网络错误向上抛 urllib 异常，由外层统一处理。
    """
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        req_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
        return json.loads(body) if body else {}


def request_text(url, headers=None, timeout=120):
    """执行 GET 请求并原样返回 UTF-8 文本响应体（不做 JSON 解析）。

    默认 Accept: text/markdown，用于拉取评测报告的 Markdown 原文。
    """
    req = urllib.request.Request(url, headers={"Accept": "text/markdown", **(headers or {})}, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8")


def auth_headers(args):
    """根据命令行参数构造鉴权/租户请求头。

    api_key 存在时附加 X-API-Key，tenant_id 存在时附加 X-Tenant-ID；
    二者相互独立，均可缺省（返回空字典）。
    """
    headers = {}
    if args.api_key:
        headers["X-API-Key"] = args.api_key
    if args.tenant_id:
        headers["X-Tenant-ID"] = args.tenant_id
    return headers


def normalize_case(raw, index):
    """把数据集中一条原始用例归一化为后端评测 API 的标准结构。

    兼容规则：
    - 字段名同时接受驼峰（expectedKeywords）与下划线（expected_keywords）；
    - caseId 缺省时退回 id，再缺省则按序号生成 "case-001" 形式；
    - category 缺省为 "rag"；
    - 所有期望/禁止关键词与引用列表统一转为字符串列表。

    index 为用例在数据集中的下标（0 起），仅用于生成兜底 caseId。
    """
    expected_keywords = raw.get("expectedKeywords", raw.get("expected_keywords", []))
    expected_citations = raw.get("expectedCitations", raw.get("expected_citations", []))
    forbidden_keywords = raw.get("forbiddenKeywords", raw.get("forbidden_keywords", []))
    return {
        "caseId": str(raw.get("caseId", raw.get("id", f"case-{index + 1:03d}"))),
        "category": str(raw.get("category", "rag")),
        "chatId": str(raw.get("chatId", raw.get("chat_id", ""))),
        "question": str(raw.get("question", "")).strip(),
        "expectedKeywords": [str(item) for item in expected_keywords],
        "expectedCitations": [str(item) for item in expected_citations],
        "forbiddenKeywords": [str(item) for item in forbidden_keywords],
    }


def load_cases(path):
    """从 JSON 文件加载评测用例并归一化，返回 question 非空的用例列表。

    文件顶层既可以是用例数组本身，也可以是 {"cases": [...]} 包装对象；
    非字典元素被跳过。读取失败/JSON 非法时向上抛异常。
    """
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    raw_cases = data if isinstance(data, list) else data.get("cases", [])
    cases = [normalize_case(item, idx) for idx, item in enumerate(raw_cases) if isinstance(item, dict)]
    return [case for case in cases if case["question"]]


def main():
    """脚本入口：加载用例 → 创建数据集 → 启动评测运行 → 拉取并落盘报告。

    流程说明：
    - 用例集为空时直接退出（不向后端发起任何请求）；
    - 依次调用评测 API 的 datasets / runs / report 三个端点，
      并从响应中取 datasetId、runId 串联后续调用；
    - 报告写入 --output 指定路径（默认 evaluation/reports/
      latest-evaluation-report.md），父目录不存在时自动创建；
    - 成功时向标准输出打印 datasetId、runId 与报告落盘路径。
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.getenv("EVAL_DEMO_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--dataset", default="evaluation/dataset.json")
    parser.add_argument("--output", default="evaluation/reports/latest-evaluation-report.md")
    parser.add_argument("--api-key", default=os.getenv("E2E_API_KEY", "dev-admin-key-2026"))
    parser.add_argument("--tenant-id", default=os.getenv("EVAL_TENANT_ID", "public"))
    parser.add_argument("--model-profile", default=os.getenv("EVAL_MODEL_PROFILE", "balanced"))
    args = parser.parse_args()

    headers = auth_headers(args)
    base = args.base_url.rstrip("/")
    cases = load_cases(args.dataset)
    if not cases:
        raise SystemExit(f"no cases found in {args.dataset}")

    dataset = request_json(
        f"{base}/ai/evaluation/datasets",
        "POST",
        {
            "name": "RAG Evaluation Studio Demo",
            "description": "Generated by make eval-demo",
            "cases": cases,
        },
        headers,
    )
    run = request_json(
        f"{base}/ai/evaluation/datasets/{dataset['datasetId']}/runs",
        "POST",
        {"modelProfile": args.model_profile, "chatIdPrefix": "eval-demo"},
        headers,
    )
    report = request_text(f"{base}/ai/evaluation/runs/{run['runId']}/report", headers)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report, encoding="utf-8")
    print(f"dataset: {dataset['datasetId']}")
    print(f"run: {run['runId']}")
    print(f"report: {output}")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.URLError as exc:
        raise SystemExit(f"evaluation API is unavailable: {exc}") from exc

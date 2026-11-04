#!/usr/bin/env python3
"""评测实时打标脚本：把数据集逐条打到真实聊天流式端点，产出预测记录。

与离线评测（eval_demo.py 走 /ai/evaluation 端点）不同，本脚本直接调用
POST /ai/react/chat/stream 的 SSE 接口，为 evaluation/dataset.json 中每条
用例采集「真实模型回答」，供与评测报告对照分析。核心能力：

- 每条用例独立流式请求，逐 token 记录首 token 延迟与总耗时；
- answer 优先取 done 事件的 answer 字段，缺省时回退为全部 token 拼接；
- 引用（citations）从 done 事件的顶层 citations 及 trace 各步骤的
  observation.citations 中提取并去重；
- 单条请求失败（HTTP/网络/超时/流内 error 事件）不中断整体运行，
  该条记为 status=error，其余继续。

用法示例：
    E2E_API_KEY=<key> python scripts/eval_live_runner.py --limit 5

输出：JSON 数组写入 --output（默认 evaluation/predictions.live.json），
每条记录包含 id、prediction_source="live_api"、status、answer、error、
citations、first_token_latency_ms、total_latency_ms。
"""

import argparse
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path


def make_headers(args):
    """构造 SSE 流式请求所需的公共请求头。

    固定携带 Content-Type: application/json 与 Accept: text/event-stream；
    认证二选一：优先 Bearer Token（Authorization 头），其次 API Key
    （X-API-Key 头）；配置了租户时追加 X-Tenant-ID 头。
    """
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
    if args.bearer_token:
        headers["Authorization"] = f"Bearer {args.bearer_token}"
    elif args.api_key:
        headers["X-API-Key"] = args.api_key
    if args.tenant_id:
        headers["X-Tenant-ID"] = args.tenant_id
    return headers


def extract_citations(done_payload):
    """从 done 事件 payload 中提取引用列表（去重、保序、去空白项）。

    两个来源依次收集：
    - 顶层 citations 字段（列表，元素转字符串）；
    - trace 各步骤的 observation.citations 字段（RAG 检索观测）。

    done_payload 非 dict（如解析降级为纯文本）时返回空列表。
    去重按首次出现顺序保留，即「unique + order」。
    """
    if not isinstance(done_payload, dict):
        return []
    citations = []

    direct = done_payload.get("citations")
    if isinstance(direct, list):
        for item in direct:
            text = str(item).strip()
            if text:
                citations.append(text)

    trace = done_payload.get("trace") or []
    for step in trace:
        if not isinstance(step, dict):
            continue
        observation = step.get("observation")
        if not isinstance(observation, dict):
            continue
        values = observation.get("citations")
        if isinstance(values, list):
            for item in values:
                text = str(item).strip()
                if text:
                    citations.append(text)
    # unique + order
    seen = set()
    result = []
    for item in citations:
        if item in seen:
            continue
        seen.add(item)
        result.append(item)
    return result


def stream_chat(base_url, body, headers, timeout):
    """对 /ai/react/chat/stream 发起一次 SSE 流式聊天，返回单条预测结果字典。

    返回结构：
    - status："ok"（收到 done 且无 error 事件）或 "error"；
    - answer：done.answer 优先，缺省回退为全部 token 顺序拼接；
    - error：流内 error 事件的消息；流正常结束但无 done 时补
      "stream ended without done payload"；
    - citations：见 extract_citations；
    - first_token_latency_ms：首个非空 token（或 done 事件）距请求
      开始的毫秒数，未收到任何 token/done 时为 None；
    - total_latency_ms：流读取全程耗时（毫秒）。

    SSE 解析兼容标准布局（event:/data:）与 Spring WebFlux 包装布局
    （data:event:/data:data:），空 data 行视为心跳忽略；data 行 JSON
    解析失败时降级为原始文本参与事件处理。非 200 响应抛 RuntimeError，
    由 main 的逐条 try/except 捕获后记为该条用例失败。
    """
    url = f"{base_url.rstrip('/')}/ai/react/chat/stream"
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")

    started = time.perf_counter()
    first_token_ms = None
    token_buffer = []
    done_payload = None
    error_message = ""

    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if resp.status != 200:
            raise RuntimeError(f"HTTP {resp.status}")

        event_name = "message"
        data_lines = []

        while True:
            raw = resp.readline()
            if not raw:
                break
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")

            if line == "":
                if data_lines:
                    joined = "\n".join(data_lines)
                    try:
                        payload = json.loads(joined)
                    except json.JSONDecodeError:
                        payload = joined

                    if event_name == "token" and isinstance(payload, dict):
                        token = str(payload.get("token", ""))
                        if token and first_token_ms is None:
                            first_token_ms = (time.perf_counter() - started) * 1000.0
                        token_buffer.append(token)
                    elif event_name == "done":
                        if first_token_ms is None:
                            first_token_ms = (time.perf_counter() - started) * 1000.0
                        if isinstance(payload, dict):
                            done_payload = payload
                        break
                    elif event_name == "error":
                        if isinstance(payload, dict):
                            error_message = str(payload.get("message", "stream error"))
                        else:
                            error_message = str(payload)
                        break

                event_name = "message"
                data_lines = []
                continue

            # Spring WebFlux may wrap emitted SSE strings as data:event/data:data lines.
            # Support both wrapped and raw SSE formats.
            if line.startswith("data:event:"):
                event_name = line[len("data:event:"):].strip() or "message"
            elif line.startswith("data:data:"):
                data_lines.append(line[len("data:data:"):].strip())
            elif line.startswith("event:"):
                event_name = line[len("event:"):].strip() or "message"
            elif line.startswith("data:"):
                payload = line[len("data:"):].strip()
                if payload:
                    data_lines.append(payload)

    total_latency_ms = (time.perf_counter() - started) * 1000.0

    answer = ""
    if isinstance(done_payload, dict):
        answer = str(done_payload.get("answer", "") or "")
    if not answer:
        answer = "".join(token_buffer)

    if done_payload is not None and not error_message:
        status = "ok"
    else:
        status = "error"
        if not error_message and not answer:
            error_message = "stream ended without done payload"

    return {
        "status": status,
        "answer": answer,
        "error": error_message,
        "citations": extract_citations(done_payload),
        "first_token_latency_ms": round(first_token_ms, 2) if first_token_ms is not None else None,
        "total_latency_ms": round(total_latency_ms, 2),
    }


def main():
    """脚本入口：读数据集 → 逐条流式请求 → 汇总写预测文件。

    流程说明：
    - 数据集为 JSON 数组文件；--limit > 0 时截取前 N 条（0 表示全部）；
    - 每条用例构造 {prompt, chatId, modelProfile} 请求体，chatId 缺省
      按 "eval-live-<序号>" 生成，保证会话独立；
    - 单条请求抛出 HTTPError/URLError/TimeoutError/RuntimeError 时，
      以固定结构的 error 记录代替该条结果，循环不中断；
    - 每处理完一条即向标准输出打印进度 "[i/总数] caseId: status"；
    - 全部完成后把预测数组（ensure_ascii=False，2 空格缩进）写入
      --output 路径并打印落盘位置与记录数。
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="evaluation/dataset.json")
    parser.add_argument("--output", default="evaluation/predictions.live.json")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--timeout", type=float, default=40.0)
    parser.add_argument("--limit", type=int, default=0, help="0 means all")
    parser.add_argument("--model-profile", default="balanced")
    parser.add_argument("--api-key", default=os.getenv("E2E_API_KEY", "dev-admin-key-2026"))
    parser.add_argument("--bearer-token", default="")
    parser.add_argument("--tenant-id", default="")
    args = parser.parse_args()

    dataset = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    if args.limit and args.limit > 0:
        dataset = dataset[: args.limit]

    headers = make_headers(args)

    predictions = []
    for idx, case in enumerate(dataset, start=1):
        case_id = str(case.get("id", ""))
        body = {
            "prompt": case.get("question", ""),
            "chatId": case.get("chatId", f"eval-live-{idx:03d}"),
            "modelProfile": args.model_profile,
        }

        try:
            result = stream_chat(args.base_url, body, headers, args.timeout)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            result = {
                "status": "error",
                "answer": "",
                "error": str(exc),
                "citations": [],
                "first_token_latency_ms": None,
                "total_latency_ms": None,
            }

        prediction = {"id": case_id, "prediction_source": "live_api"}
        prediction.update(result)
        predictions.append(prediction)
        print(f"[{idx}/{len(dataset)}] {case_id}: {prediction['status']}")

    out_path = Path(args.output)
    out_path.write_text(json.dumps(predictions, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"written: {out_path} ({len(predictions)} records)")


if __name__ == "__main__":
    main()

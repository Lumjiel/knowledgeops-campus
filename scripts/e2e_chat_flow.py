#!/usr/bin/env python3
"""端到端（E2E）聊天链路冒烟验证脚本。

针对 knowledgeops 后端依次执行三个阶段的检查：

1. 健康检查：GET /actuator/health，要求 status == "UP"；
2. 同步聊天：POST /ai/react/chat，要求 ok == 1 且 answer 非空；
3. 流式聊天：POST /ai/react/chat/stream（SSE），要求收到 done 事件，
   且 done.ok == 1，并产出 token 或完整 answer 二者之一。

用法示例：
    E2E_API_KEY=<key> python scripts/e2e_chat_flow.py --base-url http://localhost:8080

凭据要求：必须显式提供 --api-key（或环境变量 E2E_API_KEY）或 --bearer-token；
种子演示管理员密钥已被吊销，脚本会拒绝在无凭据时运行。
多租户场景可通过 --tenant-id 附加 X-Tenant-ID 请求头。

任一阶段失败时以非零码退出并输出失败原因；全部通过时打印各阶段
结果及总耗时（毫秒）。
"""

import argparse
import json
import os
import time
import urllib.error
import urllib.request


def make_headers(args, accept_json=True):
    """根据命令行参数构造公共请求头。

    规则：
    - 固定携带 Content-Type: application/json；
    - accept_json 为真时追加 Accept: application/json（SSE 请求应传 False，
      由调用方自行覆盖为 text/event-stream）；
    - 认证二选一：优先 Bearer Token（Authorization 头），其次 API Key（X-API-Key 头）；
    - 配置了租户时追加 X-Tenant-ID 头。
    """
    headers = {"Content-Type": "application/json"}
    if accept_json:
        headers["Accept"] = "application/json"
    if args.bearer_token:
        headers["Authorization"] = f"Bearer {args.bearer_token}"
    elif args.api_key:
        headers["X-API-Key"] = args.api_key
    if args.tenant_id:
        headers["X-Tenant-ID"] = args.tenant_id
    return headers


def http_json(url, body=None, headers=None, timeout=20.0):
    """执行一次 JSON HTTP 请求并返回 (HTTP 状态码, 解析后的 JSON 体)。

    body 为 None 时发 GET，否则将 body 序列化为 UTF-8 JSON 后发 POST。
    响应体为空时返回空字典 {}；非 UTF-8 字节以替换符容错解码。
    网络错误/非 2xx 由调用方通过 urllib 异常统一在外层捕获。
    """
    data = None if body is None else json.dumps(body).encode("utf-8")
    method = "GET" if body is None else "POST"
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        text = resp.read().decode("utf-8", errors="replace")
        return resp.status, json.loads(text) if text else {}


def stream_sse(url, body, headers, timeout=40.0):
    """消费 SSE 流式聊天端点，返回 (token_seen, done_payload, error_message)。

    解析规则（兼容两种布局）：
    - 标准 SSE："event: <name>" + "data: <json>" 行，空行表示事件结束；
    - Spring WebFlux 包装布局：事件名与数据整体被再包一层 data:，
      即 "data:event: <name>" / "data:data: <json>"。

    事件语义：
    - "token"：记录已看到至少一个非空 token（token_seen = True）；
    - "done"：保存其 JSON payload 并结束读取；
    - "error"：记录错误消息并结束读取；
    - 空 "data:" 行视为心跳，忽略。

    若流在空行前意外结束，用最后缓存的未消费事件做一次兜底解析。
    非 200 响应抛 RuntimeError，由 main 的外层统一转成失败退出。
    """
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")

    token_seen = False
    done_payload = None
    error_message = ""

    def consume_event(event, data):
        """处理一个已聚合的 SSE 事件。

        data 为该事件的 data 行列表（按原始顺序）；解析失败时降级为
        {"raw": <原始拼接文本>} 而非直接报错。返回 True 表示流应终止
        （遇到 done 或 error 事件），返回 False 表示继续读取。
        副作用：更新外层的 token_seen / done_payload / error_message。
        """
        nonlocal token_seen, done_payload, error_message
        if not data:
            return False
        joined = "\n".join(data)
        try:
            payload = json.loads(joined)
        except json.JSONDecodeError:
            payload = {"raw": joined}

        if event == "token":
            token = str(payload.get("token", ""))
            if token:
                token_seen = True
        elif event == "done":
            if isinstance(payload, dict):
                done_payload = payload
            return True
        elif event == "error":
            error_message = str(payload.get("message", "stream error"))
            return True
        return False

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
                if consume_event(event_name, data_lines):
                    break

                event_name = "message"
                data_lines = []
                continue

            # Spring WebFlux may wrap our preformatted SSE payload as:
            # data:event: token
            # data:data: {"token":"..."}
            # Keep compatibility with both wrapped and raw SSE layouts.
            if line.startswith("data:event:"):
                event_name = line[len("data:event:"):].strip() or "message"
            elif line.startswith("data:data:"):
                data_lines.append(line[len("data:data:"):].strip())
            elif line.startswith("event:"):
                event_name = line[len("event:"):].strip() or "message"
            elif line.startswith("data:"):
                payload = line[len("data:"):].strip()
                # ignore empty heartbeat "data:" lines
                if payload:
                    data_lines.append(payload)

        if done_payload is None and not error_message:
            consume_event(event_name, data_lines)

    return token_seen, done_payload, error_message


def main():
    """脚本入口：解析参数 → 健康检查 → 同步聊天 → SSE 流式聊天。

    校验规则：
    - 无任何凭据时直接退出（演示密钥已吊销，不允许隐式无鉴权调用）；
    - 健康检查要求 status == "UP"；
    - 同步聊天要求 HTTP 200、ok == 1、answer 非空；
    - 流式聊天要求无 error 事件、存在 done 事件且 done.ok == 1，
      且 token_seen 与完整 answer 至少满足其一。

    chatId 形如 "e2e-chat-<unix秒>"，保证每次运行使用独立会话。
    成功时打印各阶段结果与总耗时；失败通过 SystemExit 以非零码退出。
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--api-key", default=os.getenv("E2E_API_KEY"))
    parser.add_argument("--bearer-token", default="")
    parser.add_argument("--tenant-id", default="")
    parser.add_argument("--model-profile", default="balanced")
    args = parser.parse_args()

    if not args.bearer_token and not args.api_key:
        raise SystemExit(
            "an explicit credential is required: pass --api-key (or set E2E_API_KEY) "
            "or --bearer-token; the seeded demo admin keys have been revoked"
        )

    started = time.perf_counter()

    health_url = f"{args.base_url.rstrip('/')}/actuator/health"
    status, payload = http_json(health_url, headers=make_headers(args))
    if status != 200 or payload.get("status") != "UP":
        raise SystemExit("health check failed")
    print("health ok")

    chat_url = f"{args.base_url.rstrip('/')}/ai/react/chat"
    chat_body = {
        "chatId": f"e2e-chat-{int(time.time())}",
        "prompt": "请返回一句可执行建议，并保持简洁。",
        "modelProfile": args.model_profile,
    }
    status, payload = http_json(chat_url, body=chat_body, headers=make_headers(args), timeout=40.0)
    if status != 200:
        raise SystemExit(f"chat endpoint failed: HTTP {status}")
    if payload.get("ok") != 1:
        raise SystemExit("chat response ok != 1")
    if not str(payload.get("answer", "")).strip():
        raise SystemExit("chat answer is empty")
    print("chat endpoint ok")

    stream_url = f"{args.base_url.rstrip('/')}/ai/react/chat/stream"
    stream_headers = make_headers(args, accept_json=False)
    stream_headers["Accept"] = "text/event-stream"
    token_seen, done_payload, error_message = stream_sse(stream_url, chat_body, stream_headers, timeout=60.0)

    if error_message:
        raise SystemExit(f"stream endpoint returned error: {error_message}")
    if done_payload is None:
        raise SystemExit("stream endpoint missing done event")
    if done_payload.get("ok") != 1:
        raise SystemExit("stream done payload ok != 1")
    if not token_seen and not str(done_payload.get("answer", "")).strip():
        raise SystemExit("stream endpoint produced neither token nor answer")

    elapsed = (time.perf_counter() - started) * 1000.0
    print(f"stream endpoint ok (token_seen={token_seen})")
    print(f"e2e chat flow success in {elapsed:.2f} ms")


if __name__ == "__main__":
    try:
        main()
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, RuntimeError) as exc:
        raise SystemExit(f"e2e chat flow failed: {exc}")

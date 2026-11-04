#!/usr/bin/env python3
"""Unit tests for scripts/e2e_chat_flow.py (stdlib unittest, no external deps).

Covers the three units of the e2e script without touching the network:
- make_headers: auth header selection (bearer > api-key) and tenant header
- http_json: GET/POST mapping, UTF-8 JSON body, empty-body handling
- stream_sse: raw and WebFlux-wrapped SSE layouts, done/error/heartbeat,
  trailing event flush, non-200 response
- main: credential guard, health/chat/stream orchestration and failure paths
"""

import argparse
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parents[3] / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

import e2e_chat_flow  # noqa: E402


class _FakeResponse:
    """Minimal urlopen() stand-in: context manager + read/readline/status."""

    def __init__(self, status=200, lines=(), body=b""):
        self.status = status
        # readline() 返回 bytes，与真实 HTTPResponse 一致；
        # SSE 空行在线缆上是 "\n"，与 EOF 的 b"" 信号不同
        self._lines = [(l if l else "\n") if isinstance(l, str) else l
                       for l in lines]
        self._lines = [l.encode("utf-8") if isinstance(l, str) else l
                       for l in self._lines]
        self._body = body

    def read(self):
        return self._body

    def readline(self):
        return self._lines.pop(0) if self._lines else b""

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False


class MakeHeadersTest(unittest.TestCase):

    def args(self, bearer_token="", api_key=None, tenant_id=""):
        return argparse.Namespace(
            bearer_token=bearer_token, api_key=api_key, tenant_id=tenant_id)

    def test_content_type_and_accept_by_default(self):
        headers = e2e_chat_flow.make_headers(self.args())
        self.assertEqual(headers["Content-Type"], "application/json")
        self.assertEqual(headers["Accept"], "application/json")

    def test_no_accept_header_for_stream_requests(self):
        headers = e2e_chat_flow.make_headers(self.args(), accept_json=False)
        self.assertNotIn("Accept", headers)
        self.assertEqual(headers["Content-Type"], "application/json")

    def test_bearer_token_wins_over_api_key(self):
        headers = e2e_chat_flow.make_headers(
            self.args(bearer_token="tok-1", api_key="key-1"))
        self.assertEqual(headers["Authorization"], "Bearer tok-1")
        self.assertNotIn("X-API-Key", headers)

    def test_api_key_used_when_no_bearer_token(self):
        headers = e2e_chat_flow.make_headers(self.args(api_key="key-1"))
        self.assertEqual(headers["X-API-Key"], "key-1")
        self.assertNotIn("Authorization", headers)

    def test_tenant_header_only_when_present(self):
        headers = e2e_chat_flow.make_headers(self.args(tenant_id="acme"))
        self.assertEqual(headers["X-Tenant-ID"], "acme")
        self.assertNotIn("X-Tenant-ID", e2e_chat_flow.make_headers(self.args()))


class HttpJsonTest(unittest.TestCase):

    def test_get_when_body_is_none(self):
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(
                body=json.dumps({"status": "UP"}).encode("utf-8"))
            status, payload = e2e_chat_flow.http_json(
                "http://x/health", headers={"Accept": "application/json"}, timeout=5)

        req = urlopen.call_args.args[0]
        self.assertEqual(req.get_method(), "GET")
        self.assertIsNone(req.data)
        self.assertEqual(req.get_header("Accept"), "application/json")
        self.assertEqual(urlopen.call_args.kwargs["timeout"], 5)
        self.assertEqual(status, 200)
        self.assertEqual(payload, {"status": "UP"})

    def test_post_sends_utf8_json_body(self):
        body = {"prompt": "请返回一句可执行建议，并保持简洁。"}
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(
                body=json.dumps({"ok": 1, "answer": "建议"}).encode("utf-8"))
            status, payload = e2e_chat_flow.http_json(
                "http://x/chat", body=body, headers={"Content-Type": "application/json"})

        req = urlopen.call_args.args[0]
        self.assertEqual(req.get_method(), "POST")
        self.assertEqual(req.data, json.dumps(body).encode("utf-8"))
        self.assertEqual(req.get_header("Content-type"), "application/json")
        self.assertEqual(status, 200)
        self.assertEqual(payload["ok"], 1)

    def test_empty_response_body_yields_empty_dict(self):
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(body=b"")
            status, payload = e2e_chat_flow.http_json("http://x/health")

        self.assertEqual(status, 200)
        self.assertEqual(payload, {})


class StreamSseTest(unittest.TestCase):

    def stream(self, lines, status=200):
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(status=status, lines=lines)
            return e2e_chat_flow.stream_sse(
                "http://x/stream", {"prompt": "q"}, {}, timeout=9)

    def test_raw_sse_layout_yields_token_and_done(self):
        token_seen, done, error = self.stream([
            "event: token",
            'data: {"token":"你好"}',
            "",
            "event: done",
            'data: {"ok":1,"answer":"你好"}',
            "",
        ])
        self.assertTrue(token_seen)
        self.assertEqual(done, {"ok": 1, "answer": "你好"})
        self.assertEqual(error, "")

    def test_webflux_wrapped_layout_is_supported(self):
        # Spring WebFlux 包装格式：data:event: / data:data: 双前缀
        token_seen, done, error = self.stream([
            "data:event: token",
            'data:data: {"token":"你"}',
            "",
            "data:event: done",
            'data:data: {"ok":1,"answer":"你"}',
            "",
        ])
        self.assertTrue(token_seen)
        self.assertEqual(done, {"ok": 1, "answer": "你"})
        self.assertEqual(error, "")

    def test_error_event_stops_stream_with_message(self):
        token_seen, done, error = self.stream([
            "event: error",
            'data: {"message":"boom"}',
            "",
        ])
        self.assertFalse(token_seen)
        self.assertIsNone(done)
        self.assertEqual(error, "boom")

    def test_empty_heartbeat_data_lines_are_ignored(self):
        token_seen, done, error = self.stream([
            "event: token",
            "data:",
            'data: {"token":"x"}',
            "",
            "event: done",
            'data: {"ok":1}',
            "",
        ])
        self.assertTrue(token_seen)
        self.assertEqual(done, {"ok": 1})

    def test_trailing_done_without_final_blank_line_is_flushed(self):
        # EOF 时仍有一次 flush，未以空行结尾的 done 事件不丢
        token_seen, done, error = self.stream([
            "event: token",
            'data: {"token":"x"}',
            "",
            "event: done",
            'data: {"ok":1}',
        ])
        self.assertTrue(token_seen)
        self.assertEqual(done, {"ok": 1})

    def test_non_json_data_falls_back_to_raw_payload(self):
        token_seen, done, error = self.stream([
            "event: token",
            "data: plain text chunk",
            "",
            "event: done",
            'data: {"ok":1}',
            "",
        ])
        # 非法 JSON 记为 raw，但没有 token 字段 → 不算见过 token
        self.assertFalse(token_seen)
        self.assertEqual(done, {"ok": 1})

    def test_done_with_non_dict_payload_ends_stream_without_done(self):
        token_seen, done, error = self.stream([
            "event: done",
            "data: [1,2]",
            "",
        ])
        self.assertFalse(token_seen)
        self.assertIsNone(done)
        self.assertEqual(error, "")

    def test_non_200_response_raises_runtime_error(self):
        with self.assertRaisesRegex(RuntimeError, "HTTP 500"):
            self.stream([], status=500)


class MainTest(unittest.TestCase):

    def run_main(self, argv, responses):
        """Run main() with a URL-dispatching fake urlopen; return stdout."""
        calls = []

        def fake_urlopen(req, timeout=None):
            calls.append(req)
            for suffix, response in responses:
                if req.full_url.endswith(suffix):
                    return response
            raise AssertionError(f"unexpected url: {req.full_url}")

        argv = ["e2e_chat_flow.py"] + argv
        with mock.patch.object(sys, "argv", argv), \
                mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            buf = io.StringIO()
            with redirect_stdout(buf):
                e2e_chat_flow.main()
        return buf.getvalue(), calls

    def sse_lines(self):
        return [
            "event: token",
            'data: {"token":"建议"}',
            "",
            "event: done",
            'data: {"ok":1,"answer":"建议"}',
            "",
        ]

    base_argv = ["--base-url", "http://test", "--api-key", "key-1"]

    def test_missing_credential_fails_before_any_network_call(self):
        with mock.patch("urllib.request.urlopen") as urlopen:
            with self.assertRaises(SystemExit) as cm:
                with mock.patch.object(sys, "argv", ["e2e_chat_flow.py"]):
                    e2e_chat_flow.main()
        self.assertIn("explicit credential", str(cm.exception))
        urlopen.assert_not_called()

    def test_success_flow_hits_health_chat_stream_in_order(self):
        out, calls = self.run_main(self.base_argv, [
            ("/actuator/health", _FakeResponse(
                body=json.dumps({"status": "UP"}).encode("utf-8"))),
            ("/ai/react/chat", _FakeResponse(
                body=json.dumps({"ok": 1, "answer": "建议"}).encode("utf-8"))),
            ("/ai/react/chat/stream", _FakeResponse(lines=self.sse_lines())),
        ])

        self.assertIn("health ok", out)
        self.assertIn("chat endpoint ok", out)
        self.assertIn("stream endpoint ok (token_seen=True)", out)
        self.assertIn("e2e chat flow success", out)

        self.assertEqual(len(calls), 3)
        self.assertTrue(calls[0].full_url.endswith("/actuator/health"))
        self.assertTrue(calls[1].full_url.endswith("/ai/react/chat"))
        self.assertTrue(calls[2].full_url.endswith("/ai/react/chat/stream"))

        # chat 与 stream 复用同一 body；api key 透传；stream 走 SSE Accept
        chat_body = json.loads(calls[1].data.decode("utf-8"))
        stream_body = json.loads(calls[2].data.decode("utf-8"))
        self.assertEqual(chat_body, stream_body)
        self.assertTrue(chat_body["chatId"].startswith("e2e-chat-"))
        self.assertEqual(chat_body["modelProfile"], "balanced")
        self.assertEqual(calls[1].get_header("X-api-key"), "key-1")
        self.assertEqual(calls[2].get_header("Accept"), "text/event-stream")

    def test_health_not_up_exits_with_failure(self):
        with self.assertRaisesRegex(SystemExit, "health check failed"):
            self.run_main(self.base_argv, [
                ("/actuator/health", _FakeResponse(
                    body=json.dumps({"status": "DOWN"}).encode("utf-8"))),
            ])

    def test_chat_ok_not_one_exits_with_failure(self):
        with self.assertRaisesRegex(SystemExit, "chat response ok != 1"):
            self.run_main(self.base_argv, [
                ("/actuator/health", _FakeResponse(
                    body=json.dumps({"status": "UP"}).encode("utf-8"))),
                ("/ai/react/chat", _FakeResponse(
                    body=json.dumps({"ok": 0}).encode("utf-8"))),
            ])

    def test_stream_error_event_exits_with_failure(self):
        with self.assertRaisesRegex(SystemExit, "stream endpoint returned error: boom"):
            self.run_main(self.base_argv, [
                ("/actuator/health", _FakeResponse(
                    body=json.dumps({"status": "UP"}).encode("utf-8"))),
                ("/ai/react/chat", _FakeResponse(
                    body=json.dumps({"ok": 1, "answer": "建议"}).encode("utf-8"))),
                ("/ai/react/chat/stream", _FakeResponse(lines=[
                    "event: error",
                    'data: {"message":"boom"}',
                    "",
                ])),
            ])

    def test_stream_missing_done_event_exits_with_failure(self):
        with self.assertRaisesRegex(SystemExit, "stream endpoint missing done event"):
            self.run_main(self.base_argv, [
                ("/actuator/health", _FakeResponse(
                    body=json.dumps({"status": "UP"}).encode("utf-8"))),
                ("/ai/react/chat", _FakeResponse(
                    body=json.dumps({"ok": 1, "answer": "建议"}).encode("utf-8"))),
                ("/ai/react/chat/stream", _FakeResponse(lines=[
                    "event: token",
                    'data: {"token":"x"}',
                    "",
                ])),
            ])


if __name__ == "__main__":
    unittest.main()

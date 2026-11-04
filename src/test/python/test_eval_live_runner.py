#!/usr/bin/env python3
"""Unit tests for scripts/eval_live_runner.py (stdlib unittest, no external deps).

Covers the units of the live-eval runner without touching the network:
- make_headers: auth header selection (bearer > api-key), tenant header,
  SSE Accept header
- extract_citations: direct citations + trace observation citations,
  dedup / strip / type-coercion, malformed payload shapes
- stream_chat: raw and WebFlux-wrapped SSE layouts, token/done/error events,
  answer fallback to token buffer, status decision, latency fields,
  request line (POST + UTF-8 JSON body)
- main: dataset → predictions orchestration, --limit slicing, chatId
  defaulting, output writing, per-case failure isolation
"""

import argparse
import io
import json
import sys
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parents[3] / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

import eval_live_runner  # noqa: E402


class _FakeResponse:
    """Minimal urlopen() stand-in: context manager + readline/status."""

    def __init__(self, status=200, lines=()):
        self.status = status
        # readline() 返回 bytes，与真实 HTTPResponse 一致；
        # SSE 空行在线缆上是 "\n"，与 EOF 的 b"" 信号不同
        lines = [(l if l else "\n") if isinstance(l, str) else l
                 for l in lines]
        self._lines = [l.encode("utf-8") if isinstance(l, str) else l
                       for l in lines]

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

    def test_stream_accept_and_json_content_type_by_default(self):
        headers = eval_live_runner.make_headers(self.args())
        self.assertEqual(headers["Content-Type"], "application/json")
        self.assertEqual(headers["Accept"], "text/event-stream")

    def test_bearer_token_wins_over_api_key(self):
        headers = eval_live_runner.make_headers(
            self.args(bearer_token="tok-1", api_key="key-1"))
        self.assertEqual(headers["Authorization"], "Bearer tok-1")
        self.assertNotIn("X-API-Key", headers)

    def test_api_key_used_when_no_bearer_token(self):
        headers = eval_live_runner.make_headers(self.args(api_key="key-1"))
        self.assertEqual(headers["X-API-Key"], "key-1")
        self.assertNotIn("Authorization", headers)

    def test_tenant_header_only_when_present(self):
        headers = eval_live_runner.make_headers(self.args(tenant_id="acme"))
        self.assertEqual(headers["X-Tenant-ID"], "acme")
        self.assertNotIn("X-Tenant-ID",
                         eval_live_runner.make_headers(self.args()))


class ExtractCitationsTest(unittest.TestCase):

    def test_direct_citations_keep_order_and_dedup(self):
        payload = {"citations": ["c2", " c1 ", "c2", ""]}
        self.assertEqual(eval_live_runner.extract_citations(payload),
                         ["c2", "c1"])

    def test_trace_observation_citations_appended_after_direct(self):
        payload = {
            "citations": ["c1"],
            "trace": [
                {"observation": {"citations": ["c2", "c1"]}},
                {"observation": {"citations": ["c3"]}},
                # 非 dict step / 非 dict observation 均跳过
                "not-a-dict",
                {"observation": "not-a-dict"},
                {"other": {"citations": ["c4"]}},
            ],
        }
        self.assertEqual(eval_live_runner.extract_citations(payload),
                         ["c1", "c2", "c3"])

    def test_non_string_items_are_coerced_via_str(self):
        # str() 强转后 strip，非空即保留（None 会被转成字面量 "None"）
        payload = {"citations": [7, None]}
        self.assertEqual(eval_live_runner.extract_citations(payload),
                         ["7", "None"])

    def test_non_dict_payload_yields_empty_list(self):
        self.assertEqual(eval_live_runner.extract_citations(None), [])
        self.assertEqual(eval_live_runner.extract_citations("done"), [])
        self.assertEqual(eval_live_runner.extract_citations([1, 2]), [])

    def test_citations_key_of_wrong_type_is_ignored(self):
        self.assertEqual(
            eval_live_runner.extract_citations({"citations": "c1"}), [])

    def test_non_list_trace_is_ignored(self):
        # dict / 字符串 trace 不可迭代出 dict step，应安全返回 direct 部分
        self.assertEqual(eval_live_runner.extract_citations(
            {"citations": ["c1"], "trace": {"a": 1}}), ["c1"])
        self.assertEqual(eval_live_runner.extract_citations(
            {"trace": "boom"}), [])


class StreamChatTest(unittest.TestCase):

    def stream(self, lines, status=200, base_url="http://x"):
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(status=status, lines=lines)
            result = eval_live_runner.stream_chat(
                base_url, {"prompt": "q"}, {}, timeout=9)
        return result, urlopen

    def test_request_is_post_to_stream_endpoint_with_json_body(self):
        body = {"prompt": "请用一句话总结。"}
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(lines=[
                "event: done",
                'data: {"answer":"a"}',
                "",
            ])
            eval_live_runner.stream_chat(
                "http://x:8080/", body,
                {"Content-Type": "application/json", "X-API-Key": "k"},
                timeout=9)

        req = urlopen.call_args.args[0]
        self.assertTrue(req.full_url.endswith("/ai/react/chat/stream"))
        self.assertEqual(req.get_method(), "POST")
        self.assertEqual(req.data, json.dumps(body).encode("utf-8"))
        self.assertEqual(req.get_header("Content-type"), "application/json")
        self.assertEqual(req.get_header("X-api-key"), "k")
        self.assertEqual(urlopen.call_args.kwargs["timeout"], 9)

    def test_raw_sse_layout_yields_ok_answer_and_first_token_latency(self):
        result, _ = self.stream([
            "event: token",
            'data: {"token":"你好"}',
            "",
            "event: token",
            'data: {"token":"世界"}',
            "",
            "event: done",
            'data: {"answer":"你好世界","citations":["c1"]}',
            "",
        ])
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["answer"], "你好世界")
        self.assertEqual(result["error"], "")
        self.assertEqual(result["citations"], ["c1"])
        self.assertIsNotNone(result["first_token_latency_ms"])
        self.assertGreaterEqual(result["first_token_latency_ms"], 0)
        self.assertGreaterEqual(result["total_latency_ms"], 0)

    def test_webflux_wrapped_layout_is_supported(self):
        result, _ = self.stream([
            "data:event: token",
            'data:data: {"token":"你"}',
            "",
            "data:event: done",
            'data:data: {"answer":"你"}',
            "",
        ])
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["answer"], "你")

    def test_answer_falls_back_to_joined_token_buffer(self):
        result, _ = self.stream([
            "event: token",
            'data: {"token":"a"}',
            "",
            "event: token",
            'data: {"token":"b"}',
            "",
            "event: done",
            'data: {"ok":1}',
            "",
        ])
        # done payload 没有 answer 字段 → 拼接 token 流
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["answer"], "ab")

    def test_done_without_tokens_still_records_first_token_latency(self):
        result, _ = self.stream([
            "event: done",
            'data: {"answer":"a"}',
            "",
        ])
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["answer"], "a")
        self.assertIsNotNone(result["first_token_latency_ms"])

    def test_error_event_yields_error_status_with_message(self):
        result, _ = self.stream([
            "event: token",
            'data: {"token":"部分"}',
            "",
            "event: error",
            'data: {"message":"boom"}',
            "",
        ])
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["error"], "boom")
        self.assertEqual(result["answer"], "部分")
        self.assertEqual(result["citations"], [])

    def test_error_event_with_non_dict_payload_uses_raw_text(self):
        result, _ = self.stream([
            "event: error",
            "data: plain failure",
            "",
        ])
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["error"], "plain failure")

    def test_stream_without_done_event_is_error(self):
        result, _ = self.stream([
            "event: token",
            'data: {"token":"x"}',
            "",
        ])
        # 有 token 输出 → answer 非空、error 留空，但 status 仍为 error
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["error"], "")
        self.assertEqual(result["answer"], "x")

    def test_empty_stream_reports_missing_done_payload(self):
        result, _ = self.stream([])
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["error"], "stream ended without done payload")
        self.assertEqual(result["answer"], "")
        self.assertIsNone(result["first_token_latency_ms"])

    def test_done_with_non_dict_payload_reports_missing_done(self):
        result, _ = self.stream([
            "event: done",
            "data: [1,2]",
            "",
        ])
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["error"], "stream ended without done payload")

    def test_empty_heartbeat_data_lines_are_ignored(self):
        result, _ = self.stream([
            "event: token",
            "data:",
            'data: {"token":"x"}',
            "",
            "event: done",
            'data: {"answer":"x"}',
            "",
        ])
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["answer"], "x")

    def test_non_json_token_payload_is_skipped(self):
        result, _ = self.stream([
            "event: token",
            "data: plain text chunk",
            "",
            "event: done",
            'data: {"answer":"a"}',
            "",
        ])
        # 非 dict 的 token 载荷既不计入 buffer 也不触发首 token 计时
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["answer"], "a")

    def test_done_without_trailing_blank_line_is_not_flushed(self):
        # 与 e2e_chat_flow.stream_sse 不同：本实现没有 EOF flush，
        # 未以空行结尾的 done 事件会被丢弃（现状行为的特征化测试）
        result, _ = self.stream([
            "event: done",
            'data: {"answer":"a"}',
        ])
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["error"], "stream ended without done payload")

    def test_non_200_response_raises_runtime_error(self):
        with self.assertRaisesRegex(RuntimeError, "HTTP 500"):
            self.stream([], status=500)

    def test_base_url_trailing_slash_is_normalized(self):
        result, urlopen = self.stream(
            ["event: done", 'data: {"answer":"a"}', ""],
            base_url="http://x:8080/")
        req = urlopen.call_args.args[0]
        self.assertEqual(req.full_url, "http://x:8080/ai/react/chat/stream")
        self.assertEqual(result["status"], "ok")


class MainTest(unittest.TestCase):

    def run_main(self, argv, lines):
        """Run main() with a fake urlopen serving the same SSE lines per call."""
        calls = []

        def fake_urlopen(req, timeout=None):
            calls.append(req)
            return _FakeResponse(lines=lines)

        argv = ["eval_live_runner.py"] + argv
        with mock.patch.object(sys, "argv", argv), \
                mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            buf = io.StringIO()
            with redirect_stdout(buf):
                eval_live_runner.main()
        return buf.getvalue(), calls

    def write_dataset(self, data):
        tmp = tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8")
        tmp.write(json.dumps(data, ensure_ascii=False))
        tmp.close()
        self.addCleanup(Path(tmp.name).unlink)
        return tmp.name

    def sse_lines(self, answer="答案"):
        return [
            "event: token",
            'data: {"token":"答案"}',
            "",
            "event: done",
            "data: " + json.dumps({"answer": answer, "citations": ["c1"]},
                                  ensure_ascii=False),
            "",
        ]

    base_argv = ["--base-url", "http://test", "--api-key", "key-1",
                 "--tenant-id", "acme"]

    def test_success_flow_writes_predictions_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            dataset = self.write_dataset([
                {"id": "a", "question": "q1"},
                {"id": "b", "question": "q2", "chatId": "ch-fixed"},
            ])
            # 注意：与 eval_demo 不同，main() 不自动创建输出父目录，用平铺路径
            output = Path(tmp) / "predictions.json"

            out, calls = self.run_main(
                self.base_argv + [
                    "--dataset", dataset, "--output", str(output)],
                self.sse_lines())

            # 须在临时目录存续期内读取
            raw_output = output.read_text(encoding="utf-8")
            predictions = json.loads(raw_output)

        self.assertEqual(len(calls), 2)
        self.assertTrue(
            calls[0].full_url.endswith("/ai/react/chat/stream"))

        # 请求体：question→prompt、chatId 缺省按序号补齐、modelProfile 透传
        body1 = json.loads(calls[0].data.decode("utf-8"))
        self.assertEqual(body1, {"prompt": "q1", "chatId": "eval-live-001",
                                 "modelProfile": "balanced"})
        body2 = json.loads(calls[1].data.decode("utf-8"))
        self.assertEqual(body2["chatId"], "ch-fixed")

        # 凭证与租户头透传
        self.assertEqual(calls[0].get_header("X-api-key"), "key-1")
        self.assertEqual(calls[0].get_header("X-tenant-id"), "acme")

        # 输出：id + prediction_source + 流式结果
        self.assertEqual([p["id"] for p in predictions], ["a", "b"])
        self.assertTrue(all(p["prediction_source"] == "live_api"
                            for p in predictions))
        self.assertEqual(predictions[0]["status"], "ok")
        self.assertEqual(predictions[0]["answer"], "答案")
        self.assertEqual(predictions[0]["citations"], ["c1"])
        self.assertEqual(predictions[0]["error"], "")
        self.assertIsNotNone(predictions[0]["total_latency_ms"])

        # 输出为非 ASCII 原样保留的 JSON（ensure_ascii=False）；stdout 逐条汇报进度
        self.assertIn("答案", raw_output)
        self.assertIn("[1/2] a: ok", out)
        self.assertIn("[2/2] b: ok", out)
        self.assertIn(f"written: {output} (2 records)", out)

    def test_limit_slices_dataset(self):
        dataset = self.write_dataset([
            {"id": "a", "question": "q1"},
            {"id": "b", "question": "q2"},
            {"id": "c", "question": "q3"},
        ])
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            out, calls = self.run_main(
                self.base_argv + [
                    "--dataset", dataset, "--output", str(output),
                    "--limit", "2"],
                self.sse_lines())
            predictions = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(len(calls), 2)
        self.assertEqual([p["id"] for p in predictions], ["a", "b"])
        self.assertIn("[2/2] b: ok", out)

    def test_zero_limit_means_all_cases(self):
        dataset = self.write_dataset([
            {"id": "a", "question": "q1"},
            {"id": "b", "question": "q2"},
        ])
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            _, calls = self.run_main(
                self.base_argv + [
                    "--dataset", dataset, "--output", str(output),
                    "--limit", "0"],
                self.sse_lines())
            predictions = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(len(calls), 2)
        self.assertEqual([p["id"] for p in predictions], ["a", "b"])

    def test_model_profile_is_forwarded(self):
        dataset = self.write_dataset([{"id": "a", "question": "q1"}])
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            _, calls = self.run_main(
                self.base_argv + [
                    "--dataset", dataset, "--output", str(output),
                    "--model-profile", "quality"],
                self.sse_lines())

        body = json.loads(calls[0].data.decode("utf-8"))
        self.assertEqual(body["modelProfile"], "quality")

    def test_network_failure_is_isolated_per_case(self):
        calls = []

        def flaky_urlopen(req, timeout=None):
            calls.append(req)
            if len(calls) == 1:
                raise urllib.error.HTTPError(
                    req.full_url, 503, "Service Unavailable", {}, None)
            return _FakeResponse(lines=self.sse_lines())

        dataset = self.write_dataset([
            {"id": "a", "question": "q1"},
            {"id": "b", "question": "q2"},
        ])
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            with mock.patch.object(
                    sys, "argv",
                    ["eval_live_runner.py"] + self.base_argv + [
                        "--dataset", dataset, "--output", str(output)]), \
                    mock.patch("urllib.request.urlopen",
                               side_effect=flaky_urlopen):
                buf = io.StringIO()
                with redirect_stdout(buf):
                    eval_live_runner.main()
            predictions = json.loads(output.read_text(encoding="utf-8"))

        # 第一个用例失败不中断整体运行
        self.assertEqual(len(calls), 2)
        self.assertEqual(predictions[0]["status"], "error")
        self.assertIn("503", predictions[0]["error"])
        self.assertEqual(predictions[0]["answer"], "")
        self.assertEqual(predictions[0]["citations"], [])
        self.assertIsNone(predictions[0]["first_token_latency_ms"])
        self.assertIsNone(predictions[0]["total_latency_ms"])
        self.assertEqual(predictions[1]["status"], "ok")
        self.assertIn("[1/2] a: error", buf.getvalue())
        self.assertIn("[2/2] b: ok", buf.getvalue())


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Unit tests for scripts/eval_demo.py (stdlib unittest, no external deps).

Covers the units of the eval-demo script without touching the network:
- request_json / request_text: method, headers merge, UTF-8 JSON body
- auth_headers: api key / tenant header selection
- normalize_case: camelCase vs snake_case dataset fields, defaults
- load_cases: list/object dataset forms, non-dict and question-less filtering
- main: dataset → run → report orchestration, output writing, failure path
"""

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parents[3] / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

import eval_demo  # noqa: E402


class _FakeResponse:
    """Minimal urlopen() stand-in: context manager + read."""

    def __init__(self, body=b""):
        self._body = body

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False


class RequestJsonTest(unittest.TestCase):

    def test_get_without_payload_has_no_content_type(self):
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(
                body=json.dumps({"ok": 1}).encode("utf-8"))
            payload = eval_demo.request_json("http://x/api", "GET", timeout=7)

        req = urlopen.call_args.args[0]
        self.assertEqual(req.get_method(), "GET")
        self.assertIsNone(req.data)
        self.assertEqual(req.get_header("Accept"), "application/json")
        self.assertIsNone(req.get_header("Content-type"))
        self.assertEqual(urlopen.call_args.kwargs["timeout"], 7)
        self.assertEqual(payload, {"ok": 1})

    def test_post_payload_sends_utf8_json_with_content_type(self):
        body = {"name": "RAG Evaluation Studio Demo"}
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(
                body=json.dumps({"datasetId": "ds-1"}).encode("utf-8"))
            payload = eval_demo.request_json(
                "http://x/api", "POST", body, headers={"X-API-Key": "k"})

        req = urlopen.call_args.args[0]
        self.assertEqual(req.get_method(), "POST")
        self.assertEqual(req.data, json.dumps(body).encode("utf-8"))
        self.assertEqual(req.get_header("Content-type"), "application/json")
        # 自定义头与默认 Accept 合并，而非覆盖
        self.assertEqual(req.get_header("X-api-key"), "k")
        self.assertEqual(req.get_header("Accept"), "application/json")
        self.assertEqual(payload, {"datasetId": "ds-1"})

    def test_default_timeout_is_120(self):
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(body=b"")
            eval_demo.request_json("http://x/api", "GET")

        self.assertEqual(urlopen.call_args.kwargs["timeout"], 120)

    def test_empty_body_yields_empty_dict(self):
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(body=b"")
            self.assertEqual(eval_demo.request_json("http://x/api", "GET"), {})


class RequestTextTest(unittest.TestCase):

    def test_get_requests_markdown_and_returns_decoded_text(self):
        with mock.patch("urllib.request.urlopen") as urlopen:
            urlopen.return_value = _FakeResponse(body="# 报告\n内容".encode("utf-8"))
            text = eval_demo.request_text("http://x/report", headers={"X-Tenant-ID": "t"})

        req = urlopen.call_args.args[0]
        self.assertEqual(req.get_method(), "GET")
        self.assertEqual(req.get_header("Accept"), "text/markdown")
        self.assertEqual(req.get_header("X-tenant-id"), "t")
        self.assertEqual(text, "# 报告\n内容")


class AuthHeadersTest(unittest.TestCase):

    def args(self, api_key=None, tenant_id=None):
        import argparse
        return argparse.Namespace(api_key=api_key, tenant_id=tenant_id)

    def test_both_credentials_are_sent(self):
        headers = eval_demo.auth_headers(self.args(api_key="k", tenant_id="t"))
        self.assertEqual(headers, {"X-API-Key": "k", "X-Tenant-ID": "t"})

    def test_each_credential_is_independent(self):
        self.assertEqual(eval_demo.auth_headers(self.args(api_key="k")),
                         {"X-API-Key": "k"})
        self.assertEqual(eval_demo.auth_headers(self.args(tenant_id="t")),
                         {"X-Tenant-ID": "t"})
        self.assertEqual(eval_demo.auth_headers(self.args()), {})


class NormalizeCaseTest(unittest.TestCase):

    def test_camel_case_fields_take_precedence(self):
        raw = {
            "caseId": "c-1",
            "category": "safety",
            "chatId": "ch-1",
            "question": "  什么是 RAG？  ",
            "expectedKeywords": ["rag", 42],
            "expectedCitations": ["chunk-3"],
            "forbiddenKeywords": ["幻觉"],
        }
        case = eval_demo.normalize_case(raw, 0)

        self.assertEqual(case["caseId"], "c-1")
        self.assertEqual(case["category"], "safety")
        self.assertEqual(case["chatId"], "ch-1")
        self.assertEqual(case["question"], "什么是 RAG？")
        self.assertEqual(case["expectedKeywords"], ["rag", "42"])
        self.assertEqual(case["expectedCitations"], ["chunk-3"])
        self.assertEqual(case["forbiddenKeywords"], ["幻觉"])

    def test_snake_case_fallback_and_id_alias(self):
        raw = {
            "id": 7,
            "chat_id": "ch-7",
            "question": "q",
            "expected_keywords": ["k1"],
            "forbidden_keywords": ["f1"],
        }
        case = eval_demo.normalize_case(raw, 0)

        self.assertEqual(case["caseId"], "7")
        self.assertEqual(case["chatId"], "ch-7")
        self.assertEqual(case["expectedKeywords"], ["k1"])
        self.assertEqual(case["forbiddenKeywords"], ["f1"])

    def test_minimal_case_gets_indexed_defaults(self):
        case = eval_demo.normalize_case({}, 2)

        self.assertEqual(case["caseId"], "case-003")
        self.assertEqual(case["category"], "rag")
        self.assertEqual(case["chatId"], "")
        self.assertEqual(case["question"], "")
        self.assertEqual(case["expectedKeywords"], [])
        self.assertEqual(case["expectedCitations"], [])
        self.assertEqual(case["forbiddenKeywords"], [])


class LoadCasesTest(unittest.TestCase):

    def write_dataset(self, data):
        tmp = tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8")
        tmp.write(json.dumps(data, ensure_ascii=False))
        tmp.close()
        self.addCleanup(Path(tmp.name).unlink)
        return tmp.name

    def test_list_form_loads_all_cases(self):
        path = self.write_dataset([
            {"caseId": "a", "question": "q1"},
            {"caseId": "b", "question": "q2"},
        ])
        cases = eval_demo.load_cases(path)
        self.assertEqual([c["caseId"] for c in cases], ["a", "b"])

    def test_object_form_uses_cases_key(self):
        path = self.write_dataset({"cases": [{"id": "x", "question": "q"}]})
        cases = eval_demo.load_cases(path)
        self.assertEqual([c["caseId"] for c in cases], ["x"])

    def test_non_dict_items_and_blank_questions_are_dropped(self):
        path = self.write_dataset([
            {"caseId": "a", "question": "q1"},
            "not-a-dict",
            {"caseId": "b", "question": "   "},
        ])
        cases = eval_demo.load_cases(path)
        self.assertEqual([c["caseId"] for c in cases], ["a"])

    def test_missing_cases_key_yields_empty_list(self):
        path = self.write_dataset({"other": []})
        self.assertEqual(eval_demo.load_cases(path), [])

    def test_defaults_are_applied_to_loaded_cases(self):
        path = self.write_dataset([{"question": "q"}])
        case = eval_demo.load_cases(path)[0]
        self.assertEqual(case["caseId"], "case-001")
        self.assertEqual(case["category"], "rag")


class MainTest(unittest.TestCase):

    def run_main(self, argv, responses):
        """Run main() with a URL-dispatching fake urlopen; return (stdout, calls)."""
        calls = []

        def fake_urlopen(req, timeout=None):
            calls.append(req)
            for suffix, response in responses:
                if req.full_url.endswith(suffix):
                    return response
            raise AssertionError(f"unexpected url: {req.full_url}")

        argv = ["eval_demo.py"] + argv
        with mock.patch.object(sys, "argv", argv), \
                mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            buf = io.StringIO()
            with redirect_stdout(buf):
                eval_demo.main()
        return buf.getvalue(), calls

    def test_success_flow_creates_dataset_run_and_writes_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            dataset = Path(tmp) / "dataset.json"
            dataset.write_text(json.dumps(
                [{"caseId": "a", "question": "q1"}], ensure_ascii=False),
                encoding="utf-8")
            output = Path(tmp) / "nested" / "report.md"

            out, calls = self.run_main([
                "--base-url", "http://test",
                "--dataset", str(dataset),
                "--output", str(output),
                "--model-profile", "quality",
            ], [
                ("/report", _FakeResponse(body="# 评测报告".encode("utf-8"))),
                ("/runs", _FakeResponse(
                    body=json.dumps({"runId": "run-1"}).encode("utf-8"))),
                ("/datasets", _FakeResponse(
                    body=json.dumps({"datasetId": "ds-1"}).encode("utf-8"))),
            ])
            # 报告写入输出路径（含父目录自动创建）；须在临时目录存续期内读取
            report_text = output.read_text(encoding="utf-8")

        # dataset → run → report 三次调用，顺序与端点正确
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[0].get_method(), "POST")
        self.assertTrue(calls[0].full_url.endswith("/ai/evaluation/datasets"))
        self.assertEqual(calls[1].get_method(), "POST")
        self.assertTrue(calls[1].full_url.endswith("/datasets/ds-1/runs"))
        self.assertEqual(calls[2].get_method(), "GET")
        self.assertTrue(calls[2].full_url.endswith("/runs/run-1/report"))

        # 请求体：dataset 携带归一化后的 cases；run 携带 modelProfile 与 chatIdPrefix
        dataset_body = json.loads(calls[0].data.decode("utf-8"))
        self.assertEqual(dataset_body["cases"][0]["caseId"], "a")
        run_body = json.loads(calls[1].data.decode("utf-8"))
        self.assertEqual(run_body, {"modelProfile": "quality", "chatIdPrefix": "eval-demo"})

        # 默认凭证与租户头出现在每个请求上
        for req in calls:
            self.assertEqual(req.get_header("X-api-key"), "dev-admin-key-2026")
            self.assertEqual(req.get_header("X-tenant-id"), "public")

        # 报告内容与 stdout 汇总的三个 ID
        self.assertEqual(report_text, "# 评测报告")
        self.assertIn("dataset: ds-1", out)
        self.assertIn("run: run-1", out)
        self.assertIn(f"report: {output}", out)

    def test_empty_dataset_fails_before_any_network_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            dataset = Path(tmp) / "dataset.json"
            dataset.write_text(json.dumps([{"question": "  "}]), encoding="utf-8")

            with mock.patch("urllib.request.urlopen") as urlopen:
                with self.assertRaisesRegex(SystemExit, "no cases found in"):
                    with mock.patch.object(
                            sys, "argv",
                            ["eval_demo.py", "--dataset", str(dataset)]):
                        eval_demo.main()
            urlopen.assert_not_called()


if __name__ == "__main__":
    unittest.main()

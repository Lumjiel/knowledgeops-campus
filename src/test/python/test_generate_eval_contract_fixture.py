#!/usr/bin/env python3
"""Unit tests for scripts/generate_eval_contract_fixture.py (stdlib unittest).

Covers the contract-fixture generator end to end without touching the network:
- answer: joined expected_keywords, fallback when empty/missing
- citations: expected_citations pass-through (str coercion + blank filtering),
  rag-category synthetic fallback, non-rag empty
- determinism: index-based latency formula, byte-identical reruns
- main: dataset/output argument handling, stdout summary, empty dataset
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

import generate_eval_contract_fixture  # noqa: E402


class GenerateContractFixtureTest(unittest.TestCase):

    def run_main(self, argv):
        argv = ["generate_eval_contract_fixture.py"] + argv
        with mock.patch.object(sys, "argv", argv):
            buf = io.StringIO()
            with redirect_stdout(buf):
                generate_eval_contract_fixture.main()
        return buf.getvalue()

    def write_dataset(self, data):
        tmp = tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8")
        tmp.write(json.dumps(data, ensure_ascii=False))
        tmp.close()
        self.addCleanup(Path(tmp.name).unlink)
        return tmp.name

    def generate(self, dataset, output):
        dataset_path = self.write_dataset(dataset)
        out = self.run_main(
            ["--dataset", dataset_path, "--output", str(output)])
        return json.loads(Path(output).read_text(encoding="utf-8")), out

    def test_answer_joins_expected_keywords(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate(
                [{"id": "a", "expected_keywords": ["检索", "增强", "生成"]}],
                output)

        self.assertEqual(predictions[0]["answer"], "检索 增强 生成")
        self.assertEqual(predictions[0]["status"], "ok")
        self.assertEqual(predictions[0]["prediction_source"],
                         "synthetic_contract_fixture")

    def test_answer_falls_back_when_keywords_missing_or_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate(
                [{"id": "a", "expected_keywords": []}, {"id": "b"}],
                output)

        self.assertEqual(predictions[0]["answer"], "已执行并返回结果。")
        self.assertEqual(predictions[1]["answer"], "已执行并返回结果。")

    def test_expected_citations_coerced_and_blank_filtered(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate(
                [{"id": "a", "expected_citations": ["c1", 7, "  ", "c2"]}],
                output)

        self.assertEqual(predictions[0]["citations"], ["c1", "7", "c2"])

    def test_rag_case_without_citations_gets_synthetic_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate(
                [{"id": "case-9", "category": "rag"},
                 {"id": "case-10", "category": "rag-evidence"}],
                output)

        self.assertEqual(
            predictions[0]["citations"],
            ["source=contract/case-9.md, chunk=1"])
        self.assertEqual(
            predictions[1]["citations"],
            ["source=contract/case-10.md, chunk=1"])

    def test_non_rag_case_without_citations_gets_empty_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate(
                [{"id": "a", "category": "safety"},
                 {"id": "b"},
                 {"id": "c", "category": "registry"}],
                output)

        for prediction in predictions:
            self.assertEqual(prediction["citations"], [])

    def test_missing_id_uses_index_in_synthetic_citation(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate(
                [{"category": "rag"},
                 {"id": "has-id", "category": "rag"}],
                output)

        self.assertEqual(
            predictions[0]["citations"],
            ["source=contract/0.md, chunk=1"])
        self.assertEqual(
            predictions[1]["citations"],
            ["source=contract/has-id.md, chunk=1"])

    def test_citations_win_over_rag_synthetic_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate(
                [{"id": "a", "category": "rag",
                  "expected_citations": ["c1"]}],
                output)

        self.assertEqual(predictions[0]["citations"], ["c1"])

    def test_latency_is_deterministic_index_based_formula(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate(
                [{"id": i} for i in range(150)], output)

        for index, prediction in enumerate(predictions):
            first = 180 + (index % 120)
            self.assertEqual(prediction["first_token_latency_ms"], first)
            self.assertEqual(prediction["total_latency_ms"],
                             first + 380 + (index % 90))

    def test_id_passthrough_including_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate(
                [{"id": "x"}, {"id": 42}, {}], output)

        self.assertEqual([p["id"] for p in predictions], ["x", 42, None])

    def test_reruns_are_byte_identical(self):
        dataset = [{"id": "a", "expected_keywords": ["k1"],
                    "expected_citations": ["c1"], "category": "rag"}]
        with tempfile.TemporaryDirectory() as tmp:
            first = Path(tmp) / "first.json"
            second = Path(tmp) / "second.json"
            self.generate(dataset, first)
            self.generate(dataset, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_stdout_reports_written_path_and_record_count(self):
        dataset = self.write_dataset([{"id": "a"}, {"id": "b"}])
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            out = self.run_main(
                ["--dataset", dataset, "--output", str(output)])

        self.assertIn(f"written contract fixture: {output} (2 records)", out)

    def test_empty_dataset_writes_empty_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, out = self.generate([], output)

        self.assertEqual(predictions, [])
        self.assertIn("(0 records)", out)

    def test_prediction_record_has_no_error_key(self):
        # 合约夹具只产出成功记录；不应携带 error / first_token 之外的杂项键
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            predictions, _ = self.generate([{"id": "a"}], output)

        self.assertEqual(set(predictions[0]), {
            "id", "prediction_source", "status", "answer", "citations",
            "first_token_latency_ms", "total_latency_ms"})

    def test_non_ascii_output_is_not_escaped(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "predictions.json"
            self.generate([{"id": "中文", "expected_keywords": ["答案"]}],
                          output)
            raw = output.read_text(encoding="utf-8")

        self.assertIn("中文", raw)
        self.assertIn("答案", raw)
        self.assertNotIn("\\u", raw)


if __name__ == "__main__":
    unittest.main()

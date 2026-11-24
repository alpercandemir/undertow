import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
from unittest import TestCase

from fixtures import POLICY, SHORT_POLICY, SOURCE_URL, GitReviewTest

EVALUATION = Path(__file__).resolve().parents[1] / "evaluation"
sys.path.insert(0, str(EVALUATION))
import run as evaluation
from copilot import finding_fields, parse_response
from pilot import compare, make_packet


class EvaluationRunTests(GitReviewTest):
    def test_frozen_worker_retains_reports_and_records_unavailable_comparisons(self):
        self.comparison({"policy.py": POLICY}, {"policy.py": SHORT_POLICY})
        owner = self.directory / "example"
        owner.mkdir()
        self.bare.rename(owner / "fixture.git")
        good_case = {
            "id": "threshold", "repository": "example/fixture", "source_url": SOURCE_URL,
            "base": self.base, "head": self.head, "classification": "intentional_change",
            "changes": [{"id": "1", "supported": True}],
        }
        manifest = self.directory / "manifest.json"
        manifest.write_text(json.dumps({"schema": 1, "kind": "synthetic", "cases": [
            good_case, {**good_case, "id": "missing", "head": "a" * 40},
        ]}))
        # Even an ambient model configuration must have no effect on a local run.
        env = {**self.env, "GEMINI_API_KEY": "fixture", "UNDERTOW_GEMINI_MODEL": "invalid-fixture"}
        result = subprocess.run([
            sys.executable, str(EVALUATION / "run.py"), "--manifest", str(manifest),
            "run", "--repos", str(self.directory), "--output", str(self.output),
        ], env=env, capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        record = json.loads((self.output / "run.json").read_text())
        self.assertEqual(record["undertow_commit"], evaluation.RELEASE)
        self.assertRegex(record["worker_sha256"], r"\A[0-9a-f]{64}\Z")
        rows = record["cases"]
        self.assertEqual((rows["threshold"]["status"], rows["threshold"]["mode"],
                          rows["threshold"]["version"]), ("findings", "local", "0.3.0"))
        report = json.loads((self.output / "threshold" / "report.json").read_text())
        finding, = report["findings"]
        self.assertEqual(rows["threshold"]["finding_ids"], [
            evaluation.digest({"finding": finding, "evidence": report["evidence"]}),
        ])
        self.assertTrue((self.output / "threshold" / "summary.md").is_file())
        self.assertEqual(rows["missing"]["status"], "unavailable")
        self.assertEqual(rows["missing"]["finding_ids"], [])


class ScoreTests(TestCase):
    def test_score_uses_adjudicated_findings_and_requested_mode(self):
        manifest = {"schema": 1, "kind": "synthetic", "cases": [
            {"id": "changed", "classification": "bug_fix",
             "changes": [{"id": "1", "supported": True}, {"id": "2", "supported": False}]},
            {"id": "control", "classification": "no_change", "changes": []},
            {"id": "fallback", "classification": "bug_fix",
             "changes": [{"id": "1", "supported": True}]},
            {"id": "pending", "classification": "unverified", "changes": []},
        ]}
        cases = {case["id"]: case for case in manifest["cases"]}
        record = {"schema": 1, "manifest_sha256": evaluation.digest(manifest), "requested_mode": "gemini",
                  "cases": {case_id: {"status": "findings", "mode": mode, "finding_ids": finding_ids,
                                      "seconds": seconds}
                            for case_id, mode, finding_ids, seconds in (
                                ("changed", "gemini", ["match", "duplicate"], 2),
                                ("control", "gemini", ["incorrect"], 4),
                                ("fallback", "local", ["local-only"], 100),
                                ("pending", "gemini", ["unreviewed"], 6),
                            )}}
        reviews = {
            "changed": {finding_id: {"verdict": "correct", "changes": ["1"], "concrete": True}
                        for finding_id in ("match", "duplicate")},
            "control": {"incorrect": {"verdict": "incorrect"}},
        }
        metrics = evaluation.score(record, manifest, cases, reviews)
        self.assertEqual(metrics["completed_in_other_mode"], 1)
        self.assertEqual(metrics["scored_cases"], 2)
        self.assertEqual(metrics["unverified_findings"], 1)
        self.assertEqual(metrics["recall_supported"], {"numerator": 1, "denominator": 1, "value": 1.0})
        self.assertEqual(metrics["recall_labeled"]["value"], 0.5)
        self.assertEqual(metrics["precision"]["value"], 2 / 3)
        self.assertEqual(metrics["incorrect_findings_per_control"]["value"], 1.0)
        self.assertEqual(metrics["median_seconds"], 4)
        with self.assertRaisesRegex(ValueError, "unknown findings"):
            evaluation.score(record, manifest, cases, {"changed": {"other-run": {}}})
        with self.assertRaisesRegex(ValueError, "do not match"):
            evaluation.score({**record, "manifest_sha256": "outdated"}, manifest, cases, reviews)

    def test_copilot_findings_can_be_blinded_and_compared_without_counting_unavailable_cases(self):
        case = {"id": "changed", "source_url": SOURCE_URL, "base": "a" * 40, "head": "b" * 40,
                "classification": "regression", "changes": [{"id": "1", "supported": False}]}
        incomplete = {**case, "id": "incomplete"}
        manifest = {"schema": 1, "kind": "synthetic", "cases": [case, incomplete]}
        cases = {item["id"]: item for item in manifest["cases"]}
        ref = {"side": "head", "path": "policy.py", "line": 7}
        claim = {"claim": "GitHub Copilot: a request now fails", "input": "retry after 1 second",
                 "before": "request succeeds", "after": "request fails", "question": "Intended?",
                 "sources": [ref]}
        findings = parse_response(f"Inspecting source.\n\n```json\n{json.dumps([claim])}\n```\n")
        self.assertEqual(parse_response("Inspecting source.\n[]\n"), [])
        self.assertEqual(parse_response(f"Inspecting [source].{json.dumps([claim])}\n"), [claim])
        hit = {"seconds": 10, **finding_fields(findings)}
        miss = {"status": "no_findings", "mode": "local", "seconds": 1, "finding_ids": []}
        header = {"schema": 1, "manifest_sha256": evaluation.digest(manifest)}
        copilot = {**header, "requested_mode": "copilot", "cases": {"changed": hit, "incomplete": hit}}
        local = {**header, "requested_mode": "local", "cases": {
            "changed": miss, "incomplete": {**miss, "status": "unavailable"}}}
        identifier, = hit["finding_ids"]
        judgments = {"changed": {identifier: {"verdict": "correct", "concrete": False, "changes": ["1"]}}}
        metrics = evaluation.score(copilot, manifest, cases, judgments)
        result = compare(manifest, cases, local, copilot, {}, judgments, {"changed": {"1": "Test added"}})
        self.assertEqual(metrics["concrete_example_accuracy"], evaluation.ratio(0, 1))
        self.assertEqual(result["paired_adjudicated_cases"], 1)
        self.assertEqual(result["totals"]["right_only"], 1)
        self.assertEqual(result["totals"]["right_only_with_observed_action"], 1)

        with TemporaryDirectory() as temporary:
            directory = Path(temporary)
            record_path = directory / "run.json"
            evaluation.write(record_path, copilot)
            packet_dir = directory / "packet"
            make_packet(manifest, cases, [record_path], packet_dir)
            packet = evaluation.read(packet_dir / "reviewer.json")
            key = evaluation.read(packet_dir / "coordinator-key.json")
        self.assertEqual(key["packet_sha256"], evaluation.digest(packet))
        reviewer_text = json.dumps(packet)
        for private in ("Copilot", "changed", "regression", identifier):
            self.assertNotIn(private, reviewer_text)
        masked = next(iter(packet["cases"]["C01"]["findings"].values()))
        self.assertEqual(masked["sources"], [{**ref, "end": 7}])

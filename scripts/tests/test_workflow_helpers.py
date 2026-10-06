"""Exercise artifact provenance and evaluation behavior without network access."""

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(filename):
    spec = importlib.util.spec_from_file_location(filename, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


artifacts = load_script("verify-artifacts.py")
evaluation = load_script("evaluate.py")


class ArtifactVerificationTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.environment = {
            "GITHUB_REPOSITORY": "owner/repository",
            "GITHUB_RUN_ID": "123",
            "GITHUB_SHA": "a" * 40,
            "PR_NUMBER": "7",
        }
        for name in artifacts.EXPECTED_FILES - {"provenance.json"}:
            (self.root / name).write_text("test artifact", encoding="utf-8")
        self.write_json("pr.json", {
            "repository": "owner/repository", "number": 7,
            "base_sha": "b" * 40, "head_sha": "c" * 40,
        })
        self.write_json("report.json", {
            "base_sha": "b" * 40, "head_sha": "c" * 40,
        })
        self.write_manifest()

    def write_json(self, name, content):
        (self.root / name).write_text(json.dumps(content), encoding="utf-8")

    def write_manifest(self):
        self.write_json("provenance.json", {
            "repository": self.environment["GITHUB_REPOSITORY"],
            "run_id": self.environment["GITHUB_RUN_ID"],
            "harness_sha": self.environment["GITHUB_SHA"],
            "hashes": {
                name: hashlib.sha256((self.root / name).read_bytes()).hexdigest()
                for name in artifacts.EXPECTED_FILES - {"provenance.json"}
            },
        })

    def test_same_run_bundle_can_be_verified_repeatedly(self):
        artifacts.verify(self.root, self.environment)
        artifacts.verify(self.root, self.environment)

    def test_modified_content_is_rejected(self):
        (self.root / "report.md").write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "content hash mismatch"):
            artifacts.verify(self.root, self.environment)

    def test_path_traversal_in_manifest_is_rejected(self):
        manifest = json.loads((self.root / "provenance.json").read_text())
        manifest["hashes"]["../outside"] = "fake hash"
        self.write_json("provenance.json", manifest)
        with self.assertRaisesRegex(ValueError, "content hash mismatch"):
            artifacts.verify(self.root, self.environment)

    def test_symlink_is_rejected(self):
        path = self.root / "report.md"
        path.unlink()
        path.symlink_to(self.root / "report.json")
        with self.assertRaisesRegex(ValueError, "artifact type/size"):
            artifacts.verify(self.root, self.environment)

    def test_other_run_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "workflow provenance mismatch"):
            artifacts.verify(self.root, {**self.environment, "GITHUB_RUN_ID": "456"})

    def test_stale_report_commits_are_rejected_even_with_matching_hashes(self):
        self.write_json("report.json", {"base_sha": "b" * 40, "head_sha": "d" * 40})
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "Report commit mismatch"):
            artifacts.verify(self.root, self.environment)


class EvaluationScoringTest(unittest.TestCase):
    def test_scores_matching_and_unrelated_findings_independently(self):
        label = {"id": "case", "split": "heldout", "unsafe": True,
                 "rule_id": "rule", "severity": "HIGH"}
        report = {
            "status": "partial", "elapsed_millis": 5, "model_calls": 1,
            "tool_calls": 2, "input_tokens": 100, "output_tokens": 50,
            "findings": [
                {"rule_ids": ["rule"], "severity": "MEDIUM"},
                {"rule_ids": ["other"], "severity": "HIGH"},
            ],
        }
        row = evaluation.score_run(
            SimpleNamespace(input_rate=1, output_rate=2), label, report, "tools", 0
        )
        self.assertEqual(row["true_positive"], 1)
        self.assertEqual(row["false_positive"], 1)
        self.assertEqual(row["false_negative"], 0)
        self.assertEqual(row["severity_disagreements"], 1)
        self.assertAlmostEqual(row["estimated_cost_usd"], 0.0002)
        summary = evaluation.summarize([row])
        self.assertEqual(summary["precision"], 0.5)
        self.assertEqual(summary["recall"], 1)
        self.assertIsNone(summary["benign_false_positive_rate"])


if __name__ == "__main__":
    unittest.main()

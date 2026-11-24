import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "actions" / "replay-comment" / "publish.py"
spec = importlib.util.spec_from_file_location("replay_comment", SCRIPT)
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class CommentTests(unittest.TestCase):
    def test_behavior_comment_requires_new_observed_evidence(self):
        finding = {"target": "src/checkout.py::quote", "type": "int", "input": "50",
                   "base": {"returned": False}, "head": {"raised": "ValueError"}}
        report = {"schema_version": 1, "kind": "behavior", "status": "observed_difference",
                  "base": {"sha": "a"}, "head": {"sha": "b"}, "findings": [finding, finding]}
        sections = publisher.sections_for(report, "a", "b", set())
        self.assertEqual(len(sections), 1)
        self.assertIn("New exception on PR head", sections[0])
        self.assertIn("| Result | false | Raised ValueError |", sections[0])
        self.assertEqual(publisher.sections_for(report, "a", "b", {publisher.fingerprint(finding)}), [])
        with self.assertRaises(ValueError):
            publisher.sections_for(report, "a", "c", set())
        self.assertNotIn("<img", publisher.cell("<img> | @user"))
        report["status"] = "unavailable"
        self.assertEqual(publisher.sections_for(report, "a", "b", set()), [])

    def test_selected_pytest_results_stay_in_actions(self):
        report = {"schema_version": 1, "test": "test.py::test_quote",
                  "base": {"sha": "a", "status": "passed"},
                  "head": {"sha": "b", "status": "assertion_failed"}}
        self.assertEqual(publisher.sections_for(report, "a", "b", set()), [])
        report["base"]["status"], report["head"]["status"] = "assertion_failed", "passed"
        self.assertEqual(publisher.sections_for(report, "a", "b", set()), [])

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "actions" / "replay-comment" / "publish.py"
spec = importlib.util.spec_from_file_location("replay_comment", SCRIPT)
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class CommentTests(unittest.TestCase):
    def test_values_stale_revisions_and_legacy_artifacts(self):
        report = {"schema_version": 1, "test": "test.py::test_quote",
                  "base": {"sha": "a", "status": "assertion_failed"},
                  "head": {"sha": "b", "status": "passed", "observations": {"Amount due": "$9.04"}}}
        text = publisher.comparison(report, "a", "b")
        self.assertIn("| Assertion | Assertion failed | Passed |", text)
        self.assertIn("| Amount due | Unrecorded | $9.04 |", text)
        with self.assertRaises(ValueError):
            publisher.comparison(report, "a", "c")
        self.assertNotIn("<img", publisher.cell("<img> | @user"))

    def test_behavior_comment_requires_new_observed_evidence(self):
        finding = {"target": "src/checkout.py::quote", "type": "int", "input": "50",
                   "base": {"returned": False}, "head": {"raised": "ValueError"}}
        report = {"schema_version": 1, "kind": "behavior", "status": "observed_difference",
                  "base": {"sha": "a"}, "head": {"sha": "b"}, "findings": [finding, finding]}
        sections = publisher.sections_for(report, "a", "b", set(), False)
        self.assertEqual(len(sections), 1)
        self.assertIn("New exception on PR head", sections[0])
        self.assertIn("| Result | false | Raised ValueError |", sections[0])
        self.assertEqual(publisher.sections_for(report, "a", "b", {publisher.fingerprint(finding)}, False), [])
        report["status"] = "unavailable"
        self.assertEqual(publisher.sections_for(report, "a", "b", set(), False), [])

    def test_selected_pytest_results_are_quiet_by_default(self):
        report = {"schema_version": 1, "test": "test.py::test_quote",
                  "base": {"sha": "a", "status": "passed"},
                  "head": {"sha": "b", "status": "assertion_failed"}}
        self.assertEqual(publisher.sections_for(report, "a", "b", set(), False), [])
        section, = publisher.sections_for(report, "a", "b", set(), True)
        self.assertIn("Possible regression", section)
        report["head"]["status"] = "passed"
        self.assertEqual(publisher.sections_for(report, "a", "b", set(), True), [])

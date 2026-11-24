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

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


REPLAY = Path(__file__).resolve().parents[1] / "actions" / "test-replay" / "replay.py"
spec = importlib.util.spec_from_file_location("undertow_test_replay", REPLAY)
replay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(replay)


class ReplayInputTests(unittest.TestCase):
    def test_rejects_unsafe_or_incomplete_selectors(self):
        for node_id in ("../test_cart.py::test_cart", "/test_cart.py::test_cart",
                        "test_cart.py", "--test.py::test_cart", "test_cart.py::test\ncart"):
            with self.subTest(node_id=node_id), self.assertRaises(ValueError):
                replay.test_path(node_id)


@unittest.skipUnless(importlib.util.find_spec("pytest"), "pytest required for replay integration")
class ReplayIntegrationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Undertow Test")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "commit.gpgsign", "false")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], text=True).strip()

    def commit(self, files):
        for name, contents in files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(textwrap.dedent(contents), encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "Update test fixture")
        return self.git("rev-parse", "HEAD")

    def run_replay(self, base, head, node_id):
        output = self.root / "report"
        result = subprocess.run(
            [sys.executable, "-IB", str(REPLAY), "--repo", str(self.repo), "--base", base,
             "--head", head, "--test", node_id, "--output", str(output), "--timeout", "15"],
            capture_output=True, text=True, timeout=45,
        )
        report = json.loads((output / "report.json").read_text())
        self.assertEqual(report["base"]["sha"], base)
        self.assertEqual(report["head"]["sha"], head)
        self.assertEqual(result.stdout, (output / "summary.md").read_text())
        return result.returncode, report

    def test_uses_head_test_on_both_revisions(self):
        base = self.commit({"src/cart.py": "shipping = 7\n",
                            "tests/test_cart.py": "def test_cart(): assert False, 'stale base test'\n"})
        head = self.commit({"src/cart.py": "shipping = 0\n", "tests/test_cart.py": """
            from cart import shipping

            def test_cart(record_property):
                record_property("undertow.Shipping", str(shipping))
                assert shipping == 0
            """})
        code, report = self.run_replay(base, head, "tests/test_cart.py::test_cart")
        self.assertEqual(code, 0)
        self.assertEqual(report["status"], "passes_on_head_only")
        self.assertIn("7 == 0", report["base"]["assertion"])
        self.assertEqual(report["head"]["status"], "passed")
        self.assertEqual(report["base"]["observations"], {"Shipping": "7"})
        self.assertEqual(report["head"]["observations"], {"Shipping": "0"})
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_supports_new_test_and_exact_parameter(self):
        base = self.commit({"cart.py": "shipping = 0\n"})
        head = self.commit({"cart.py": "shipping = 7\n", "tests/test_cart.py": """
            import pytest
            from cart import shipping

            @pytest.mark.parametrize("expected", [0, 7])
            def test_cart(expected):
                assert shipping == expected
            """})
        code, report = self.run_replay(base, head, "tests/test_cart.py::test_cart[0]")
        self.assertEqual(code, 0)
        self.assertEqual(report["status"], "passes_on_base_only")
        self.assertEqual(report["head"]["status"], "assertion_failed")

    def test_assertion_in_setup_is_unavailable(self):
        base = self.commit({"cart.py": "shipping = 7\n"})
        head = self.commit({"cart.py": "shipping = 0\n", "tests/test_cart.py": """
            import pytest
            from cart import shipping

            @pytest.fixture
            def cart():
                assert shipping == 0

            def test_cart(cart):
                pass
            """})
        code, report = self.run_replay(base, head, "tests/test_cart.py::test_cart")
        self.assertEqual(code, 1)
        self.assertEqual(report["status"], "unavailable")
        self.assertEqual(report["base"]["status"], "unavailable")
        self.assertEqual(report["head"]["status"], "passed")

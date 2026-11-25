import json
import subprocess
import sys

from fixtures import ACTION, GitReviewTest


SCRIPT = ACTION.parent / "behavior-replay" / "discover.py"
EXPORT = SCRIPT.with_name("write_test.py")
REVIEW = SCRIPT.with_name("review.py")
ROUNDING = """from decimal import Decimal, ROUND_HALF_UP

RATE = Decimal("0.10")
CENT = Decimal("0.01")

def quote(prices: list[Decimal]):
    subtotal = sum(prices)
    discount = sum((price * RATE).quantize(CENT, rounding=ROUND_HALF_UP) for price in prices)
    return {"discount": discount, "amount_due": subtotal - discount}
"""


class BehaviorReplayTests(GitReviewTest):
    def branch_review(self, probe, choice):
        return subprocess.run(
            [sys.executable, "-IB", str(REVIEW), "--repo", self.worktree, "--against", "main",
             "--head", "topic", "--probe", probe, "--probe-module", "checkout", "--output", self.output,
             "--expect", choice], input="head\n", text=True, capture_output=True, env=self.env, timeout=30)

    def test_named_case_reviews_the_merge_base_and_verifies_the_chosen_test(self):
        base = self.commit({"src/checkout.py": "def url(params):\n    return '/?a=1'\n"}, "Base")
        self.git("switch", "-qc", "topic")
        head = self.commit({"src/checkout.py": "def url(params):\n    return '/' if params == {} else '/?a=1'\n"},
                           "Clear the query")
        self.git("switch", "-q", "main")
        self.commit({"src/checkout.py": "def url(params):\n    return '/other'\n"}, "Independent change")
        probe = self.directory / "query.py"
        probe.write_text('from checkout import url\nCASES = {"unspecified": None, "empty params": {}}\n'
                         'def probe(params):\n    return url(params)\n')
        run = self.branch_review(probe, "ask")
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("Case: empty params\nInput: probe({})", run.stdout)
        report = json.loads((self.output / "replay/report.json").read_bytes())
        self.assertEqual(report["base"]["sha"], base)
        self.assertEqual(report["head"]["sha"], head)
        self.assertEqual(report["findings"][0]["input"], {})
        self.assertIn("probe({})", (self.output / "test_undertow_regression.py").read_text())
        verification = json.loads((self.output / "verification.json").read_bytes())
        self.assertEqual((verification["verified"], verification["base"], verification["head"]), (True, "fail", "pass"))
        self.assertEqual(self.git("branch", "--show-current"), "main")

    def test_rejects_invalid_named_cases(self):
        probe = self.directory / "probe.py"
        probe.write_text('CASES = {"empty": {}, "empty": []}\ndef probe(value):\n    return value\n')
        report = self.replay("x = 1", "x = 2", "unavailable", exit_code=1, probe=(probe, "checkout"))
        self.assertIn("unique", report["notes"][0])

    def test_existing_numeric_probe_can_keep_its_own_cases_lookup(self):
        probe = self.directory / "probe.py"
        probe.write_text("from checkout import quote\nCASES = {1: 'empty'}\n"
                         "def probe(value: int):\n    return quote(CASES.get(value))\n")
        report = self.replay("def quote(value):\n    return value\n",
                             "def quote(value):\n    return value and 'changed'\n",
                             "observed_difference", probe=(probe, "checkout"))
        self.assertEqual(report["findings"][0]["input"], "1")

    def test_review_verifies_a_return_becoming_an_exception(self):
        self.commit({"checkout.py": "def quote(value):\n    return 10\n"}, "Base")
        self.git("switch", "-qc", "topic")
        self.commit({"checkout.py": "def quote(value):\n    raise ValueError('invalid')\n"}, "Reject")
        probe = self.directory / "probe.py"
        probe.write_text("from checkout import quote\ndef probe(value: int):\n    return quote(value)\n")
        run = self.branch_review(probe, "base")
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("pytest: base pass · head fail", run.stdout)

    def test_verification_rejects_an_unrelated_failure_under_pytest(self):
        self.commit({"checkout.py": "def quote(value):\n    return value\n"}, "Base")
        self.git("switch", "-qc", "topic")
        self.commit({"checkout.py": "import sys\ndef quote(value):\n"
                     "    if 'pytest' in sys.modules:\n        raise AssertionError('unrelated')\n"
                     "    return value + 1\n"}, "Head")
        probe = self.directory / "probe.py"
        probe.write_text("from checkout import quote\ndef probe(value: int):\n    return quote(value)\n")
        run = self.branch_review(probe, "base")
        self.assertEqual(run.returncode, 1, run.stdout + run.stderr)
        self.assertIn("pytest: base pass · head unavailable", run.stdout)
        self.assertFalse((self.output / "test_undertow_regression.py").exists())

    def replay(self, before, after, expected_status, exit_code=0, probe=None, path="src/checkout.py"):
        self.comparison({path: before}, {path: after})
        command = [sys.executable, "-IB", str(SCRIPT), "--repo", str(self.bare), "--base", self.base,
                   "--head", self.head, "--output", str(self.output)]
        if probe:
            probe_path, module = probe
            command.extend(["--probe", str(probe_path), "--probe-module", module])
        result = subprocess.run(command, env=self.env, text=True, capture_output=True, timeout=20)
        self.assertEqual(result.returncode, exit_code, result.stderr)
        report = self.read_report()
        self.assertEqual(report["status"], expected_status, report)
        return report

    def test_discovers_a_rounding_difference_without_tests_or_pytest(self):
        after = ROUNDING.replace("sum((price * RATE).quantize(CENT, rounding=ROUND_HALF_UP) for price in prices)",
                                 "(subtotal * RATE).quantize(CENT, rounding=ROUND_HALF_UP)")
        report = self.replay(ROUNDING, after, "observed_difference")
        finding, = report["findings"]
        self.assertEqual(finding["target"], "src/checkout.py::quote")
        self.assertEqual(finding["input"], ["1.05", "1.05"])
        self.assertEqual(finding["base"]["returned"], {"discount": "0.22", "amount_due": "1.88"})
        self.assertEqual(finding["head"]["returned"], {"discount": "0.21", "amount_due": "1.89"})

    def test_behavior_preserving_refactor_produces_no_finding(self):
        report = self.replay("def quote(value: int):\n    return value + 1\n",
                             "def quote(value: int):\n    return 1 + value\n", "no_observed_difference")
        self.assertEqual(report["findings"], [])

    def test_new_exception_and_import_failure_are_different_outcomes(self):
        report = self.replay("def quote(value: int):\n    return value\n",
                             "def quote(value: int):\n    if value == 500:\n        raise ValueError\n    return value\n",
                             "observed_difference")
        finding, = report["findings"]
        self.assertEqual((finding["input"], finding["base"], finding["head"]),
                         ("500", {"returned": 500}, {"raised": "ValueError"}))

    def test_import_failure_is_unavailable(self):
        report = self.replay("def quote(value: int):\n    return value\n",
                             "import nonexistent_fixture\ndef quote(value: int):\n    return value + 1\n",
                             "unavailable", exit_code=1)
        self.assertIn("head: the module could not be loaded", report["notes"][0])

    def test_probe_rejects_modules_outside_the_snapshot(self):
        probe = self.directory / "probe.py"
        probe.write_text("from statistics import mean\n"
                         "def probe(values: list[int]):\n    return mean(values)\n", encoding="utf-8")
        report = self.replay("def mean(values):\n    return 1\n", "def mean(values):\n    return 2\n",
                             "unavailable", exit_code=1, probe=(probe, "statistics"),
                             path="lib/statistics.py")
        self.assertEqual(report["findings"], [])
        self.assertIn("base: probe module 'statistics' was not loaded from the Git snapshot", report["notes"][0])

    def test_probe_continues_past_a_difference_that_needs_prior_inputs(self):
        probe = self.directory / "probe.py"
        probe.write_text("from checkout import quote\n"
                         "def probe(value: int):\n    return quote(value)\n", encoding="utf-8")
        before = "def quote(value):\n    return value\n"
        after = """seen = False

def quote(value):
    global seen
    previous = seen
    seen = True
    if value == 1 and previous:
        return 100
    if value == 2:
        return 200
    return value
"""
        report = self.replay(before, after, "observed_difference", probe=(probe, "checkout"))
        finding, = report["findings"]
        self.assertEqual((finding["input"], finding["base"], finding["head"]),
                         ("2", {"returned": 2}, {"returned": 200}))

    def test_probe_finding_exports_the_reviewed_outcome(self):
        probe = self.directory / "probe.py"
        probe.write_text("def probe(values: list[int]):\n"
                         "    from checkout import Checkout\n    return Checkout().quote(values)\n",
                         encoding="utf-8")
        before = "class Checkout:\n    def quote(self, values):\n        return sum(values)\n"
        report = self.replay(before, before.replace("sum(values)", "sum(values) + 1"),
                             "observed_difference", probe=(probe, "checkout"))
        finding, = report["findings"]
        self.assertEqual(report["probe_modules"], ["checkout"])
        self.assertEqual((finding["input"], finding["base"], finding["head"]),
                         (["1"], {"returned": 1}, {"returned": 2}))
        draft = self.directory / "test_probe.py"
        command = [sys.executable, "-IB", str(EXPORT), "--report", str(self.output / "report.json"),
                   "--probe", str(probe), "--expect", "base", "--output", str(draft)]
        subprocess.run(command, env=self.env, check=True, timeout=20)
        for revision, expected_exit in ((self.base, 0), (self.head, 1)):
            self.git("checkout", "--quiet", "--detach", revision)
            result = subprocess.run(
                [sys.executable, "-B", "-c", "import runpy, sys; runpy.run_path(sys.argv[1])['test_undertow_regression']()",
                 str(draft)], env={**self.env, "PYTHONPATH": str(self.worktree / "src")},
                capture_output=True, cwd=self.worktree, timeout=20, text=True)
            self.assertEqual(result.returncode, expected_exit, result.stderr)
            if expected_exit:
                self.assertIn("AssertionError", result.stderr)

        with probe.open("a", encoding="utf-8") as file:
            file.write("# changed since the finding\n")
        result = subprocess.run([*command[:-1], str(self.directory / "wrong.py")], env=self.env,
                                capture_output=True, timeout=20, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("differs from the one that produced this finding", result.stderr)

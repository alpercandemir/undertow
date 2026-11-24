import subprocess
import sys

from fixtures import ACTION, GitReviewTest


SCRIPT = ACTION.parent / "behavior-replay" / "discover.py"
EXPORT = SCRIPT.with_name("write_test.py")
ROUNDING = """from decimal import Decimal, ROUND_HALF_UP

RATE = Decimal("0.10")
CENT = Decimal("0.01")

def quote(prices: list[Decimal]):
    subtotal = sum(prices)
    discount = sum((price * RATE).quantize(CENT, rounding=ROUND_HALF_UP) for price in prices)
    return {"discount": discount, "amount_due": subtotal - discount}
"""


class BehaviorReplayTests(GitReviewTest):
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

"""Run the exported test against this snapshot and identify failures at its assertion."""

import json
import os
from pathlib import Path
import sys


def main():
    import pytest

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from run_cases import loaded_from_snapshot

    test = Path(sys.argv[1]).resolve()
    record = Path(sys.argv[2])
    observation = json.loads(sys.argv[3])
    modules = sys.argv[4:]
    root = Path.cwd()
    sys.path[:0] = [str(root / "src"), str(root)]

    class Result:
        call = None

        def __init__(self):
            self.phases = []

        def pytest_runtest_logreport(self, report):
            self.phases.append((report.when, "xfail" if hasattr(report, "wasxfail") else report.outcome))

        @pytest.hookimpl(tryfirst=True)
        def pytest_runtest_makereport(self, item, call):
            if call.when == "call":
                if call.excinfo is None:
                    self.call = "pass"
                elif call.excinfo.errisinstance(AssertionError):
                    frame = call.excinfo.traceback[-1]
                    if (Path(frame.path).resolve() == test and frame.name == "test_undertow_regression"
                            and "observed" in frame.locals):
                        actual = frame.locals["observed"]
                        if not observation["compares_exceptions"]:
                            actual = {"returned": actual}
                        if json.dumps(actual, sort_keys=True) == json.dumps(observation["outcome"], sort_keys=True):
                            self.call = "fail"

    result = Result()
    code = pytest.main(["-q", "--assert=plain", "--noconftest", "-c", os.devnull, "-p", "no:cacheprovider",
                        "--import-mode=importlib", str(test) + "::test_undertow_regression"], plugins=[result])
    if not all(loaded_from_snapshot(module, root) for module in modules):
        print("A declared project module did not load from this revision.", file=sys.stderr)
        return 2
    outcome = {("pass", 0): "passed", ("fail", 1): "failed"}.get((result.call, code))
    if not outcome or result.phases != [("setup", "passed"), ("call", outcome), ("teardown", "passed")]:
        return 1
    record.write_text(json.dumps(result.call), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())

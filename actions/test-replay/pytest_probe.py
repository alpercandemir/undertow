"""Execute one selected pytest case and record the phases pytest actually ran."""

import json
from pathlib import Path
import sys

import pytest


class Observation:
    def __init__(self):
        self.collected = []
        self.phases = []

    def pytest_collection_finish(self, session):
        self.collected = [item.nodeid for item in session.items]

    @pytest.hookimpl(wrapper=True, tryfirst=True)
    def pytest_runtest_makereport(self, item, call):
        report = yield
        phase = {"name": report.when, "node_id": item.nodeid, "outcome": report.outcome}
        if hasattr(report, "wasxfail"):
            phase["outcome"] = "expected_failure"
        if report.failed and call.excinfo and call.when == "call":
            if call.excinfo.errisinstance(AssertionError):
                phase["outcome"] = "assertion_failed"
                phase["assertion"] = str(call.excinfo.value)[:2000]
        self.phases.append(phase)
        return report


def main():
    output, node_id, raw_args = sys.argv[1:]
    root = Path.cwd()
    sys.path[:0] = [str(root), str(root / "src")]
    observation = Observation()
    exit_code = pytest.main(
        [*json.loads(raw_args), "--rootdir", str(root), node_id],
        plugins=[observation],
    )
    Path(output).write_text(json.dumps(vars(observation)), encoding="utf-8")
    return int(exit_code)


if __name__ == "__main__":
    sys.exit(main())

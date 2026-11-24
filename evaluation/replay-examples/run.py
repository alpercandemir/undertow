"""Replay the README's seeded regressions in small, reproducible Git repositories."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from xml.etree import ElementTree


EXAMPLES = Path(__file__).resolve().parent
REPLAY = EXAMPLES.parents[1] / "actions" / "behavior-replay"
CANDIDATES = {
    "refunds": "Normalize refund webhook deduplication",
    "invoice-export": "Use the page end as the export checkpoint",
    "departure-board": "Filter crossings using port-local time",
}
GIT_ENV = {
    "PATH": os.environ.get("PATH", os.defpath),
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_AUTHOR_NAME": "Undertow examples",
    "GIT_AUTHOR_EMAIL": "examples@example.invalid",
    "GIT_AUTHOR_DATE": "2026-01-01T00:00:00Z",
    "GIT_COMMITTER_NAME": "Undertow examples",
    "GIT_COMMITTER_EMAIL": "examples@example.invalid",
    "GIT_COMMITTER_DATE": "2026-01-01T00:00:00Z",
}
TEST_ENV = {
    **os.environ,
    "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
    "PYTEST_ADDOPTS": "",
    "PYTHONPATH": "",
}


def git(project, *arguments):
    return subprocess.check_output(
        ["git", "-C", project, *arguments], env=GIT_ENV, text=True, timeout=30,
    ).strip()


def run_pytest(project, xml_path, *files, expected_failures=()):
    result = subprocess.run(
        [sys.executable, "-B", "-m", "pytest", "-q", "-p", "no:cacheprovider",
         "--junitxml", xml_path, *files],
        cwd=project, env=TEST_ENV, timeout=60, check=False,
    )
    cases = ElementTree.parse(xml_path).findall(".//testcase")
    failures = tuple(case.get("name") for case in cases if case.find("failure") is not None)
    if (not cases or result.returncode != int(bool(expected_failures))
            or failures != expected_failures
            or any(case.find("error") is not None or case.find("skipped") is not None for case in cases)):
        raise RuntimeError(f"Unexpected pytest results in {xml_path}")
    return {"passed": len(cases) - len(failures), "failed": list(failures)}


def replay_case(case, candidate_message, output):
    fixture = EXAMPLES / case
    output.mkdir()
    project = output / "project"
    shutil.copytree(
        fixture / "source", project, ignore=shutil.ignore_patterns("__pycache__", ".pytest_cache"),
    )
    git(project, "init", "-q", "--object-format=sha1")
    git(project, "add", "--all")
    git(project, "commit", "-qm", "Baseline")
    base = git(project, "rev-parse", "HEAD")
    existing = {"base": run_pytest(project, output / "base-existing.xml", "existing_tests.py")}

    git(project, "apply", "--index", fixture / "candidate.patch")
    git(project, "commit", "-qm", candidate_message)
    head = git(project, "rev-parse", "HEAD")
    git(project, "diff", "--exit-code", base, head, "--", "existing_tests.py")
    existing["head"] = run_pytest(project, output / "head-existing.xml", "existing_tests.py")

    probe = fixture / "probe.py"
    report = output / "replay" / "report.json"
    test = output / "regression.py"
    subprocess.run(
        [sys.executable, "-IB", REPLAY / "discover.py", "--repo", project, "--base", base,
         "--head", head, "--probe", probe, "--probe-module", case.replace("-", "_"),
         "--output", report.parent],
        check=True, timeout=60,
    )
    subprocess.run(
        [sys.executable, "-IB", REPLAY / "write_test.py", "--report", report, "--probe", probe,
         "--expect", "base", "--output", test],
        check=True, timeout=30,
    )

    expanded = {}
    for side, revision in (("base", base), ("head", head)):
        git(project, "checkout", "-q", "--detach", revision)
        expanded[side] = run_pytest(
            project, output / f"{side}-expanded.xml", "existing_tests.py", test,
            expected_failures=("test_undertow_regression",) if side == "head" else (),
        )
        if expanded[side]["passed"] != existing[side]["passed"] + int(side == "base"):
            raise RuntimeError(f"Unexpected change to the test count for {case} on {side}")
    return {"existing_suite": existing, "with_exported_test": expanded}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="A new output directory")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True)
    results = {}
    for case, message in CANDIDATES.items():
        print(f"\n{case}", flush=True)
        results[case] = replay_case(case, message, output / case)
    (output / "verification.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

"""Run the frozen Click option pilot behind each revision's existing pytest suite."""

import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import time

import pytest

from run import ROOT, cases_from, digest, engine_environment, read, write


HERE = Path(__file__).resolve().parent
MANIFEST = HERE / "click-options-pilot.json"
PROBE = HERE / "probes" / "click_options.py"
REPLAY = ROOT / "actions" / "behavior-replay" / "discover.py"
MODULES = ("click.core", "click.testing")


def test_snapshot(repo, revision, output, env):
    with tempfile.TemporaryDirectory(prefix="undertow-click-options-") as directory:
        with tempfile.TemporaryFile() as archive:
            subprocess.run(["git", "-C", str(repo), "archive", revision],
                           stdout=archive, env=env, check=True, timeout=30)
            archive.seek(0)
            with tarfile.open(fileobj=archive) as tree:
                tree.extractall(directory, filter="data")
        command = [sys.executable, "-B", "-m", "pytest", "-q"]
        result = subprocess.run(command, cwd=directory, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120,
                                env={**env, "PYTHONPATH": "src", "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1"})
    (output / f"{revision}-pytest.log").write_text(result.stdout, encoding="utf-8")
    summary = result.stdout.strip().splitlines()
    return {"exit_code": result.returncode, "summary": summary[-1] if summary else ""}


def run_replay(repo, case, output, env):
    command = [sys.executable, "-IB", str(REPLAY), "--repo", str(repo), "--base", case["base"],
               "--head", case["head"], "--probe", str(PROBE), "--output", str(output)]
    for module in MODULES:
        command.extend(["--probe-module", module])
    started = time.monotonic()
    result = subprocess.run(command, env=env, stdout=subprocess.DEVNULL, timeout=80)
    if result.returncode not in (0, 1):
        result.check_returncode()
    report = read(output / "report.json")
    return {"report": report, "seconds": round(time.monotonic() - started, 3)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    repo = args.repo.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifest = read(MANIFEST)
    cases = cases_from(manifest)
    env = engine_environment("local", None)

    suites, results = {}, {}
    for case_id, case in cases.items():
        for side in ("base", "head"):
            revision = case[side]
            if revision not in suites:
                suites[revision] = test_snapshot(repo, revision, output, env)
        passed = all(suites[case[side]]["exit_code"] == 0 for side in ("base", "head"))
        results[case_id] = run_replay(repo, case, output / case_id, env) if passed else {}
        state = results[case_id]["report"]["status"] if passed else "suite_failed"
        print(f"{case_id}: {state}", flush=True)

    source_files = (PROBE, REPLAY, REPLAY.with_name("run_cases.py"))
    write(output / "results.json", {
        "manifest_sha256": digest(manifest),
        "source_sha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in source_files},
        "python": sys.version.split()[0], "pytest": pytest.__version__, "platform": sys.platform,
        "suites": suites, "cases": results,
    })


if __name__ == "__main__":
    main()

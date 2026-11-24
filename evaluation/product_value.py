"""Measure runtime review on the existing labeled Click and HTTPX corpus."""

import argparse
from concurrent.futures import ThreadPoolExecutor
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
MANIFESTS = ("historical.json", "click-stdin-regression.json")
REPLAY = ROOT / "actions" / "behavior-replay" / "discover.py"
PROBES = {
    "pallets/click": (("click_options", ("click.core", "click.testing")),),
    "encode/httpx": (
        ("httpx_request", ("httpx._models",)),
        ("httpx_json", ("httpx._models",)),
        ("httpx_stream", ("httpx._models",)),
    ),
}


def test_snapshot(repo, revision, output, env, test_flags):
    with tempfile.TemporaryDirectory(prefix="undertow-value-") as directory:
        with tempfile.TemporaryFile() as archive:
            subprocess.run(["git", "-C", str(repo), "archive", revision],
                           stdout=archive, env=env, check=True, timeout=30)
            archive.seek(0)
            with tarfile.open(fileobj=archive) as tree:
                tree.extractall(directory, filter="data")
        command = [sys.executable, "-B", "-m", "pytest", "-q", *test_flags]
        try:
            run = subprocess.run(command, cwd=directory, text=True, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, timeout=30,
                                 env={**env, "PYTHONPATH": "src:.", "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1"})
        except subprocess.TimeoutExpired as error:
            output.write_bytes(error.stdout or b"")
            return {"exit_code": None, "summary": "Timed out after 30 seconds"}
    output.write_text(run.stdout, encoding="utf-8")
    lines = run.stdout.strip().splitlines()
    return {"exit_code": run.returncode, "summary": lines[-1] if lines else ""}


def replay(repo, case, output, env, probe=None):
    command = [sys.executable, "-IB", str(REPLAY), "--repo", str(repo),
               "--base", case["base"], "--head", case["head"], "--output", str(output)]
    if probe:
        name, modules = probe
        command.extend(["--probe", str(HERE / "probes" / f"{name}.py")])
        for module in modules:
            command.extend(["--probe-module", module])
    start = time.monotonic()
    process = subprocess.run(command, env=env, stdout=subprocess.DEVNULL, timeout=80)
    if process.returncode not in {0, 1}:
        process.check_returncode()
    return {"seconds": round(time.monotonic() - start, 3), "report": read(output / "report.json")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repos", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--jobs", default=3, type=int)
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive.")
    repos, output = args.repos.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    logs = output / "test-logs"
    logs.mkdir()
    manifests = {name: read(HERE / name) for name in MANIFESTS}
    cases = {key: case for manifest in manifests.values() for key, case in cases_from(manifest).items()}
    env = engine_environment("local", None)
    flags = {"pallets/click": (), "encode/httpx": ("-p", "anyio.pytest_plugin", "-m", "not network")}
    revisions = dict.fromkeys((case["repository"], case[side])
                             for case in cases.values() for side in ("base", "head"))

    def suite(entry):
        repository, sha = entry
        result = test_snapshot(repos / f"{repository}.git", sha, logs / f"{sha}.log", env, flags[repository])
        print(f"{repository} {sha[:8]} pytest={result['exit_code']}", flush=True)
        return sha, result

    # Historical suites can share a fixed pager file or a fixed loopback port.
    def repository_suites(repository):
        return dict(suite(entry) for entry in revisions if entry[0] == repository)

    suites = {}
    with ThreadPoolExecutor(max_workers=min(args.jobs, len(flags))) as pool:
        for batch in pool.map(repository_suites, flags):
            suites.update(batch)

    def compare(entry):
        case_id, case = entry
        repo = repos / f"{case['repository']}.git"
        plan = (("default", None), *((probe[0], probe) for probe in PROBES[case["repository"]]))
        runs = {name: replay(repo, case, output / case_id / name, env, probe) for name, probe in plan}
        print(case_id, *(f"{name}={row['report']['status']}" for name, row in runs.items()), flush=True)
        return case_id, runs

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = dict(pool.map(compare, cases.items()))

    sources = [REPLAY, REPLAY.with_name("run_cases.py")]
    sources.extend(HERE / "probes" / f"{name}.py" for probes in PROBES.values() for name, _ in probes)
    write(output / "results.json", {
        "manifests": {name: digest(manifest) for name, manifest in manifests.items()},
        "sources": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in sources},
        "python": sys.version.split()[0], "platform": sys.platform, "pytest": pytest.__version__,
        "jobs": args.jobs, "test_flags": flags, "suites": suites, "cases": results,
    })


if __name__ == "__main__":
    main()

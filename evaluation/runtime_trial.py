#!/usr/bin/env python3
"""Replay the trial's reviewed probes against trusted, pinned Git snapshots."""

import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import time

from run import ENGINE, ROOT, engine_environment, finding_ids, read, run_case, write
from runtime_probes import PROBES


HERE = Path(__file__).resolve().parent
SHOWCASE = "alpercandemir/undertow-showcase"
SHOWCASE_CASES = {
    "showcase-5": ("7f831b0d911228ba608bfb9f9ab553933190348e",
                   "1aee946a9ad10474b6971acc909fd29b6d70f261", {"order_total"}),
    "showcase-6": ("68067acaf97cba47dfd66788cb21980612718bdc",
                   "856a37f063cb9ad4cf90a76323a32009991a3568",
                   {"order_total", "days_since_delivery", "months", "attempts"}),
}


def numeric_inputs(report, names):
    values = {}
    for finding in report["findings"]:
        match = re.fullmatch(r"(\w+) = (-?[0-9]+)", finding["example"]["input"] or "")
        if match and match[1] in names:
            if match[1] in values:
                raise ValueError(f"Multiple findings supply {match[1]}.")
            values[match[1]] = int(match[2])
    if values.keys() != names:
        raise ValueError(f"Missing inputs from the report: {sorted(names - values.keys())}.")
    return values


def observe(case_id, repo, sha, inputs, env):
    with tempfile.TemporaryDirectory(prefix="undertow-runtime-") as directory:
        archive = subprocess.check_output(["git", "-C", str(repo), "archive", sha], env=env,
                                          stderr=subprocess.PIPE, timeout=30)
        with tarfile.open(fileobj=io.BytesIO(archive)) as tree:
            tree.extractall(directory, filter="data")
        command = [sys.executable, "-IB", str(HERE / "runtime_probes.py"), case_id,
                   directory, json.dumps(inputs)]
        samples = []
        for _ in range(2):
            result = subprocess.run(command, cwd=directory, env=env, text=True,
                                    capture_output=True, timeout=15, check=True)
            samples.append(json.loads(result.stdout))
        if samples[0] != samples[1]:
            raise ValueError(f"The independent runs at {sha} disagreed: {samples!r}")
        return samples[0]


def trial_case(case_id, cases, repos, showcase_repo, output, env):
    if case_id in SHOWCASE_CASES:
        base, head, inputs = SHOWCASE_CASES[case_id]
        case = {"base": base, "head": head, "source_url": f"https://github.com/{SHOWCASE}"}
        repo = showcase_repo
        report_dir = output / case_id
        result = run_case(ROOT / ENGINE, case, repo, report_dir, env)
        if result["status"] != "findings":
            raise ValueError(f"Undertow did not produce the expected report: {result['status']}.")
        report = read(report_dir / "report.json")
        values = numeric_inputs(report, inputs)
    else:
        case = cases[case_id]
        repo = repos / (case["repository"] + ".git")
        report_dir = HERE / "results" / "historical-gemini" / case_id
        report = read(report_dir / "report.json")
        values = {}
    if any(report[side] != case[side] for side in ("base", "head")):
        raise ValueError("The report revisions do not match the trial revisions.")

    result = {"mode": report["mode"], "source_url": case["source_url"],
              "inputs_from_report": values,
              "findings": [{"id": finding["id"], "title": finding["title"],
                            "example": finding["example"], "fingerprint": fingerprint}
                           for finding, fingerprint in zip(report["findings"], finding_ids(report), strict=True)]}
    for side in ("base", "head"):
        result[side] = {"sha": case[side], "observed": observe(case_id, repo, case[side], values, env)}
    result["comparison"] = ("same" if result["base"]["observed"] == result["head"]["observed"] else "different")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repos", type=Path, required=True, help="Local benchmark bare repositories.")
    parser.add_argument("--showcase-repo", type=Path, required=True, help="Local showcase bare repository.")
    parser.add_argument("--output", type=Path, required=True, help="Directory for new trial results.")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    cases = {case["id"]: case for case in read(HERE / "historical.json")["cases"]}
    env = engine_environment("local", None)
    repos = args.repos.resolve()
    showcase_repo = args.showcase_repo.resolve()
    results = {}
    for case_id in PROBES:
        started = time.monotonic()
        try:
            result = trial_case(case_id, cases, repos, showcase_repo, args.output, env)
        except (OSError, ValueError, subprocess.SubprocessError, tarfile.TarError) as error:
            stderr = getattr(error, "stderr", None)
            if isinstance(stderr, bytes):
                stderr = stderr.decode(errors="replace")
            result = {"comparison": "unavailable", "error": (stderr or str(error)).strip()}
        result["seconds"] = round(time.monotonic() - started, 3)
        results[case_id] = result
        print(f"{case_id}: {result['comparison']} ({result['seconds']:.2f}s)")
    write(args.output / "observations.json", {"schema": 1, "python": sys.version.split()[0],
                                            "repeats_per_revision": 2, "cases": results})
    return int(any(case["comparison"] == "unavailable" for case in results.values()))


if __name__ == "__main__":
    sys.exit(main())

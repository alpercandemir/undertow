"""Review a branch against its merge base and keep a verified pytest test."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from discover import SHA, git_snapshot, invocation


def revisions(repo, against, head):
    git = ["git", "-C", repo]

    def commit(ref):
        result = subprocess.run([*git, "rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}"],
                                text=True, capture_output=True, timeout=15)
        sha = result.stdout.strip()
        if result.returncode or not SHA.fullmatch(sha):
            raise ValueError(f"Cannot resolve {ref!r} to a local commit.")
        return sha

    candidate = commit(head)
    result = subprocess.run([*git, "merge-base", "--all", commit(against), candidate],
                            text=True, capture_output=True, timeout=15)
    bases = result.stdout.splitlines()
    if result.returncode or len(bases) != 1:
        raise ValueError("Expected one merge base. Check both refs and fetch their full history.")
    return bases[0], candidate


def choose_expectation(choice):
    if choice != "ask":
        return choice
    while True:
        try:
            selected = input("Keep base, head, or skip the export? [base/head/skip] ").strip().lower()
        except EOFError:
            return None
        if selected in {"base", "head"}:
            return selected
        if selected in {"", "skip"}:
            return None


def verify(test, report, repo, output, directory):
    env = {"PATH": os.environ.get("PATH", os.defpath), "LC_ALL": "C.UTF-8",
           "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1"}
    outcomes = {}
    finding, = report["findings"]
    compares_exceptions = any("raised" in finding[side] for side in ("base", "head"))
    for side in ("base", "head"):
        snapshot = directory / side
        git_snapshot(repo, report[side]["sha"], snapshot, env)
        record = directory / f"{side}-pytest.json"
        with (output / f"pytest-{side}.log").open("wb") as log:
            try:
                observation = json.dumps({"outcome": finding[side], "compares_exceptions": compares_exceptions})
                run = subprocess.run([sys.executable, "-IB", HERE / "verify_test.py", test, record,
                                      observation, *report["probe_modules"]],
                                     cwd=snapshot, env=env, stdin=subprocess.DEVNULL, stdout=log,
                                     stderr=subprocess.STDOUT, timeout=30)
                outcomes[side] = (json.loads(record.read_bytes()) if run.returncode == 0 and record.is_file()
                                  else "unavailable")
            except subprocess.TimeoutExpired:
                outcomes[side] = "timeout"
    return outcomes


def review(args):
    base, head = revisions(args.repo, args.against, args.head)
    if args.output:
        output = args.output.resolve()
        output.mkdir(parents=True)
    else:
        parent = Path(".undertow").resolve()
        parent.mkdir(exist_ok=True)
        output = Path(tempfile.mkdtemp(prefix="review-", dir=parent))
    evidence = output / "replay"
    print(f"Review {base[:8]} → {head[:8]}\nEvidence directory: {output}", flush=True)
    modules = [arg for module in args.probe_module for arg in ("--probe-module", module)]
    run = subprocess.run([sys.executable, "-IB", HERE / "discover.py", "--repo", args.repo, "--base", base,
                          "--head", head, "--probe", args.probe, *modules, "--output", evidence],
                         stdout=subprocess.DEVNULL)
    if not (evidence / "report.json").is_file():
        return run.returncode or 1
    report = json.loads((evidence / "report.json").read_bytes())
    if not report["findings"]:
        print("No difference observed for the compared inputs." if report["status"] == "no_observed_difference"
              else "Comparison unavailable.", *report["notes"], sep="\n")
        return run.returncode

    finding, = report["findings"]
    if "case" in finding:
        print(f"Case: {finding['case']}")
    print(f"Input: {invocation(finding)}")
    for side in ("base", "head"):
        print(f"{side}: {json.dumps(finding[side], ensure_ascii=False)}")
    expect = choose_expectation(args.expect)
    if expect is None:
        return 0

    with tempfile.TemporaryDirectory() as scratch:
        directory = Path(scratch)
        test = directory / "test_undertow_regression.py"
        subprocess.run([sys.executable, "-IB", HERE / "write_test.py", "--report", evidence / "report.json",
                        "--probe", args.probe, "--expect", expect, "--output", test], check=True)
        outcomes = verify(test, report, args.repo, output, directory)
        verified = outcomes[expect] == "pass" and set(outcomes.values()) == {"pass", "fail"}
        record = {"expect": expect, "verified": verified, "base_sha": base, "head_sha": head, **outcomes}
        (output / "verification.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        print(f"pytest: base {outcomes['base']} · head {outcomes['head']}")
        if not verified:
            print(f"Verification failed. See pytest-base.log and pytest-head.log in {output}.", file=sys.stderr)
            return 1
        destination = output / test.name
        with destination.open("xb") as saved:
            saved.write(test.read_bytes())
        print(f"Verified test: {destination}")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("."), help="Trusted Git repository (default: current).")
    parser.add_argument("--against", required=True, help="Base branch, tag, or commit.")
    parser.add_argument("--head", default="HEAD", help="Branch, tag, or commit to review (default: HEAD).")
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--probe-module", action="append", required=True, metavar="MODULE")
    parser.add_argument("--expect", choices=("base", "head", "ask"), default="ask" if sys.stdin.isatty() else None,
                        help="Export the chosen outcome. Interactive reviews ask after showing both results.")
    parser.add_argument("--output", type=Path, help="New output directory (default: a fresh run under .undertow/).")
    args = parser.parse_args()
    try:
        return review(args)
    except (OSError, ValueError, subprocess.SubprocessError, tarfile.TarError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    sys.exit(main())

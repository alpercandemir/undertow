#!/usr/bin/env python3
"""Replay one pytest case from head against two trusted Git revisions."""

import argparse
import hashlib
import html
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import sys
import tarfile
import tempfile


PROBE = Path(__file__).with_name("pytest_probe.py").resolve()
SHA = re.compile(r"[0-9a-f]{40}")
OUTCOMES = {
    ("assertion_failed", "passed"): "passes_on_head_only",
    ("passed", "assertion_failed"): "passes_on_base_only",
    ("passed", "passed"): "passes_on_both",
    ("assertion_failed", "assertion_failed"): "assertion_failed_on_both",
}


def test_path(node_id):
    path, separator, selector = node_id.partition("::")
    relative = PurePosixPath(path)
    if (not separator or not selector or not path.endswith(".py") or path.startswith("-")
            or relative.is_absolute() or ".." in relative.parts or path != relative.as_posix()
            or any(ord(character) < 32 for character in node_id)):
        raise ValueError("Select one pytest case as a repository-relative file.py::test_name.")
    return relative


def path_in_snapshot(root, relative):
    path = root
    for part in relative.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError("The selected test path traverses a symbolic link.")
    if path.exists() and not path.is_file():
        raise ValueError("The selected test path must be a regular file.")
    return path


def git_snapshot(repo, revision, directory, env):
    directory.mkdir()
    with tempfile.TemporaryFile() as archive:
        subprocess.run(["git", "-C", str(repo), "archive", "--format=tar", f"{revision}^{{commit}}"],
                       env=env, stdout=archive, stderr=subprocess.PIPE, check=True, timeout=60)
        archive.seek(0)
        with tarfile.open(fileobj=archive) as tree:
            tree.extractall(directory, filter="data")


def phase_outcome(record, node_id, exit_code):
    phases = record["phases"]
    if (record["collected"] != [node_id]
            or [phase["name"] for phase in phases] != ["setup", "call", "teardown"]
            or any(phase["node_id"] != node_id for phase in phases)
            or phases[0]["outcome"] != "passed" or phases[2]["outcome"] != "passed"):
        return {"status": "unavailable", "reason": "Expected one completed test with successful setup and teardown."}

    call = phases[1]
    observed = {"observations": call["observations"]} if call.get("observations") else {}
    if call["outcome"] == "passed" and exit_code == 0:
        return {"status": "passed", **observed}
    if call["outcome"] == "assertion_failed" and exit_code == 1:
        return {"status": "assertion_failed", "assertion": call["assertion"], **observed}
    return {"status": "unavailable", "reason": f"Test call reported {call['outcome']} with pytest exit code {exit_code}."}


def observe(snapshot, record, log, args, env):
    command = [sys.executable, "-IB", str(PROBE), str(record), args.test, json.dumps(args.pytest_args)]
    with log.open("wb") as output:
        with subprocess.Popen(command, cwd=snapshot, env=env, stdin=subprocess.DEVNULL,
                              stdout=output, stderr=subprocess.STDOUT, start_new_session=True) as process:
            try:
                exit_code = process.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                return {"status": "unavailable", "reason": "The test exceeded the time limit."}
    if not record.is_file():
        return {"status": "unavailable", "reason": f"Pytest exited with code {exit_code} before recording an observation."}
    return phase_outcome(json.loads(record.read_text(encoding="utf-8")), args.test, exit_code)


def replay(args, relative, directory):
    env = {"PATH": os.environ.get("PATH", os.defpath), "LC_ALL": "C.UTF-8",
           "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}
    base = directory / "base"
    head = directory / "head"
    git_snapshot(args.repo, args.base, base, env)
    git_snapshot(args.repo, args.head, head, env)
    test = path_in_snapshot(head, relative).read_bytes()
    base_test = path_in_snapshot(base, relative)
    base_test.parent.mkdir(parents=True, exist_ok=True)
    base_test.write_bytes(test)

    results = {}
    for side, snapshot in (("base", base), ("head", head)):
        try:
            results[side] = observe(snapshot, directory / f"{side}.json",
                                    args.output / f"{side}.log", args, env)
        except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
            results[side] = {"status": "unavailable", "reason": "Could not read a valid pytest observation."}
    results["test_sha256"] = hashlib.sha256(test).hexdigest()
    statuses = results["base"]["status"], results["head"]["status"]
    results["status"] = OUTCOMES.get(statuses, "unavailable")
    return results


def summary(report):
    status = report["status"].replace("_", " ").capitalize()
    lines = ["## Undertow test replay", "", f"**{status}.**", "",
             f"<code>{html.escape(report['test'])}</code>", ""]
    if "reason" in report:
        lines += [report["reason"], ""]
    for side in ("base", "head"):
        result = report[side]
        outcome = result["status"].replace("_", " ")
        lines += [f"- **{side.capitalize()}** · `{result['sha']}` · {outcome}"]
        if result.get("reason"):
            lines.append(f"  {result['reason']}")
        if result.get("assertion"):
            lines.extend(["", *(f"      {line}" for line in result["assertion"].splitlines()), ""])
    lines += ["", "This result describes the selected test and its assertions.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--base", required=True, help="Full SHA for the comparison base.")
    parser.add_argument("--head", required=True, help="Full SHA for the candidate revision.")
    parser.add_argument("--test", required=True, help="One exact pytest node ID from head.")
    parser.add_argument("--pytest-args", default="[]", help="JSON array of additional pytest arguments.")
    parser.add_argument("--timeout", type=int, default=120, help="Seconds allowed per revision.")
    parser.add_argument("--output", type=Path, required=True, help="New directory for reports and pytest logs.")
    args = parser.parse_args()
    try:
        relative = test_path(args.test)
        args.pytest_args = json.loads(args.pytest_args)
        if (not SHA.fullmatch(args.base) or not SHA.fullmatch(args.head) or args.timeout <= 0
                or not isinstance(args.pytest_args, list)
                or any(not isinstance(arg, str) for arg in args.pytest_args)):
            raise ValueError("Use full Git SHAs, a positive timeout and an array of pytest argument strings.")
        args.output.mkdir(parents=True)
    except (OSError, ValueError) as error:
        parser.error(str(error))

    try:
        with tempfile.TemporaryDirectory(prefix="undertow-test-replay-") as directory:
            report = replay(args, relative, Path(directory))
    except (OSError, ValueError, subprocess.SubprocessError, tarfile.TarError, tarfile.FilterError):
        report = {"status": "unavailable",
                  "reason": "Could not prepare both snapshots with the selected test file from head.",
                  "base": {"status": "unavailable"}, "head": {"status": "unavailable"}}
    report.update(schema_version=1, test=args.test, pytest_args=args.pytest_args)
    for side in ("base", "head"):
        report[side]["sha"] = getattr(args, side)
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    markdown = summary(report)
    (args.output / "summary.md").write_text(markdown, encoding="utf-8")
    print(markdown, end="")
    return int(report["status"] == "unavailable")


if __name__ == "__main__":
    sys.exit(main())

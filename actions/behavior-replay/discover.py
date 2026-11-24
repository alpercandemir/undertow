"""Find a reproducible behavior difference in supported Python functions."""

import argparse
import ast
from decimal import Decimal
import hashlib
import html
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tarfile
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "test-replay"))
from replay import SHA, git_snapshot, path_in_snapshot


PROBE = Path(__file__).with_name("run_cases.py").resolve()
SCALAR_TYPES = {"int", "float", "Decimal"}
MAX_FILES = 12
MAX_TARGETS = 6


def functions(path):
    if path.stat().st_size > 128 * 1024:
        return {}
    return {node.name: node for node in ast.parse(path.read_bytes()).body
            if isinstance(node, ast.FunctionDef) and not node.name.startswith("_")}


def parameter_type(function):
    args = function.args
    positional = args.posonlyargs + args.args
    if (len(positional) != 1 or args.vararg or args.kwonlyargs or args.kwarg
            or function.decorator_list):
        return None
    annotation = positional[0].annotation
    if isinstance(annotation, ast.Name) and annotation.id in SCALAR_TYPES:
        return annotation.id
    if (isinstance(annotation, ast.Subscript) and isinstance(annotation.value, ast.Name)
            and annotation.value.id == "list" and isinstance(annotation.slice, ast.Name)
            and annotation.slice.id in SCALAR_TYPES):
        return f"list[{annotation.slice.id}]"
    return None


def executable_body(function):
    body = function.body
    if ast.get_docstring(function) is not None:
        body = body[1:]
    return tuple(ast.dump(node) for node in body)


def targets(repo, base, head, snapshots, env):
    changed = subprocess.check_output(
        ["git", "-C", str(repo), "diff", "--no-renames", "--name-only", "--diff-filter=M",
         "-z", base, head, "--"], env=env, timeout=10,
    )
    paths = []
    for name in os.fsdecode(changed).split("\0"):
        path = Path(name)
        if path.suffix != ".py" or path.name in {"__init__.py", "conftest.py"}:
            continue
        if any(not part.isidentifier() or part in {"test", "tests"} or part.startswith("test_")
               for part in (*path.parts[:-1], path.stem)) or path.stem.endswith("_test"):
            continue
        paths.append(path)
    for path in paths[:MAX_FILES]:
        try:
            old, new = (functions(path_in_snapshot(snapshot, path)) for snapshot in snapshots)
        except (OSError, SyntaxError, ValueError):
            continue
        for name, after in new.items():
            before = old.get(name)
            kind = parameter_type(after)
            if kind and before and kind == parameter_type(before) and executable_body(before) != executable_body(after):
                yield path, before, after, kind


def cases(kind, before, after):
    if kind.startswith("list["):
        numbers = range(1, 11) if kind == "list[int]" else (
            Decimal(cents) / 100 for cents in (*range(100, 200, 5), *range(101, 200))
        )
        values = list(dict.fromkeys(str(number) for number in numbers))
        return [[number] * size for size in (1, 2, 3) for number in values]

    step = Decimal("0.01") if kind != "int" else 1
    values = []
    for function in (before, after):
        for node in ast.walk(function):
            if isinstance(node, ast.Compare):
                for operand in (node.left, *node.comparators):
                    if type(getattr(operand, "value", None)) in {int, float}:
                        number = Decimal(str(operand.value))
                        if number.is_finite() and abs(number) <= 10**9:
                            values.extend((number, number - step, number + step))
    values.extend((0, 1, -1, 2, 10, 100))
    if kind == "int":
        values = [int(value) for value in values if value == int(value)]
    return list(dict.fromkeys(str(value) for value in values))[:40]


def observe(snapshot, request, env, deadline):
    with tempfile.TemporaryDirectory(dir=snapshot.parent) as directory:
        request_path = Path(directory) / "request.json"
        result_path = Path(directory) / "result.json"
        request_path.write_text(json.dumps(request), encoding="utf-8")
        command = [sys.executable, "-IB", str(PROBE), str(request_path), str(result_path)]
        with subprocess.Popen(command, cwd=snapshot, env=env, stdin=subprocess.DEVNULL,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                              start_new_session=True) as process:
            try:
                process.wait(timeout=max(0, min(8, deadline - time.monotonic())))
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                return None, "execution exceeded the time limit"
        if process.returncode or not result_path.is_file() or result_path.stat().st_size > 256 * 1024:
            return None, "the module could not be loaded or the probe did not finish"
        try:
            values = json.loads(result_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            values = None
        if not isinstance(values, list) or len(values) != len(request["inputs"]):
            return None, "the probe did not produce comparable results"
        return values, None


def same(left, right):
    return json.dumps(left, sort_keys=True) == json.dumps(right, sort_keys=True)


def compare(snapshots, request, env, deadline):
    runs = []
    for snapshot in snapshots:
        first, reason = observe(snapshot, request, env, deadline)
        if reason:
            return None, f"{snapshot.name}: {reason}"
        second, reason = observe(snapshot, request, env, deadline)
        if reason:
            return None, f"{snapshot.name}: {reason}"
        runs.append((first, second))

    compared = False
    for value, base, base_repeat, head, head_repeat in zip(request["inputs"], *runs[0], *runs[1], strict=True):
        if (base is None or head is None or not same(base, base_repeat) or not same(head, head_repeat)
                or ("raised" in base and "raised" in head)):
            continue
        if same(base, head):
            compared = True
            continue
        isolated = {**request, "inputs": [value]}
        isolated_base, base_reason = observe(snapshots[0], isolated, env, deadline)
        isolated_head, head_reason = observe(snapshots[1], isolated, env, deadline)
        if base_reason or head_reason:
            side, reason = ("base", base_reason) if base_reason else ("head", head_reason)
            return None, f"{side}: {reason}"
        if same(isolated_base, [base]) and same(isolated_head, [head]):
            return {"input": value, "base": base, "head": head}, None
        return None, "the difference did not reproduce with the input alone"
    return None, None if compared else "no stable, comparable result"


def inspect(args, directory):
    env = {"PATH": os.environ.get("PATH", os.defpath), "LC_ALL": "C.UTF-8",
           "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}
    snapshots = tuple(directory / side for side in ("base", "head"))
    for sha, snapshot in zip((args.base, args.head), snapshots):
        git_snapshot(args.repo, sha, snapshot, env)
    findings, notes = [], []
    deadline = time.monotonic() + 45
    selected = list(targets(args.repo, args.base, args.head, snapshots, env))[:MAX_TARGETS]
    for path, before, after, kind in selected:
        label = f"{path.as_posix()}::{after.name}"
        if time.monotonic() >= deadline:
            notes.append("The comparison reached its time limit.")
            break
        request = {"path": path.as_posix(), "name": after.name, "type": kind,
                   "inputs": cases(kind, before, after)}
        finding, reason = compare(snapshots, request, env, deadline)
        if finding:
            findings.append({"target": label, "type": kind, **finding})
        if reason:
            notes.append(f"{label}: {reason}.")
    if findings:
        status = "observed_difference"
    elif selected and not notes:
        status = "no_observed_difference"
    else:
        status = "unavailable"
        if not selected:
            notes.append("No supported changed Python function was found.")
    return status, findings, notes


def fingerprint(finding):
    return hashlib.sha256(json.dumps(finding, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def invocation(finding):
    name = finding["target"].rsplit("::", 1)[1]
    kind = finding["type"]
    argument = finding["input"]
    if isinstance(argument, list):
        argument = "[" + ", ".join(literal(kind[5:-1], value) for value in argument) + "]"
    else:
        argument = literal(kind, argument)
    return f"{name}({argument})"


def literal(kind, value):
    return f"Decimal({json.dumps(value)})" if kind == "Decimal" else value


def summary(report):
    labels = {
        "observed_difference": "Behavior difference observed",
        "no_observed_difference": "No difference observed for the generated inputs",
        "unavailable": "Comparison unavailable",
    }
    lines = ["## Undertow behavior replay", "", f"**{labels[report['status']]}.**", "",
             f"Base `{report['base']['sha'][:7]}` · PR head `{report['head']['sha'][:7]}`", ""]
    for finding in report["findings"]:
        lines.extend([f"<code>{html.escape(invocation(finding))}</code>",
                      f"- Base · <code>{html.escape(json.dumps(finding['base']))}</code>",
                      f"- PR head · <code>{html.escape(json.dumps(finding['head']))}</code>",
                      f"- Evidence ID · `{fingerprint(finding)}`", ""])
    lines.extend(f"- {html.escape(note)}" for note in report["notes"])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        if not all(SHA.fullmatch(sha) for sha in (args.base, args.head)):
            raise ValueError("Use full Git commit SHAs.")
        args.output.mkdir(parents=True)
    except (OSError, ValueError) as error:
        parser.error(str(error))

    try:
        with tempfile.TemporaryDirectory() as directory:
            status, findings, notes = inspect(args, Path(directory))
    except (OSError, ValueError, tarfile.TarError, subprocess.SubprocessError):
        status, findings, notes = "unavailable", [], ["Could not prepare or compare the Git snapshots."]
    report = {"schema_version": 1, "kind": "behavior", "status": status,
              "base": {"sha": args.base}, "head": {"sha": args.head}, "findings": findings, "notes": notes}
    markdown = summary(report)
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (args.output / "summary.md").write_text(markdown, encoding="utf-8")
    print(markdown, end="")
    return int(status == "unavailable")


if __name__ == "__main__":
    sys.exit(main())

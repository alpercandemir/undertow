"""Find a reproducible behavior difference across Python revisions."""

import argparse
import ast
from decimal import Decimal
import hashlib
import html
import json
import keyword
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


def functions(source):
    return {node.name: node for node in ast.parse(source).body
            if isinstance(node, ast.FunctionDef) and not node.name.startswith("_")}


def parameter_type(function):
    args = function.args
    positional = args.posonlyargs + args.args
    if not single_argument(function):
        return None
    annotation = positional[0].annotation
    if isinstance(annotation, ast.Name) and annotation.id in SCALAR_TYPES:
        return annotation.id
    if (isinstance(annotation, ast.Subscript) and isinstance(annotation.value, ast.Name)
            and annotation.value.id == "list" and isinstance(annotation.slice, ast.Name)
            and annotation.slice.id in SCALAR_TYPES):
        return f"list[{annotation.slice.id}]"
    return None


def single_argument(function):
    args = function.args
    return (len(args.posonlyargs + args.args) == 1 and not args.vararg and not args.kwonlyargs
            and not args.kwarg and not function.decorator_list)


def named_cases(tree):
    declarations = [node.value for node in tree.body if isinstance(node, (ast.Assign, ast.AnnAssign))
                    and any(isinstance(target, ast.Name) and target.id == "CASES"
                            for target in (node.targets if isinstance(node, ast.Assign) else [node.target]))]
    if not declarations:
        return None
    if len(declarations) != 1 or not isinstance(declarations[0], ast.Dict):
        raise ValueError("Define CASES once as a literal dictionary of names and JSON inputs.")
    table = declarations[0]
    if not 1 <= len(table.keys) <= 40:
        raise ValueError("Supply between 1 and 40 named cases.")
    examples = {}
    for key, value in zip(table.keys, table.values):
        if not isinstance(key, ast.Constant) or not isinstance(key.value, str):
            raise ValueError("Each case needs a literal string name.")
        name = key.value
        if not name.strip() or not name.isprintable() or len(name) > 80 or name in examples:
            raise ValueError("Use unique, printable case names of 1 to 80 characters.")
        try:
            argument = ast.literal_eval(value)
            encoded = json.dumps(argument, allow_nan=False)
            if json.loads(encoded) != argument:
                raise ValueError
        except (ValueError, TypeError, OverflowError) as error:
            raise ValueError(f"Case {name!r} needs a literal JSON input.") from error
        examples[name] = argument
    return examples


def load_probe(path):
    if path.suffix != ".py" or path.stat().st_size > 128 * 1024:
        raise ValueError("Supply a Python probe of at most 128 KiB.")
    source = path.read_bytes()
    tree = ast.parse(source)
    probe = next((node for node in reversed(tree.body)
                  if isinstance(node, ast.FunctionDef) and node.name == "probe"), None)
    kind = parameter_type(probe) if probe else None
    if kind:
        return source, probe, kind, None
    examples = named_cases(tree)
    if probe and single_argument(probe) and examples is not None:
        return source, probe, "json", examples
    raise ValueError("Define probe(value) with a CASES dictionary or a supported numeric or list annotation.")


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
            files = [path_in_snapshot(snapshot, path) for snapshot in snapshots]
            if any(file.stat().st_size > 128 * 1024 for file in files):
                continue
            old, new = (functions(file.read_bytes()) for file in files)
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
        if isinstance(values, dict) and values.get("unverified_probe_module") in request.get("modules", []):
            module = values["unverified_probe_module"]
            return None, f"probe module {module!r} was not loaded from the Git snapshot"
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
    rejected = None
    observations = zip(request["inputs"], *runs[0], *runs[1], strict=True)
    for index, (value, base, base_repeat, head, head_repeat) in enumerate(observations):
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
            rejected = f"{side}: {reason}"
            if time.monotonic() >= deadline:
                break
            continue
        if same(isolated_base, [base]) and same(isolated_head, [head]):
            finding = {"input": value, "base": base, "head": head}
            if "case_names" in request:
                finding["case"] = request["case_names"][index]
            return finding, None
        rejected = "the difference did not reproduce with the input alone"
    return None, rejected or (None if compared else "no stable, comparable result")


def requests(args, directory, snapshots, env):
    if args.probe:
        source, function, kind, examples = load_probe(args.probe)
        probe = directory / "probe.py"
        probe.write_bytes(source)
        inputs = ({"inputs": list(examples.values()), "case_names": list(examples)} if examples is not None
                  else {"inputs": cases(kind, function, function)})
        yield (f"{args.probe.name}::probe",
               {"probe": str(probe), "modules": args.probe_module, "name": "probe", "type": kind, **inputs},
               {"probe_sha256": hashlib.sha256(source).hexdigest()})
        return
    selected = list(targets(args.repo, args.base, args.head, snapshots, env))[:MAX_TARGETS]
    for path, before, after, kind in selected:
        yield (f"{path.as_posix()}::{after.name}",
               {"path": path.as_posix(), "name": after.name, "type": kind,
                "inputs": cases(kind, before, after)}, {})


def inspect(args, directory):
    env = {"PATH": os.environ.get("PATH", os.defpath), "LC_ALL": "C.UTF-8",
           "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}
    snapshots = tuple(directory / side for side in ("base", "head"))
    for sha, snapshot in zip((args.base, args.head), snapshots):
        git_snapshot(args.repo, sha, snapshot, env)
    findings, notes = [], []
    deadline = time.monotonic() + 45
    selected = list(requests(args, directory, snapshots, env))
    for label, request, metadata in selected:
        if time.monotonic() >= deadline:
            notes.append("The comparison reached its time limit.")
            break
        finding, reason = compare(snapshots, request, env, deadline)
        if finding:
            findings.append({"target": label, "type": request["type"], **metadata, **finding})
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
    if kind == "json":
        argument = ascii(argument)
    elif isinstance(argument, list):
        argument = "[" + ", ".join(literal(kind[5:-1], value) for value in argument) + "]"
    else:
        argument = literal(kind, argument)
    return f"{name}({argument})"


def literal(kind, value):
    return f"Decimal({json.dumps(value)})" if kind == "Decimal" else value


def summary(report):
    labels = {
        "observed_difference": "Behavior difference observed",
        "no_observed_difference": "No difference observed for the compared inputs",
        "unavailable": "Comparison unavailable",
    }
    lines = ["## Undertow behavior replay", "", f"**{labels[report['status']]}.**", "",
             f"Base `{report['base']['sha'][:7]}` · PR head `{report['head']['sha'][:7]}`", ""]
    for finding in report["findings"]:
        if "case" in finding:
            lines.append(f"Case · <code>{html.escape(finding['case'])}</code>")
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
    parser.add_argument("--probe", type=Path, help="Trusted Python file defining an alternative public API scenario.")
    parser.add_argument("--probe-module", action="append", default=[], metavar="MODULE",
                        help="Import name of a project module the probe uses. Repeat as needed.")
    args = parser.parse_args()
    try:
        if not all(SHA.fullmatch(sha) for sha in (args.base, args.head)):
            raise ValueError("Use full Git commit SHAs.")
        if bool(args.probe) != bool(args.probe_module):
            raise ValueError("Use --probe with at least one --probe-module.")
        if any(not part.isidentifier() or keyword.iskeyword(part)
               for name in args.probe_module for part in name.split(".")):
            raise ValueError("Use dotted Python import names for --probe-module.")
        args.probe_module = list(dict.fromkeys(args.probe_module))
        args.output.mkdir(parents=True)
    except (OSError, ValueError) as error:
        parser.error(str(error))

    try:
        with tempfile.TemporaryDirectory() as directory:
            status, findings, notes = inspect(args, Path(directory))
    except (ValueError, SyntaxError) as error:
        status, findings, notes = "unavailable", [], [str(error)]
    except (OSError, tarfile.TarError, subprocess.SubprocessError):
        status, findings, notes = "unavailable", [], ["Could not prepare or compare the Git snapshots."]
    report = {"schema_version": 1, "kind": "behavior", "status": status,
              "base": {"sha": args.base}, "head": {"sha": args.head}, "findings": findings, "notes": notes}
    if args.probe:
        report["probe_modules"] = args.probe_module
    markdown = summary(report)
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (args.output / "summary.md").write_text(markdown, encoding="utf-8")
    print(markdown, end="")
    return int(status == "unavailable")


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Compare two immutable revisions of a conventionally laid out Java/Maven project."""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from functools import partial
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time
from urllib.parse import quote, urlsplit
import xml.etree.ElementTree as ET

VERSION = "0.1.1-alpha.1"
INSPECTOR = Path(__file__).resolve().with_name("change-review") / "Inspect.java"
PRODUCTION = "src/main/java/"
TESTS = "src/test/java/"
MAX_FILES = 1500
MAX_TESTS = 8
MAX_PROBES = 32
MAX_FINDINGS = 3
MAX_SNAPSHOT_BYTES = 100_000_000
MAX_LOG_BYTES = 16_000_000
REPLAYS = 3
MAVEN_TIMEOUT = 120
DIFF_TIMEOUT = 30
ASSERTION_TYPES = {"org.opentest4j.AssertionFailedError", "java.lang.AssertionError",
                   "org.junit.ComparisonFailure"}


class Incomplete(RuntimeError):
    pass


@dataclass(frozen=True)
class Method:
    path: str
    owner: str
    name: str
    signature: str
    line: int
    end: int
    flags: str
    body: str
    summary: str
    junit: str
    references: str

    @property
    def operation(self):
        return f"{self.owner}.{self.name}"

    @property
    def location(self):
        return {"path": self.path, "line": self.line}


@dataclass(frozen=True)
class Seed:
    path: str
    package: str
    owner: str
    method: str
    line: int
    junit: str
    operation: str
    expected: str
    arguments: str
    receiver: str

    @property
    def key(self):
        return self.operation, self.receiver, self.arguments

    @property
    def location(self):
        return {"path": self.path, "line": self.line}

    @property
    def call(self):
        function = self.operation if not self.receiver else self.receiver + "." + self.operation.rsplit(".", 1)[1]
        return function + "(" + ", ".join(self.arguments.split("\x1f")) + ")"


def git(repo, *args):
    try:
        return subprocess.check_output(
            ["git", "--no-replace-objects", "-C", str(repo), *args],
            stderr=subprocess.PIPE, timeout=DIFF_TIMEOUT,
            env={**os.environ, "GIT_NO_LAZY_FETCH": "1"},
        )
    except subprocess.CalledProcessError as error:
        raise Incomplete(error.stderr.decode(errors="replace").strip()) from error


def resolve(repo, revision):
    return git(repo, "rev-parse", "--verify", "--end-of-options", revision + "^{commit}").decode().strip()


def snapshot(repo, commit, root):
    """Write tracked blobs directly from Git, independent of checkout filters and export attributes."""
    entries = git(repo, "ls-tree", "-rz", commit).split(b"\0")[:-1]
    root.mkdir()
    sizes = 0
    with tempfile.TemporaryFile() as requests:
        for entry in entries:
            mode, kind, object_id = entry.split(b"\t", 1)[0].split()
            if kind != b"blob" or mode not in {b"100644", b"100755"}:
                raise Incomplete("This build snapshot contains symlinks or submodules.")
            requests.write(object_id + b"\n")
        requests.seek(0)
        with subprocess.Popen(["git", "--no-replace-objects", "-C", str(repo), "cat-file", "--batch"],
                              stdin=requests, stdout=subprocess.PIPE,
                              env={**os.environ, "GIT_NO_LAZY_FETCH": "1"}) as process:
            try:
                for entry in entries:
                    info, raw_path = entry.split(b"\t", 1)
                    name = raw_path.decode("utf-8")
                    parts = name.split("/")
                    if any(part in {"", ".", ".."} or part.casefold() == ".git" for part in parts):
                        raise Incomplete("A tracked path cannot be represented safely.")
                    header = process.stdout.readline().split()
                    if len(header) != 3 or header[1] != b"blob":
                        raise Incomplete(f"Git object for {name} is unavailable.")
                    size = int(header[2])
                    sizes += size
                    if sizes > MAX_SNAPSHOT_BYTES:
                        raise Incomplete("The tracked snapshot exceeds the 100 MB budget.")
                    data = process.stdout.read(size)
                    if len(data) != size or process.stdout.read(1) != b"\n":
                        raise Incomplete(f"Git returned an incomplete blob for {name}.")
                    target = root.joinpath(*parts)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with target.open("xb") as output:
                        output.write(data)
                    if info.startswith(b"100755 "):
                        target.chmod(0o755)
            finally:
                if process.poll() is None:
                    process.kill()


def changes_between(repo, base, head):
    fields = git(repo, "diff", "--no-ext-diff", "--no-textconv", "--name-status", "-z", "-M",
                 base, head, "--").decode("utf-8").split("\0")
    result = []
    tokens = iter(fields[:-1])
    for status in tokens:
        path = next(tokens)
        other = next(tokens) if status.startswith(("R", "C")) else path
        result.append({"status": status, "base_path": None if status == "A" else path,
                       "head_path": None if status == "D" else other})
    return result


def execute(command, cwd, log, timeout, env=None):
    started = time.monotonic()
    timed_out = False
    exceeded_log = False
    with log.open("wb") as output:
        process = subprocess.Popen(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True, env=env)
        try:
            while process.poll() is None:
                timed_out = time.monotonic() - started >= timeout
                exceeded_log = os.fstat(output.fileno()).st_size > MAX_LOG_BYTES
                if timed_out or exceeded_log:
                    break
                try:
                    process.wait(timeout=0.25)
                except subprocess.TimeoutExpired:
                    continue
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
        if os.fstat(output.fileno()).st_size > MAX_LOG_BYTES:
            exceeded_log = True
            output.truncate(MAX_LOG_BYTES)
    code = None if timed_out or exceeded_log else process.returncode
    return {"exit_code": code, "timed_out": timed_out, "log_limit_exceeded": exceeded_log, "log": log.name,
            "seconds": round(time.monotonic() - started, 3)}


def inspect(root, output, side, java, env):
    paths = sorted(p for tree in (PRODUCTION, TESTS) for p in (root / tree).rglob("*.java"))
    limit = len(paths) > MAX_FILES
    if not paths:
        return {}, [], False
    if any("\n" in str(path) or "\r" in str(path) for path in paths):
        raise Incomplete("Java source paths contain unsupported line breaks.")
    listing = output / f"{side}-sources.txt"
    listing.write_text("".join(str(p.relative_to(root)) + "\n" for p in paths[:MAX_FILES]), encoding="utf-8")
    log = output / f"{side}-discovery.tsv"
    attempt = execute([java, str(INSPECTOR), str(listing)], root, log, DIFF_TIMEOUT, env)
    if attempt["exit_code"] != 0:
        raise Incomplete(f"Java discovery did not finish. See {log.name}.")
    methods, seeds = {}, []
    for row in log.read_text(encoding="utf-8").splitlines():
        tag, *fields = row.split("\t")
        if tag not in {"M", "P"}:
            limit = True
            continue
        values = [base64.b64decode(field).decode() for field in fields]
        values[4] = int(values[4])
        if tag == "M":
            values[5] = int(values[5])
            method = Method(*values)
            methods[(method.owner, method.name, method.signature)] = method
        else:
            seeds.append(Seed(*values))
    return methods, seeds, limit


def discover(changes, base, head):
    old_paths = {item["base_path"] for item in changes}
    new_paths = {item["head_path"] for item in changes}
    direct = {}
    for key in sorted(base.keys() | head.keys()):
        before, after = base.get(key), head.get(key)
        paths = ((before and before.path in old_paths) or (after and after.path in new_paths))
        if not paths or not (before or after).path.startswith(PRODUCTION):
            continue
        if before and after and (before.path, before.body, before.flags) == (after.path, after.body, after.flags):
            continue
        name = (before or after).operation
        direct.setdefault(name, []).append({
            "base": before.location if before else None, "head": after.location if after else None,
            "base_documentation": before.summary if before else "",
            "head_documentation": after.summary if after else "",
        })
    routes = {operation: [operation] for operation in direct}
    for _ in range(3):
        updated = {}
        for method in [*base.values(), *head.values()]:
            if not method.path.startswith(PRODUCTION) or method.operation in routes:
                continue
            targets = sorted(set(method.references.splitlines()) & routes.keys())
            if targets:
                path = [method.operation, *routes[targets[0]]]
                updated.setdefault(method.operation, path)
        routes.update(updated)
    return direct, routes


def eligible_probes(base, head, base_seeds, head_seeds, routes, changes):
    shared = {
        (method.operation, "static" in method.flags)
        for key, method in base.items()
        if key in head and "public" in method.flags
        and method.flags == head[key].flags and method.path.startswith(PRODUCTION)
    }
    edited = {item[field] for item in changes for field in ("base_path", "head_path")}
    selected = {}
    for side, seeds in (("base", base_seeds), ("head", head_seeds)):
        for seed in seeds:
            if seed.operation in routes and (seed.operation, not seed.receiver) in shared:
                selected.setdefault(seed.key, {}).setdefault(side, seed)
    exact = sorted(selected.values(), key=lambda pair: (
        not any(seed.path in edited for seed in pair.values()),
        len(routes[next(iter(pair.values())).operation]), "base" not in pair,
        next(iter(pair.values())).path, next(iter(pair.values())).line,
    ))
    variants = []
    for pair in exact:
        seed = pair.get("base") or pair["head"]
        args = seed.arguments.split("\x1f")
        for index, argument in enumerate(args):
            padded = perturb(argument, seed.expected.startswith("throws "))
            if padded == argument:
                continue
            modified = [*args]
            modified[index] = padded
            variant = replace(seed, arguments="\x1f".join(modified), expected="null")
            if variant.key not in selected:
                selected[variant.key] = {}
                variants.append({"variant": variant, "seed_side": "base" if "base" in pair else "head"})
    exact_budget = MAX_PROBES - min(len(variants), MAX_PROBES // 4)
    return exact[:exact_budget] + variants[:MAX_PROBES - exact_budget]


def perturb(argument, exception_boundary):
    if exception_boundary and re.fullmatch(r'new String\[\]\{"[a-zA-Z]+"(?:,\s*"[a-zA-Z]+")*\}', argument):
        return re.sub(r'"([a-zA-Z]+)"', r'" \1"', argument)
    if re.fullmatch(r'"[a-zA-Z0-9 ]+"', argument):
        match = re.search(r"(.)\1+", argument[1:-1])
        if match:
            end = match.end() + 1
            return argument[:end] + match.group(1) + argument[end:]
    return argument


def test_scope(base, head, routes, changes):
    edited = {item[field] for item in changes for field in ("base_path", "head_path")}
    left = {(m.owner, m.name): m for m in base.values() if m.junit}
    right = {(m.owner, m.name): m for m in head.values() if m.junit}
    tests = []
    for key in sorted(left.keys() & right.keys()):
        before, after = left[key], right[key]
        links = sorted((set(before.references.splitlines()) | set(after.references.splitlines())) & routes.keys())
        if links:
            tests.append({"selector": "#".join(key), "base": before.location, "head": after.location,
                          "head_end_line": after.end, "operations": links,
                          "same_body": before.body == after.body})
    tests.sort(key=lambda test: (
        not (test["base"]["path"] in edited or test["head"]["path"] in edited),
        test["same_body"], min(len(routes[op]) for op in test["operations"]), test["selector"],
    ))
    return tests


def maven_project(root):
    pom_path = root / "pom.xml"
    if not pom_path.is_file():
        return False
    pom = ET.parse(pom_path).getroot()
    return not (pom.find("{*}modules") is not None or pom.findtext("{*}packaging") == "pom")


def surefire(root):
    cases = {}
    for path in sorted((root / "target/surefire-reports").glob("TEST-*.xml")):
        for case in ET.parse(path).getroot().iter("testcase"):
            name = f"{case.attrib.get('classname', '')}#{case.attrib.get('name', '')}"
            outcome = "passed"
            detail = {}
            for tag in ("failure", "error", "skipped"):
                item = case.find(tag)
                if item is not None:
                    outcome = tag
                    detail = {"type": item.get("type", ""), "message": item.get("message", "")[:500],
                              "trace": (item.text or "")[:2000]}
                    break
            if name in cases:
                cases[name] = {"status": "ambiguous"}
            else:
                cases[name] = {"status": outcome, **detail}
    return cases


def maven_run(root, output, label, selectors, extra, build_env):
    maven = "./mvnw" if os.access(root / "mvnw", os.X_OK) else "mvn"
    command = [maven, "-B", "-ntp", "-DfailIfNoTests=true", "-Dtest=" + ",".join(selectors), "test"]
    attempt = execute(command, root, output / f"{label}.log", MAVEN_TIMEOUT, build_env)
    attempt["command"] = command
    try:
        attempt["tests"] = surefire(root)
    except ET.ParseError:
        attempt["tests"] = {}
        attempt["report_error"] = "Surefire reports could not be parsed."
    if extra and (root / extra).is_file():
        observations = {}
        for line in (root / extra).read_text(encoding="utf-8").splitlines():
            key, value, oracle = line.split("\t")
            if key in observations:
                raise Incomplete("A generated probe reported multiple observations.")
            observations[key] = {
                "outcome": base64.b64decode(value, validate=True).decode(),
                "oracle": base64.b64decode(oracle, validate=True).decode(),
            }
        attempt["observations"] = observations
    return attempt


COMMON_JAVA = """    private static String outcome(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof String
                || value instanceof Character || value instanceof Boolean) {
            String kind = value instanceof Number ? "number" : value.getClass().getSimpleName();
            return kind + ":" + value;
        }
        throw new IllegalArgumentException("Unsupported observation type " + value.getClass().getName());
    }
"""


def java_source(package, name, junit, methods, extra="", header=""):
    annotation = "org.junit.Test" if junit == "4" else "org.junit.jupiter.api.Test"
    declaration = f"package {package};\n\n" if package else ""
    return (header + declaration + f"public class {name} {{\n" + COMMON_JAVA + extra
            + "".join(f"\n    @{annotation}\n    public void {method}() throws Exception {{\n{body}\n    }}\n"
                      for method, body in methods) + "}\n")


def observe_code(seed):
    return (f"        Object value;\n"
            f"        try {{\n            value = {seed.call};\n"
            f"        }} catch (Exception error) {{\n"
            f"            value = error;\n        }}\n"
            f"        String actual = value instanceof Exception\n"
            f"                ? \"throws:\" + value.getClass().getName() : outcome(value);\n")


def test_header(snapshots, side, source):
    text = (snapshots[side] / source.path).read_text(encoding="utf-8")
    match = re.match(r"\s*(/\*.*?\*/)", text, re.DOTALL)
    if match and re.search(r"licen[sc]e|SPDX", match[1], re.IGNORECASE):
        return match[1] + "\n"
    return ""


def probes_source(snapshots, probes, identity, file):
    by_package = {}
    headers = {}
    for index, pair in enumerate(probes):
        side = pair.get("seed_side") or ("base" if "base" in pair else "head")
        seed = pair.get("variant") or pair[side]
        package = seed.package, seed.junit
        method = f"scenario{index}"
        oracle = (f'"throws:" + {seed.expected[7:]}.class.getName()'
                  if seed.expected.startswith("throws ") else f"outcome({seed.expected})")
        body = observe_code(seed) + f'        record("{method}", actual, {oracle});'
        by_package.setdefault(package, []).append((method, body))
        if package not in headers:
            headers[package] = test_header(snapshots, side, seed)
    files, selectors = {}, {}
    for number, ((package, junit), methods) in enumerate(by_package.items()):
        name = f"UndertowObservation{identity}{number}Test"
        extra = f"""
    private static void record(String test, String value, String expected) throws java.io.IOException {{
        java.nio.file.Files.write(java.nio.file.Paths.get("{file}"),
                (test + "\\t" + encode(value) + "\\t" + encode(expected) + "\\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }}

    private static String encode(String value) {{
        return java.util.Base64.getEncoder().encodeToString(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }}
"""
        files[java_path(package, name)] = java_source(package, name, junit, methods, extra,
                                                      headers[package, junit])
        for method, _ in methods:
            selectors[method] = f"{package + '.' if package else ''}{name}#{method}"
    return files, selectors


def java_path(package, name):
    return TESTS + (package.replace(".", "/") + "/" if package else "") + name + ".java"


def paired(snapshots, output, label, selectors, files=None, observations=None, *, build_env):
    def run(side):
        with tempfile.TemporaryDirectory(prefix="undertow-attempt-") as temporary:
            root = Path(temporary) / "repo"
            shutil.copytree(snapshots[side], root)
            for path, source in (files or {}).items():
                destination = root / path
                destination.parent.mkdir(parents=True, exist_ok=True)
                with destination.open("x", encoding="utf-8") as handle:
                    handle.write(source)
            if observations and (root / observations).exists():
                raise Incomplete("The observation file would overwrite a tracked file.")
            return maven_run(root, output, f"{label}-{side}", selectors, observations, build_env)

    with ThreadPoolExecutor(max_workers=2) as pool:
        return dict(zip(snapshots, pool.map(run, snapshots)))


def replay(snapshots, output, run_pair, finding, seed, side, index):
    identity = sha256(seed.call.encode()).hexdigest()[:12]
    name = f"UndertowChange{identity}Test"
    assertion = "org.junit.Assert" if seed.junit == "4" else "org.junit.jupiter.api.Assertions"
    expected = json.dumps(finding["before"], ensure_ascii=True)
    trace_marker = "undertowExpectedBaseBehavior"
    source = java_source(seed.package, name, seed.junit, [
        ("matchesPreviouslyObservedBehavior", observe_code(seed)
         + f'        {trace_marker}(actual);')
    ], f"\n    private static void {trace_marker}(String actual) {{\n"
       f"        {assertion}.assertEquals({expected}, actual);\n    }}\n",
       test_header(snapshots, side, seed))
    target = java_path(seed.package, name)
    selector = f"{seed.package + '.' if seed.package else ''}{name}#matchesPreviouslyObservedBehavior"
    replays = []
    for attempt in range(1, REPLAYS + 1):
        runs = run_pair(f"replay-{index}-{attempt}", [selector], {target: source})
        baseline = runs["base"]["tests"].get(selector, {})
        candidate = runs["head"]["tests"].get(selector, {})
        valid = (runs["base"]["exit_code"] == 0 and baseline.get("status") == "passed"
                 and runs["head"]["exit_code"] not in (None, 0)
                 and candidate.get("status") == "failure" and candidate.get("type") in ASSERTION_TYPES
                 and f".{trace_marker}(" in candidate.get("trace", ""))
        replays.append({"validated": valid, "runs": runs})
    finding["replay"] = replays
    if all(item["validated"] for item in replays):
        artifact = output / "regressions" / target
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_text(source, encoding="utf-8")
        finding["export"] = str(artifact.relative_to(output))
        finding["target_path"] = target


def compare_probes(snapshots, output, run_pair, report, probes, routes, identity):
    file = f"undertow-observations-{identity}.tsv"
    files, selectors = probes_source(snapshots, probes, identity, file)
    for path, source in files.items():
        artifact = output / "harness" / path
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_text(source, encoding="utf-8")
    runs = run_pair("observations", sorted(set(
        selector.split("#")[0] for selector in selectors.values())), files, file)
    report["execution"]["observations"] = runs
    if any(run["exit_code"] != 0 for run in runs.values()):
        report["gaps"].append("The generated scenarios could not complete on both revisions. See the Maven logs.")
    for index, pair in enumerate(probes):
        key = f"scenario{index}"
        side = pair.get("seed_side") or ("base" if "base" in pair else "head")
        seed = pair.get("variant") or pair[side]
        selector = selectors[key]
        outcomes = [runs[side].get("observations", {}).get(key) for side in snapshots]
        scenario = {"operation": seed.operation, "input": seed.call, "selector": selector,
                    "source": {revision: pair[revision].location for revision in ("base", "head")
                               if revision in pair} or {side: seed.location},
                    "generated_input": "variant" in pair,
                    "pair": dict(zip(snapshots, outcomes))}
        report["scenarios"].append(scenario)
        if any(item is None for item in outcomes) or any(
                run["tests"].get(selector, {}).get("status") != "passed" for run in runs.values()):
            scenario["status"] = "incomplete_comparison"
            continue
        before, after = outcomes
        if before["outcome"] == after["outcome"]:
            scenario["status"] = "same_observation"
            continue
        has_base_expectation = "base" in pair and before["outcome"] == before["oracle"]
        changed_expectation = ("head" in pair and "base" in pair
                               and pair["base"].expected != pair["head"].expected)
        scenario["status"] = "observed_difference"
        if len(report["findings"]) >= MAX_FINDINGS:
            continue
        finding = {
            "kind": "observed_difference", "flow": seed.operation,
            "input": seed.call, "before": before["outcome"], "after": after["outcome"],
            "route": routes[seed.operation], "source": scenario["source"],
            "base_assertion": has_base_expectation, "changed_assertion": changed_expectation,
            "generated_input": scenario["generated_input"],
        }
        report["findings"].append(finding)
        replay(snapshots, output, run_pair, finding, seed, side, index)
        exception_might_satisfy_base = (before["oracle"].startswith("throws:")
                                        and after["outcome"].startswith("throws:"))
        if has_base_expectation and not changed_expectation and not exception_might_satisfy_base and "export" in finding:
            finding["kind"] = "reproduced_breakage"
        if "export" not in finding:
            report["gaps"].append(f"Fresh standalone replay did not validate the difference for {seed.operation}.")


def existing_assertion(test, runs):
    selector = test["selector"]
    base = runs["base"]
    head = runs["head"]
    if (base["exit_code"] != 0 or head["exit_code"] in (0, None)
            or base["tests"].get(selector, {}).get("status") != "passed"):
        return None
    case = head["tests"].get(selector, {})
    if case.get("status") != "failure" or case.get("type") not in ASSERTION_TYPES:
        return None
    method = selector.replace("#", ".")
    filename = Path(test["head"]["path"]).name
    frame = re.search(rf"(?m)^\s*at {re.escape(method)}\({re.escape(filename)}:(\d+)\)", case["trace"])
    if frame and test["head"]["line"] <= int(frame[1]) <= test["head_end_line"]:
        return int(frame[1]), case["type"], case["message"]
    return None


def replay_existing(run_pair, report, runs, routes):
    """Use unchanged repository tests as their own replay when no portable probe covers the failure."""
    paths = {change[side] for change in report["changed_files"]
             for side in ("base_path", "head_path") if change[side]}
    if any(not path.startswith(PRODUCTION) for path in paths):
        return
    for index, test in enumerate(report["selected_tests"]):
        if len(report["findings"]) >= MAX_FINDINGS:
            break
        selector = test["selector"]
        assertion = existing_assertion(test, runs)
        if not assertion or test["base"]["path"] != test["head"]["path"]:
            continue
        if any(finding["kind"] == "reproduced_breakage"
               and finding["source"]["base"]["path"] == test["base"]["path"]
               and test["base"]["line"] <= finding["source"]["base"]["line"] <= test["head_end_line"]
               for finding in report["findings"]):
            continue
        flow = min(test["operations"], key=lambda operation: (len(routes[operation]), operation))
        replays = []
        for attempt in range(1, REPLAYS + 1):
            repeated = run_pair(f"existing-replay-{index}-{attempt}", [selector])
            replays.append({"validated": existing_assertion(test, repeated) == assertion, "runs": repeated})
        if not all(item["validated"] for item in replays):
            report["gaps"].append(f"Fresh runs did not reproduce the same existing assertion failure in {selector}.")
            continue
        line, exception, message = assertion
        report["findings"].append({
            "kind": "reproduced_breakage", "flow": flow, "test": selector, "route": routes[flow],
            "source": {side: {"path": test[side]["path"], "line": line} for side in ("base", "head")},
            "assertion_type": exception, "assertion_message": message, "replay": replays,
        })


def safe_origin(repo):
    try:
        origin = git(repo, "config", "--get", "remote.origin.url").decode().strip()
    except Incomplete:
        return None
    if re.fullmatch(r"git@github\.com:[\w.-]+/[\w.-]+(?:\.git)?", origin):
        origin = "https://github.com/" + origin.removeprefix("git@github.com:")
    parsed = urlsplit(origin)
    if parsed.netloc == "github.com" and parsed.scheme == "https" and not parsed.query and not parsed.fragment and re.fullmatch(
            r"/[\w.-]+/[\w.-]+", parsed.path):
        return origin.removesuffix(".git")
    return None


def source_link(report, side, location, label):
    if location is None:
        return "—"
    path = location["path"]
    line = location.get("line")
    suffix = f":{line}" if line else ""
    display = label or path + suffix
    if report["repository_url"]:
        fragment = f"#L{line}" if line else ""
        url = f'{report["repository_url"]}/blob/{report[side]}/{quote(path)}{fragment}'
        return f"[{display}]({url})"
    return code(display)


def code(text):
    # Use a fence longer than any run of backticks in repository-derived text.
    value = str(text).replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
    fence = "`" * (1 + max((len(run) for run in re.findall(r"`+", value)), default=0))
    return f"{fence} {value} {fence}" if "`" in value else f"{fence}{value}{fence}"


def prose(text):
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\{@(?:link|code) ([^}]+)\}", r"\1", text)
    return re.sub(r"([\\`*_{}\[\]<>])", r"\\\1", " ".join(text.split()))


def behavior(outcome):
    if outcome.startswith("throws:"):
        return "threw " + code(outcome[len("throws:"):].rsplit(".", 1)[-1])
    kind, _, value = outcome.partition(":")
    if kind == "String":
        value = json.dumps(value, ensure_ascii=False)
    return "returned " + code(value if kind != "null" else "null")


def markdown(report):
    names = {
        "reproduced_breakage": "Reproduced breakage",
        "observed_difference": "Observed behavioral difference",
        "potential_impact": "Potential impact",
        "incomplete_comparison": "Incomplete comparison",
        "no_difference_observed": "No difference observed in the scenarios checked",
        "no_executable_change_found": "No executable change found in the checked scope",
    }
    lines = ["# Undertow change review", "", f"**{names[report['status']]}**", ""]
    for finding in report["findings"]:
        label = finding["flow"].rsplit(".", 1)[-1]
        lines.extend([f"### {names[finding['kind']]} in {code(label)}", ""])
        if "test" in finding:
            lines.append(f"The existing test {code(finding['test'])} passed on {len(finding['replay'])} fresh "
                         "base builds and failed at the same assertion on each fresh candidate build.")
            lines.append("")
            lines.append(code(finding["assertion_message"] or finding["assertion_type"]))
        else:
            expression = finding["input"]
            if len(expression) > 200:
                expression = expression[:197] + "..."
            lines.extend([f"With {code(expression)}, the base {behavior(finding['before'])} "
                          f"and the candidate {behavior(finding['after'])}.", ""])
            if finding["base_assertion"]:
                lines.append("The base execution matched a pre-existing assertion for these inputs.")
            if finding["changed_assertion"]:
                lines.append("The candidate changed the asserted result for these inputs. Review the expectation alongside the implementation.")
            if finding["generated_input"]:
                lines.append("Undertow tested a small variation of a repository test input.")
            if not finding["base_assertion"]:
                lines.append("The base result is a characterization of prior behavior. Review whether to preserve it.")
            if finding.get("export"):
                lines.append(f"The exported JUnit test passed on {len(finding['replay'])} fresh base builds and failed "
                             "at its comparison assertion on each fresh candidate build.")
        lines.append("")
        links = [source_link(report, side, point, ("candidate" if side == "head" else side) + " scenario source")
                 for side, point in finding["source"].items()]
        if links:
            lines.append(" · ".join(links))
        route = finding["route"]
        points = report["changed_operations"].get(route[-1], [])
        if points:
            point = points[0]
            links = [source_link(report, side, point[side], ("candidate" if side == "head" else side) + " implementation")
                     for side in ("base", "head") if point[side]]
            lines.extend(["", f"Inspect the implementation reached through {code(' → '.join(route))}. "
                          + " · ".join(links)])
        lines.append("")
    if report["affected_flows"]:
        lines.extend(["## Affected flows", ""])
        for flow in report["affected_flows"]:
            doc = prose(flow["prior_documentation"])
            links = [source_link(report, "base", flow["base"], "base source")] if flow["base"] else []
            links += [source_link(report, "head", flow["head"], "candidate source")] if flow["head"] else []
            line = f"- {code(flow['operation'])}"
            if doc:
                line += ". Prior documentation says " + doc
            if len(flow["route"]) > 1:
                line += f" Calls {code(flow['route'][1])}."
            if links:
                line += " " + " · ".join(links)
            lines.append(line)
        lines.append("")
    if report["gaps"]:
        lines.extend(["## Investigation gaps", "", *[f"- {prose(gap)}" for gap in report["gaps"]], ""])
    moved = [change for change in report["changed_files"] if change["status"].startswith("R")]
    if moved:
        lines.extend(["## Moved files", ""])
        for change in moved[:MAX_TESTS]:
            old = source_link(report, "base", {"path": change["base_path"]}, "base file")
            new = source_link(report, "head", {"path": change["head_path"]}, "candidate file")
            lines.append(f"- {code(change['base_path'])} → {code(change['head_path'])}. {old} · {new}")
        lines.append("")
    completed = sum(item["status"] != "incomplete_comparison" for item in report["scenarios"])
    lines += ["## Evidence", "",
              f"- Base {code(report['base'])}",
              f"- Candidate {code(report['head'])}"]
    if report.get("java"):
        lines.append(f"- Analysis JDK {code(report['java'].get('analysis', 'unavailable'))}. "
                     f"Project JDK {code(report['java'].get('build', 'unavailable'))}.")
    lines += [f"- {len(report['changed_files'])} changed paths. {len(report['selected_tests'])} existing test methods selected. "
              f"{completed} of {len(report['scenarios'])} literal-input scenarios compared.",
              f"- Undertow {report['version']}. Elapsed time {report['seconds']:.1f}s. "
              "The report covers the selected tests and generated scenarios.",
              "- Artifact files include the exact diff, structured results, and available Maven logs.", ""]
    existing = report["execution"].get("existing_tests")
    if existing:
        lines.extend(["<details>", "<summary>Existing tests</summary>", ""])
        for test in report["selected_tests"]:
            selector = test["selector"]
            results = []
            for side, label in (("base", "Base"), ("head", "candidate")):
                cases = [case["status"] for name, case in existing[side]["tests"].items()
                         if name == selector or name.startswith((selector + "[", selector + "("))]
                verdict = ", ".join(sorted(set(cases))) if cases else "result unavailable"
                results.append(f"{label} {verdict}")
            lines.append(f"- {code(selector)}. " + ", ".join(results) + ".")
        lines.extend(["", "</details>", ""])
    return "\n".join(lines)


def compare(args, output):
    started = time.monotonic()
    report = {
        "version": VERSION, "strategy": sha256(Path(__file__).read_bytes() + INSPECTOR.read_bytes()).hexdigest(),
        "repository_url": None, "base": args.base, "head": args.head,
        "changed_files": [], "changed_operations": {}, "affected_flows": [], "selected_tests": [],
        "scenarios": [], "execution": {}, "findings": [], "gaps": [],
        "budget": {"java_files_per_revision": MAX_FILES, "existing_test_methods": MAX_TESTS,
                   "literal_scenarios": MAX_PROBES, "findings_to_replay": MAX_FINDINGS,
                   "fresh_replays_per_finding": REPLAYS, "maven_timeout_seconds": MAVEN_TIMEOUT},
    }
    try:
        repo = args.repo.resolve(strict=True)
        report["repository_url"] = safe_origin(repo)
        report["base"], report["head"] = base, head = resolve(repo, args.base), resolve(repo, args.head)
        changes = report["changed_files"] = changes_between(repo, base, head)
        (output / "change.diff").write_bytes(git(repo, "diff", "--no-ext-diff", "--no-textconv", "-M",
                                                "--no-color", base, head, "--"))
        paths = {path for change in changes for path in (change["base_path"], change["head_path"]) if path}
        review_paths = {path for path in paths if path.startswith(("src/main/", "src/test/")) or path == "pom.xml"
                        or path.startswith(".mvn/")}
        unresolved = {path for path in paths - review_paths if Path(path).suffix.lower() not in {".md", ".adoc", ".rst"}
                      and Path(path).name not in {"LICENSE", "NOTICE", "RELEASE-NOTES.txt", "CHANGES.txt"}
                      and not path.startswith(("src/site/", "src/changes/"))}
        if unresolved:
            report["gaps"].append("Changed paths outside the Java/Maven source conventions were not traced.")
        if review_paths:
            with tempfile.TemporaryDirectory(prefix="undertow-compare-") as temporary:
                assess(repo, base, head, Path(temporary), output, report, args)
    except (Incomplete, OSError, UnicodeError, ET.ParseError, subprocess.TimeoutExpired, ValueError) as error:
        report["gaps"].append(str(error) or type(error).__name__)
        report["blocked"] = True

    if any(finding["kind"] == "reproduced_breakage" for finding in report["findings"]):
        report["status"] = "reproduced_breakage"
    elif report["findings"]:
        report["status"] = "observed_difference"
    elif report.get("blocked") or (report["gaps"] and not report["affected_flows"]) or (
            report["scenarios"] and all(s["status"] == "incomplete_comparison" for s in report["scenarios"])):
        report["status"] = "incomplete_comparison"
    elif report["gaps"] or (report["affected_flows"] and not report["scenarios"]):
        report["status"] = "potential_impact"
    elif report["scenarios"]:
        report["status"] = "no_difference_observed"
    else:
        report["status"] = "no_executable_change_found"
    report["seconds"] = round(time.monotonic() - started, 3)
    return report


def jdk_environment(home):
    env = os.environ.copy()
    if home:
        directory = Path(home).resolve(strict=True)
        env["JAVA_HOME"] = str(directory)
        env["PATH"] = str(directory / "bin") + os.pathsep + env.get("PATH", "")
    return env


def java_command(env):
    return str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"


def assess(repo, base, head, temporary, output, report, args):
    snapshots = {side: temporary / side for side in ("base", "head")}
    for side, commit in (("base", base), ("head", head)):
        snapshot(repo, commit, snapshots[side])
    analysis_env = jdk_environment(args.analysis_java_home or args.build_java_home)
    build_env = jdk_environment(args.build_java_home)
    for key in ("GITHUB_ENV", "GITHUB_OUTPUT", "GITHUB_PATH", "GITHUB_STATE", "GITHUB_STEP_SUMMARY"):
        build_env.pop(key, None)
    java = java_command(analysis_env)
    report["java"] = {}
    for name, env in (("analysis", analysis_env), ("build", build_env)):
        log = output / f"{name}-java.log"
        started = execute([java_command(env), "-version"], temporary, log, DIFF_TIMEOUT, env)
        if started["exit_code"] != 0:
            raise Incomplete(f"The {name} JDK did not start. See {log.name}.")
        report["java"][name] = next(
            (line for line in log.read_text(encoding="utf-8").splitlines() if "version \"" in line),
            log.name,
        )
    run_pair = partial(paired, snapshots, output, build_env=build_env)
    base_methods, base_seeds, base_limited = inspect(snapshots["base"], output, "base", java, analysis_env)
    head_methods, head_seeds, head_limited = inspect(snapshots["head"], output, "head", java, analysis_env)
    if base_limited or head_limited:
        report["gaps"].append("Some Java files were outside the discovery budget or had parser diagnostics.")
    changes = report["changed_files"]
    direct, routes = discover(changes, base_methods, head_methods)
    report["changed_operations"] = direct
    tests = test_scope(base_methods, head_methods, routes, changes)
    probes = eligible_probes(base_methods, head_methods, base_seeds, head_seeds, routes, changes)
    report["selected_tests"] = tests[:MAX_TESTS]
    prioritized = list(dict.fromkeys([
        *[(pair.get("variant") or pair.get("base") or pair["head"]).operation for pair in probes],
        *[op for test in tests for op in test["operations"]],
        *sorted(routes),
    ]))
    for operation in prioritized[:MAX_TESTS]:
        candidates = []
        for methods in (base_methods, head_methods):
            candidates.append(next((m for m in methods.values() if m.operation == operation
                                    and m.summary and "public" in m.flags), None)
                              or next((m for m in methods.values() if m.operation == operation), None))
        before, after = candidates
        report["affected_flows"].append({"operation": operation, "route": routes[operation],
                                         "prior_documentation": before.summary if before else "",
                                         "base": before.location if before else None,
                                         "head": after.location if after else None})
    revised = sorted({p for change in changes for p in (change["base_path"], change["head_path"]) if p})
    if any(path == "pom.xml" or path.startswith(".mvn/") or "/resources/" in path for path in revised):
        report["gaps"].append("Changed Maven configuration or resources were preserved in each build. Their behavior was not traced separately.")
    if any(path.startswith(PRODUCTION) for path in revised) and not direct:
        report["gaps"].append("No changed method body was found. Field, type, annotation and initializer changes are outside this strategy.")
    if not all(maven_project(root) for root in snapshots.values()):
        report["blocked"] = True
        report["gaps"].append("Paired execution requires a single Maven project at the repository root.")
        return
    if not tests and not probes:
        if direct:
            report["gaps"].append("No supported repository test or literal-input scenario could be linked to the changed methods.")
        elif any(path.startswith(TESTS) for path in revised):
            report["gaps"].append("Changes to existing tests could not be paired with a changed production method.")
        return
    if tests:
        runs = run_pair("existing", [t["selector"] for t in tests[:MAX_TESTS]])
        report["execution"]["existing_tests"] = runs
        if any(run["exit_code"] is None or not run["tests"] for run in runs.values()):
            report["gaps"].append("Existing test results were unavailable on one or both revisions. See the Maven logs.")
            if not probes:
                report["blocked"] = True
    if probes:
        identity = sha256((base + head).encode()).hexdigest()[:12]
        compare_probes(snapshots, output, run_pair, report, probes[:MAX_PROBES], routes, identity)
        if any(s["status"] == "incomplete_comparison" for s in report["scenarios"]):
            report["gaps"].append("At least one selected scenario lacks comparable execution evidence.")
    if tests:
        replay_existing(run_pair, report, runs, routes)
        if any(run["exit_code"] != 0 for run in runs.values()) and not any(
                finding["kind"] == "reproduced_breakage" for finding in report["findings"]):
            report["gaps"].append("One or both existing test selections failed. The evidence includes each revision’s outcome.")
    if not probes and direct and not report["findings"]:
        report["gaps"].append("No portable, literal-input assertions could be replayed on both revisions.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="version", version=VERSION)
    parser.add_argument("--repo", type=Path, default=Path.cwd(), help="Local Git repository")
    parser.add_argument("--base", required=True, help="Base revision to pin before analysis")
    parser.add_argument("--head", required=True, help="Candidate revision to pin before analysis")
    parser.add_argument("--output", type=Path, required=True, help="New evidence directory")
    parser.add_argument("--analysis-java-home", type=Path, help="JDK 17+ for source discovery (default: build JDK)")
    parser.add_argument("--build-java-home", type=Path, help="Project JDK for Maven (default: JAVA_HOME or PATH)")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = compare(args, output)
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    rendered = markdown(report)
    (output / "summary.md").write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return {"reproduced_breakage": 1, "incomplete_comparison": 2}.get(report["status"], 0)


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Run a trusted Undertow revision against pinned Git objects and score reviewed findings."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
RELEASE = "cbf43c4b7174c306c9f24bf4f3ebcb5d400a48a6"
SHA = re.compile(r"[0-9a-f]{40}")
NAME = re.compile(r"[A-Za-z0-9_-][A-Za-z0-9_.-]*")
ENGINE = "actions/change-review/review_engine.py"
MATERIAL_CHANGES = {"bug_fix", "intentional_change", "regression"}
CLASSIFICATIONS = MATERIAL_CHANGES | {"no_change", "unverified"}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=True, sort_keys=True) + "\n", encoding="utf-8")


def digest(data):
    return hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()


def finding_ids(report):
    ids = []
    for finding in report["findings"]:
        evidence = {identifier: report["evidence"][identifier] for identifier in finding["evidence"]}
        ids.append(digest({"finding": finding, "evidence": evidence}))
    return ids


def has_example(example):
    return all(isinstance(example.get(key), str) and example[key].strip()
               for key in ("input", "base_outcome", "head_outcome"))


def cases_from(manifest):
    if manifest["schema"] != 1 or manifest["kind"] not in {"historical", "synthetic"}:
        raise ValueError("Unsupported case manifest.")
    cases = {}
    for case in manifest["cases"]:
        case_id = case["id"]
        parts = case["repository"].split("/")
        if (not NAME.fullmatch(case_id) or case_id in cases or len(parts) != 2
                or not all(NAME.fullmatch(part) for part in parts)
                or not all(SHA.fullmatch(case[key]) for key in ("base", "head"))):
            raise ValueError(f"Invalid or duplicate case {case_id!r}.")
        if case["classification"] not in CLASSIFICATIONS:
            raise ValueError(f"Invalid classification for {case_id}.")
        changes = case["changes"]
        ids = {change["id"] for change in changes}
        material = case["classification"] in MATERIAL_CHANGES
        if (material != bool(changes) or len(ids) != len(changes)
                or any(type(change["supported"]) is not bool for change in changes)):
            raise ValueError(f"Invalid change labels for {case_id}.")
        cases[case_id] = case
    if not cases:
        raise ValueError("The manifest must contain at least one case.")
    return cases


def engine_environment(mode, model):
    env = dict(PATH=os.environ.get("PATH", os.defpath),
               GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0",
               GIT_ATTR_NOSYSTEM="1", LC_ALL="C")
    if mode == "gemini":
        key = os.environ.get("GEMINI_API_KEY")
        if not key:
            raise ValueError("Gemini mode requires GEMINI_API_KEY.")
        env["GEMINI_API_KEY"] = key
        if model:
            env["UNDERTOW_GEMINI_MODEL"] = model
    elif model:
        raise ValueError("--model requires --mode gemini.")
    return env


def run_case(engine, case, repo, output, env):
    args = [sys.executable, "-I", str(engine), "--repo", str(repo), "--output", str(output),
            "--base", case["base"], "--head", case["head"], "--source-url", case["source_url"]]
    started = time.monotonic()
    result = {"status": "unavailable", "mode": None, "finding_ids": []}
    try:
        if not repo.is_dir():
            raise ValueError("Local Git repository missing.")
        subprocess.run(args, env=env, timeout=150, check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)
        report = read(output / "report.json")
        if report["status"] not in {"findings", "no_findings", "unavailable"}:
            raise ValueError("Engine report status is invalid.")
        result["status"] = report["status"]
        result["mode"] = report["mode"]
        result["version"] = report["version"]
        result["model"] = report.get("model")
        result["reviewed_regions"] = report["coverage"].get("reviewed_regions")
        if report["status"] == "unavailable":
            result["error"] = "Undertow reported an unavailable comparison. See the retained report."
        elif (report["base"], report["head"]) != (case["base"], case["head"]):
            raise ValueError("Engine report revisions differ from the manifest.")
        else:
            result["finding_ids"] = finding_ids(report)
            if all("example" in finding for finding in report["findings"]):
                result["example_ids"] = [identifier for identifier, finding in zip(
                    result["finding_ids"], report["findings"], strict=True) if has_example(finding["example"])]
    except subprocess.TimeoutExpired:
        result.update(status="unavailable", error="Engine exceeded the 150-second timeout.")
    except (OSError, subprocess.CalledProcessError, ValueError, KeyError, TypeError):
        result.update(status="unavailable", error="Could not produce a report for the pinned comparison.")
    result["seconds"] = round(time.monotonic() - started, 3)
    return result


def run(args, manifest, cases):
    env = engine_environment(args.mode, args.model)
    revision = None if args.working_tree else args.revision or RELEASE
    if revision is not None:
        if not SHA.fullmatch(revision):
            raise ValueError("Supply the full SHA of a trusted Undertow revision.")
        source = subprocess.check_output(["git", "-C", str(ROOT), "show", f"{revision}:{ENGINE}"])
    else:
        source = (ROOT / ENGINE).read_bytes()
    args.output.mkdir(parents=True, exist_ok=False)
    results = {}
    with tempfile.TemporaryDirectory(prefix="undertow-eval-") as temporary:
        engine = Path(temporary) / "review_engine.py"
        engine.write_bytes(source)
        for case_id, case in cases.items():
            repo = args.repos / (case["repository"] + ".git")
            results[case_id] = run_case(engine, case, repo, args.output / case_id, env)
            result = results[case_id]
            print(f"{case_id}: {result['status']} (mode: {result['mode'] or 'unavailable'})")

    write(args.output / "run.json", {
        "schema": 1, "manifest_sha256": digest(manifest), "undertow_commit": revision,
        "worker_sha256": hashlib.sha256(source).hexdigest(),
        "platform": sys.platform, "python": sys.version.split()[0],
        "git": subprocess.check_output(["git", "--version"], text=True).strip(),
        "requested_mode": args.mode, "requested_model": args.model, "cases": results,
    })
    return int(any(result["status"] == "unavailable" for result in results.values()))


def ratio(numerator, denominator):
    return {"numerator": numerator, "denominator": denominator,
            "value": numerator / denominator if denominator else None}


def copilot_cost(record):
    charges = []
    for result in record["cases"].values():
        usage = result.get("usage", {})
        cost = usage.get("totalNanoAiu")
        if usage.get("aiCreditsStatus") == "complete" and type(cost) is int and cost >= 0:
            charges.append(cost)
    return {"unit": "USD budget equivalent", "cases_with_metered_usage": len(charges),
            "total": sum(charges) / 100_000_000_000 if charges else None,
            "median_per_metered_case": statistics.median(charges) / 100_000_000_000 if charges else None}


def score(record, manifest, cases, reviews):
    if (record["schema"] != 1 or record["manifest_sha256"] != digest(manifest)
            or cases.keys() != record["cases"].keys() or not reviews.keys() <= cases.keys()):
        raise ValueError("Run, case manifest, or adjudications do not match.")

    correct = incorrect = concrete = unreviewed = supported = detected = 0
    labeled = labeled_detected = offered = examples_correct = 0
    example_inventory_complete = True
    scored = noise = controls = control_noise = unavailable = other_mode = 0
    durations = []
    for case_id, case in cases.items():
        result = record["cases"][case_id]
        findings = result["finding_ids"]
        judgments = reviews.get(case_id, {})
        if not judgments.keys() <= set(findings):
            raise ValueError(f"Adjudications for {case_id} refer to unknown findings.")
        if result["status"] == "unavailable":
            unavailable += 1
            continue
        if record["requested_mode"] != result["mode"]:
            other_mode += 1
            continue

        durations.append(result["seconds"])
        allowed = {change["id"] for change in case["changes"]}
        example_ids = result.get("example_ids")
        if example_ids is None and findings:
            example_inventory_complete = False
        matched = set()
        case_noise = pending = 0
        for finding in findings:
            judgment = judgments.get(finding, {})
            verdict = judgment.get("verdict", "unverified")
            is_example = finding in (example_ids or [])
            if verdict == "correct":
                linked = set(judgment.get("changes", []))
                if not linked <= allowed or type(judgment.get("concrete")) is not bool:
                    raise ValueError(f"Invalid correct-finding adjudication for {case_id}.")
                matched.update(linked)
                correct += 1
                concrete += judgment["concrete"]
                offered += is_example
                examples_correct += is_example and judgment["concrete"]
            elif verdict == "incorrect":
                incorrect += 1
                case_noise += 1
                offered += is_example
            elif verdict == "unverified":
                pending += 1
            else:
                raise ValueError(f"Invalid finding verdict for {case_id}.")
        unreviewed += pending

        if case["classification"] != "unverified" and pending == 0:
            scored += 1
            noise += case_noise
            if case["classification"] == "no_change":
                controls += 1
                control_noise += case_noise
            supported_labels = {change["id"] for change in case["changes"] if change["supported"]}
            supported += len(supported_labels)
            detected += len(supported_labels & matched)
            labeled += len(allowed)
            labeled_detected += len(matched)

    return {
        "schema": 1, "requested_mode": record["requested_mode"], "cases": len(cases),
        "completed_in_requested_mode": len(durations), "unavailable": unavailable,
        "completed_in_other_mode": other_mode, "scored_cases": scored, "unverified_findings": unreviewed,
        "precision": ratio(correct, correct + incorrect),
        "recall_supported": ratio(detected, supported),
        "recall_labeled": ratio(labeled_detected, labeled),
        "incorrect_findings_per_pr": ratio(noise, scored),
        "incorrect_findings_per_control": ratio(control_noise, controls),
        "signal_quality": ratio(concrete, correct + incorrect),
        "concrete_example_accuracy": ratio(examples_correct, offered) if example_inventory_complete else None,
        "actionable_yield": None,
        "llm_cost": copilot_cost(record) if record["requested_mode"] == "copilot" else None,
        "median_seconds": statistics.median(durations) if durations else None,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "evaluation" / "historical.json")
    commands = parser.add_subparsers(dest="command", required=True)
    runner = commands.add_parser("run")
    runner.add_argument("--repos", required=True, type=Path)
    runner.add_argument("--output", required=True, type=Path)
    worker = runner.add_mutually_exclusive_group()
    worker.add_argument("--revision")
    worker.add_argument("--working-tree", action="store_true", help="Snapshot this checkout's trusted worker source")
    runner.add_argument("--mode", choices=["local", "gemini"], default="local")
    runner.add_argument("--model")
    scorer = commands.add_parser("score")
    scorer.add_argument("record", type=Path)
    scorer.add_argument("--adjudications", type=Path)
    args = parser.parse_args()
    try:
        manifest = read(args.manifest)
        cases = cases_from(manifest)
        if args.command == "run":
            return run(args, manifest, cases)
        reviews = read(args.adjudications) if args.adjudications else {}
        print(json.dumps(score(read(args.record), manifest, cases, reviews), indent=2))
    except (OSError, subprocess.CalledProcessError, KeyError, TypeError, ValueError) as error:
        parser.exit(1, f"{error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

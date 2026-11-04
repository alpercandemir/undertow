#!/usr/bin/env python3
"""Compare review modes. Replay scores test authored harness behavior only."""

import argparse
import importlib.util
import json
import hashlib
import math
import time
import statistics
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_prepare():
    spec = importlib.util.spec_from_file_location(
        "prepare_demo", ROOT / "scripts/prepare-demo.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.prepare


def run_review(args, label, info, repository, mode, repeat, destination):
    output = destination / "runs" / label["id"] / mode / str(repeat)
    command = [
        args.java, "-jar", str(ROOT / "target/undertow.jar"), "review",
        "--repo", info["repo"], "--base", info["base"], "--head", info["head"],
        "--mode", mode, "--output", str(output),
    ]
    if args.provider == "replay" and mode != "collect":
        replay = "diff-replay.json" if mode == "diff" else "replay.json"
        command += ["--replay", str(repository / replay)]
    started = time.monotonic()
    try:
        completed = subprocess.run(
            command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, timeout=360,
        )
    except (subprocess.TimeoutExpired, OSError):
        completed = None
    report_path = output / "report.json"
    if not report_path.exists():
        output.mkdir(parents=True, exist_ok=True)
        failure = dict(status="failed", findings=[], elapsed_millis=int((time.monotonic()-started)*1000),
                       model_calls=0, tool_calls=0, input_tokens=0, output_tokens=0,
                       details={"failure_cause": "timeout" if completed is None else "unavailable"},
                       evaluation_failure="Review process did not produce artifacts; this run remains in denominators")
        (output / "evaluation-failure.json").write_text(json.dumps(failure, indent=2) + "\n")
        return failure
    return json.loads(report_path.read_text(encoding="utf-8"))


def score_run(args, label, report, mode, repeat):
    findings = report["findings"]
    expected = label["rule_id"] if label["unsafe"] else None
    matching = [finding for finding in findings if expected in finding["rule_ids"]]
    false_positives = len(findings) - len(matching)
    return {
        "case": label["id"],
        "split": label["split"],
        "mode": mode,
        "repeat": repeat,
        "unsafe": label["unsafe"],
        "status": report["status"],
        "model_identity": report.get("model_identity"),
        "prompt_version": report.get("prompt_version"),
        "prompt_hash": report.get("prompt_hash"),
        "policy_hashes": report.get("policy_hashes"),
        "failure_cause": report.get("details", {}).get("failure_cause", "none"),
        "finding_signature": sorted((tuple(f["rule_ids"]), f["severity"],
                                      f.get("location", {}).get("path"), f.get("location", {}).get("start_line")) for f in findings),
        "high_risk_findings": sum(f["severity"] in {"HIGH", "CRITICAL", "HIGH_CRITICAL"} for f in findings),
        "high_risk_correct": sum(f["severity"] in {"HIGH", "CRITICAL", "HIGH_CRITICAL"} for f in matching),
        "true_positive": int(bool(matching)),
        "false_positive": false_positives,
        "false_negative": int(label["unsafe"] and not matching),
        "severity_disagreements": sum(
            finding["severity"] != label["severity"] for finding in matching
        ),
        "elapsed_ms": report["elapsed_millis"],
        "model_calls": report["model_calls"],
        "tool_calls": report["tool_calls"],
        "input_tokens": report["input_tokens"],
        "output_tokens": report["output_tokens"],
        "estimated_cost_usd": (
            report["input_tokens"] * args.input_rate
            + report["output_tokens"] * args.output_rate
        ) / 1_000_000 if args.input_rate is not None and args.output_rate is not None else None,
    }


def summarize(rows):
    true_positives = sum(row["true_positive"] for row in rows)
    false_positives = sum(row["false_positive"] for row in rows)
    false_negatives = sum(row["false_negative"] for row in rows)
    benign = [row for row in rows if not row["unsafe"]]
    variable_cases = 0
    for case in {row["case"] for row in rows}:
        outcomes = {
            (row["true_positive"], row["false_positive"], row["status"], row.get("severity_disagreements"), str(row.get("finding_signature")))
            for row in rows if row["case"] == case
        }
        variable_cases += int(len(outcomes) > 1)
    return {
        "runs": len(rows),
        "independent_cases": len({row["case"] for row in rows}),
        "completed_runs": sum(row["status"] == "complete" for row in rows),
        "completion_rate": sum(row["status"] == "complete" for row in rows) / len(rows) if rows else None,
        "failure_causes": {cause: sum(row.get("failure_cause") == cause for row in rows)
                           for cause in sorted({row.get("failure_cause", "none") for row in rows})},
        "high_risk_precision": ratio(sum(row.get("high_risk_correct", 0) for row in rows), sum(row.get("high_risk_findings", 0) for row in rows)),
        "p95_latency_ms": sorted(row["elapsed_ms"] for row in rows)[math.ceil(.95*len(rows))-1] if rows else None,
        "precision": (
            true_positives / (true_positives + false_positives)
            if true_positives + false_positives else None
        ),
        "recall": (
            true_positives / (true_positives + false_negatives)
            if true_positives + false_negatives else None
        ),
        "benign_false_positive_rate": (
            sum(row["false_positive"] > 0 for row in benign) / len(benign)
            if benign else None
        ),
        "median_latency_ms": (
            statistics.median([row["elapsed_ms"] for row in rows]) if rows else None
        ),
        "variable_cases": variable_cases,
        "severity_disagreements": sum(row["severity_disagreements"] for row in rows),
        "regression_usefulness": (
            "Human assessment pending; structured proposed-test fields validated by harness."
        ),
        "cost_usd": sum(row["estimated_cost_usd"] for row in rows) if rows and all(row["estimated_cost_usd"] is not None for row in rows) else None,
    }


def ratio(numerator, denominator):
    if not denominator:
        return {"numerator": numerator, "denominator": denominator, "value": None, "ci95": None}
    z = 1.959963984540054
    p = numerator / denominator
    center = (p + z*z/(2*denominator)) / (1 + z*z/denominator)
    radius = z*math.sqrt(p*(1-p)/denominator + z*z/(4*denominator*denominator))/(1+z*z/denominator)
    return {"numerator": numerator, "denominator": denominator, "value": p,
            "ci95": [max(0, center-radius), min(1, center+radius)]}


def freeze_inputs(destination, split):
    paths = list((ROOT / "config").rglob("*.yaml")) + [ROOT / "CODING-SKILL.md"]
    for label_path in sorted((ROOT / "evals/expected").glob("*.json")):
        label = json.loads(label_path.read_text())
        if split == "all" or label["split"] == split:
            paths += [label_path] + list((ROOT / "evals/cases" / label["id"]).iterdir())
    hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in sorted(paths) if path.is_file()}
    manifest = {"schema_version": 1, "split": split, "input_hashes": hashes,
                "labels_exposed_to_model": False, "population": "authored synthetic fixtures",
                "independent_human_labels": False}
    target = destination / "frozen-inputs.json"
    if target.exists() and json.loads(target.read_text()) != manifest:
        raise ValueError("Frozen evaluation inputs changed; create a new evaluation set/destination")
    target.write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def evaluate(args):
    prepare = load_prepare()
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    manifest = freeze_inputs(destination, args.split)
    if getattr(args, "freeze_only", False):
        return {"summaries": {}, "frozen_inputs": manifest}
    results = []
    modes = args.modes.split(",")
    for case in sorted((ROOT / "evals/expected").glob("*.json")):
        label = json.loads(case.read_text(encoding="utf-8"))
        if args.split != "all" and label["split"] != args.split:
            continue
        repository = destination / "snapshots" / label["id"]
        info = prepare(label["id"], repository)
        for mode in modes:
            for repeat in range(args.repeats):
                report = run_review(
                    args, label, info, repository, mode, repeat, destination
                )
                results.append(score_run(args, label, report, mode, repeat))
                (destination / "runs.json").write_text(json.dumps(results, indent=2) + "\n")
        print(label["id"], flush=True)

    summaries = {
        mode: summarize([row for row in results if row["mode"] == mode])
        for mode in modes
    }
    report = {
        "schema_version": 2,
        "provider": args.provider,
        "frozen_inputs_hash": hashlib.sha256((destination / "frozen-inputs.json").read_bytes()).hexdigest(),
        "quality_gate": "unmet: synthetic fixtures do not satisfy independent real-PR/human-label gates",
        "uncertainty_note": "Run-level intervals include repeats; repeats are not independent samples. Report unique cases separately.",
        "measurement": (
            "Authored replay/harness validation; not live-model accuracy"
            if args.provider == "replay"
            else "Live model comparison on synthetic labeled fixtures"
        ),
        "split": args.split,
        "repeats": args.repeats,
        "pricing_per_million": {"input": args.input_rate, "output": args.output_rate},
        "summaries": summaries,
        "runs": results,
    }
    (destination / "evaluation.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", choices=["replay", "live"], default="replay")
    parser.add_argument("--split", choices=["all", "tuning", "heldout"], default="heldout")
    parser.add_argument("--modes", default="collect,diff,tools")
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--freeze-only", action="store_true")
    parser.add_argument("--input-rate", type=float)
    parser.add_argument("--output-rate", type=float)
    args = parser.parse_args()
    if any(rate is not None and rate < 0 for rate in [args.input_rate, args.output_rate]):
        parser.error("Prices must be nonnegative when supplied")
    if (not 1 <= args.repeats <= 10
            or not set(args.modes.split(",")) <= {"collect", "diff", "tools"}):
        parser.error("Invalid modes or repeats")
    print(json.dumps(evaluate(args)["summaries"], indent=2))


if __name__ == "__main__":
    main()

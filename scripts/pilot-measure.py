#!/usr/bin/env python3
"""Measure real-PR pilots from frozen, human-labeled inputs; never submit labels to models."""

import argparse
import importlib.util
import json
import math
from pathlib import Path

spec = importlib.util.spec_from_file_location("evaluation", Path(__file__).with_name("evaluate.py"))
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)
HIGH = {"HIGH", "CRITICAL", "HIGH_CRITICAL"}


def human_decision(entries, adjudications, key):
    current = {}
    for entry in entries:
        if (entry["run_id"], entry["finding_id"]) == key:
            current[entry["reviewer"]] = entry
    if len(current) < 2:
        return None
    votes = {entry["assessment"] for entry in current.values()}
    if len(votes) == 1 and "uncertain" not in votes:
        return next(iter(votes))
    result = adjudications.get("/".join(key))
    if result and result.get("reviewer") and result.get("rationale") and result.get("assessment") in {"correct", "incorrect"}:
        return result["assessment"]
    return None


def measure(manifest, root, feedback):
    if manifest.get("schema_version") != 1 or manifest.get("dataset_kind") != "authorized_historical_prs":
        raise ValueError("A versioned authorized historical-PR manifest is required")
    cases = manifest["cases"]
    identities = [(case["repository"], case["number"]) for case in cases]
    if len(identities) != len(set(identities)):
        raise ValueError("Duplicate historical PRs do not count as independent cases")
    high_correct = high_total = defects = detected = benign_false = benign_total = completed = supported_runs = 0
    suggestions_good = suggestions_assessed = 0
    unresolved = []; latencies = []; run_results = []
    adjudications = manifest.get("adjudications", {})
    for case in cases:
        if case.get("synthetic"):
            raise ValueError("Synthetic cases must be measured separately from real PRs")
        labels = case.get("defects", [])
        independent = all(len(set(label.get("reviewers", []))) >= 2 and label.get("adjudicated") for label in labels)
        if not independent:
            unresolved.append(f"Independent defect labeling pending: {case['repository']}#{case['number']}")
        # The first frozen repeat measures quality. All repeats remain in reliability and variability reporting.
        first = None
        for index, name in enumerate(case.get("reports", [])):
            path = root / name
            report = json.loads(path.read_text()) if path.is_file() else dict(run_id=name, status="failed", findings=[], elapsed_millis=None)
            if index == 0:
                first = report
            if case.get("supported") and case.get("valid_inputs"):
                supported_runs += 1; completed += report["status"] == "complete"
            if report.get("elapsed_millis") is not None:
                latencies.append(report["elapsed_millis"])
            run_results.append(dict(repository=case["repository"], number=case["number"], repeat=index,
                                    status=report["status"], model=report.get("model_identity"),
                                    prompt_hash=report.get("prompt_hash"), policy_hashes=report.get("policy_hashes"),
                                    input_tokens=report.get("input_tokens"), output_tokens=report.get("output_tokens"),
                                    finding_signatures=[(sorted(f["rule_ids"]), f["severity"], f["location"]) for f in report["findings"]]))
        if first is None:
            first = dict(run_id="missing", status="failed", findings=[])
            if case.get("supported") and case.get("valid_inputs"):
                supported_runs += 1
        findings = first["findings"]
        if case.get("benign"):
            benign_total += 1
        false_alarm = False
        for finding in findings:
            key = (first["run_id"], finding["id"])
            verdict = human_decision(feedback, adjudications, key)
            if verdict is None:
                unresolved.append("Independent finding assessment pending: " + "/".join(key))
            if finding["severity"] in HIGH:
                high_total += 1; high_correct += verdict == "correct"
            false_alarm |= verdict == "incorrect" and finding.get("category") != "STYLE"
            votes = {entry["reviewer"]: entry for entry in feedback if (entry["run_id"], entry["finding_id"]) == key}
            if len(votes) >= 2:
                usefulness = {entry.get("regression_usefulness") for entry in votes.values()}
                if len(usefulness) == 1 and "unassessed" not in usefulness and None not in usefulness:
                    suggestions_assessed += len(finding.get("regression_tests", []))
                    if "actionable" in usefulness:
                        suggestions_good += len(finding.get("regression_tests", []))
        benign_false += bool(case.get("benign") and false_alarm)
        for label in labels:
            if case.get("supported") and label.get("severity") in HIGH:
                defects += 1
                # A failed run or absent finding remains a missed defect.
                detected += first["status"] != "failed" and any(label["rule_id"] in f["rule_ids"] and human_decision(feedback, adjudications, (first["run_id"], f["id"])) == "correct" for f in findings)
    metrics = dict(high_risk_precision=evaluation.ratio(high_correct, high_total),
                   supported_defect_recall=evaluation.ratio(detected, defects),
                   benign_false_alarm_rate=evaluation.ratio(benign_false, benign_total),
                   regression_usefulness=evaluation.ratio(suggestions_good, suggestions_assessed),
                   review_completion=evaluation.ratio(completed, supported_runs),
                   p95_latency_ms=sorted(latencies)[math.ceil(.95*len(latencies))-1] if latencies else None)
    enough = len(cases) >= 30 and len({case['repository'] for case in cases}) >= 2 and benign_total >= 10 and defects >= 20
    targets = [("high_risk_precision", .8, True), ("supported_defect_recall", .7, True),
               ("benign_false_alarm_rate", .1, False), ("regression_usefulness", .8, True), ("review_completion", .9, True)]
    quality = enough and not unresolved and all(metrics[key]['value'] is not None and
                (metrics[key]['value'] >= target if minimum else metrics[key]['value'] <= target) for key, target, minimum in targets)
    return dict(schema_version=1, dataset_kind=manifest['dataset_kind'], independent_prs=len(cases),
                repositories=len({case['repository'] for case in cases}), benign_prs=benign_total,
                supported_high_risk_defects=defects, sample_gate_met=enough, quality_targets_met=quality,
                phase2_exit="unmet; operational/publication/setup/cost gates require separate verified evidence",
                metrics=metrics, unresolved_assessments=unresolved, runs=run_results,
                cost="unknown unless explicit current prices and provider usage are supplied",
                limitations="Independent quality scoring uses the first repeat; reliability includes every repeat. Labels and feedback are human inputs, never model context.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--feedback', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    feedback = [json.loads(line) for line in args.feedback.read_text().splitlines()] if args.feedback else []
    result = measure(json.loads(args.manifest.read_text()), args.manifest.parent, feedback)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()

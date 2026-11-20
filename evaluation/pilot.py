#!/usr/bin/env python3
"""Prepare reviewer-blind packets and compare adjudicated historical outcomes."""

import argparse
import json
from pathlib import Path
import re
from secrets import SystemRandom

from run import ROOT, cases_from, digest, finding_ids, read, score, write


PRODUCT = re.compile(r"\b(?:GitHub Copilot(?: CLI)?|Copilot|Undertow|Gemini)\b", re.I)
COMPLETED = {"findings", "no_findings"}


def completed(record, case_id):
    result = record["cases"][case_id]
    return result["status"] in COMPLETED and result["mode"] == record["requested_mode"]


def claims(record_path, case, row):
    expected = row["finding_ids"]
    if not expected:
        return []
    if row["mode"] == "copilot":
        findings = row["findings"]
        if [digest(finding) for finding in findings] != expected:
            raise ValueError(f"Copilot findings do not match the run for {case['id']}.")
        return findings

    report = read(record_path.parent / case["id"] / "report.json")
    if ((report["base"], report["head"]) != (case["base"], case["head"])
            or report["mode"] != row["mode"] or finding_ids(report) != expected):
        raise ValueError(f"Undertow findings do not match the run for {case['id']}.")
    normalized = []
    for finding in report["findings"]:
        example = finding.get("example", {})
        sources = [
            {"side": side, "path": ref["path"], "line": ref["start"], "end": ref["end"]}
            for key in finding["evidence"] for side, ref in report["evidence"][key].items()
        ]
        normalized.append({"claim": finding["title"], "input": example.get("input"),
                           "precondition": example.get("precondition", finding.get("scenario")),
                           "before": finding["example_before"], "after": finding["example_after"],
                           "question": finding.get("review_question"), "sources": sources,
                           "limitations": " ".join(finding.get("analysis_limitations", []))})
    return normalized


def blinded(claim):
    fields = ("claim", "input", "precondition", "before", "after", "question", "limitations")
    result = {field: PRODUCT.sub("[reviewer]", claim[field]) if claim.get(field) else None for field in fields}
    result["sources"] = [{"side": ref["side"], "path": ref["path"], "line": ref["line"],
                          "end": ref.get("end", ref["line"])} for ref in claim["sources"]]
    return result


def make_packet(manifest, cases, record_paths, output):
    records = [(path, read(path)) for path in record_paths]
    for _, record in records:
        score(record, manifest, cases, {})
    packet = {"schema": 1, "cases": {}}
    key = {"schema": 1, "manifest_sha256": digest(manifest), "cases": {}, "findings": {}}
    case_order = list(cases.values())
    rng = SystemRandom()
    rng.shuffle(case_order)
    for index, case in enumerate(case_order, 1):
        case_id = case["id"]
        alias = f"C{index:02d}"
        key["cases"][alias] = case_id
        entries = []
        for run_index, (path, record) in enumerate(records, 1):
            if completed(record, case_id):
                row = record["cases"][case_id]
                entries.extend((claim, run_index, identifier) for claim, identifier in zip(
                    claims(path, case, row), row["finding_ids"], strict=True))
        rng.shuffle(entries)
        findings = {}
        for number, (claim, run_index, finding_id) in enumerate(entries, 1):
            blind_id = f"{alias}-F{number:02d}"
            findings[blind_id] = blinded(claim)
            key["findings"][blind_id] = {"case": case_id, "run": run_index, "finding": finding_id}
        packet["cases"][alias] = {field: case[field] for field in ("source_url", "base", "head")}
        packet["cases"][alias]["findings"] = findings

    key["packet_sha256"] = digest(packet)
    key["runs"] = [{"index": index, "mode": record["requested_mode"], "sha256": digest(record)}
                   for index, (_, record) in enumerate(records, 1)]
    output.mkdir(parents=True, exist_ok=False)
    write(output / "reviewer.json", packet)
    write(output / "coordinator-key.json", key)


def reviewed_outcomes(row, judgments):
    outcomes = set()
    for identifier in row["finding_ids"]:
        judgment = judgments.get(identifier, {})
        if judgment.get("verdict") == "correct":
            outcomes.update(judgment["changes"])
            outcomes.update(f"other/{outcome}" for outcome in judgment.get("additional_outcomes", []))
        elif judgment.get("verdict") != "incorrect":
            return None
    return outcomes


def compare(manifest, cases, left, right, left_reviews, right_reviews, actions):
    score(left, manifest, cases, left_reviews)
    score(right, manifest, cases, right_reviews)
    differences = {}
    paired_completed = paired_scored = 0
    for case_id, case in cases.items():
        if not (completed(left, case_id) and completed(right, case_id)):
            continue
        paired_completed += 1
        left_outcomes = reviewed_outcomes(left["cases"][case_id], left_reviews.get(case_id, {}))
        right_outcomes = reviewed_outcomes(right["cases"][case_id], right_reviews.get(case_id, {}))
        if left_outcomes is None or right_outcomes is None:
            continue
        paired_scored += 1
        labels = {change["id"] for change in case["changes"]}
        left_only, right_only = left_outcomes - right_outcomes, right_outcomes - left_outcomes
        observed = {outcome for outcome, evidence in actions.get(case_id, {}).items() if evidence.strip()}
        differences[case_id] = {"left_only": sorted(left_only), "right_only": sorted(right_only),
                                "left_only_with_observed_action": sorted(left_only & observed),
                                "right_only_with_observed_action": sorted(right_only & observed),
                                "labeled_missed_by_both": sorted(labels - left_outcomes - right_outcomes)}
    return {"left": left["requested_mode"], "right": right["requested_mode"],
            "paired_completed_cases": paired_completed, "paired_adjudicated_cases": paired_scored,
            "observed_actions_supplied": bool(actions),
            "totals": {field: sum(len(row[field]) for row in differences.values())
                       for field in ("left_only", "right_only", "left_only_with_observed_action",
                                     "right_only_with_observed_action", "labeled_missed_by_both")},
            "differences": differences}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "evaluation" / "historical.json")
    commands = parser.add_subparsers(dest="command", required=True)
    packet = commands.add_parser("blind")
    packet.add_argument("--run", type=Path, required=True, action="append")
    packet.add_argument("--output", type=Path, required=True)
    pair = commands.add_parser("compare")
    pair.add_argument("left", type=Path)
    pair.add_argument("right", type=Path)
    pair.add_argument("--left-adjudications", type=Path)
    pair.add_argument("--right-adjudications", type=Path)
    pair.add_argument("--observed-actions", type=Path)
    args = parser.parse_args()
    try:
        manifest = read(args.manifest)
        cases = cases_from(manifest)
        if args.command == "blind":
            make_packet(manifest, cases, args.run, args.output)
        else:
            reviews = [read(path) if path else {} for path in (
                args.left_adjudications, args.right_adjudications, args.observed_actions)]
            print(json.dumps(compare(manifest, cases, read(args.left), read(args.right), *reviews), indent=2))
    except (OSError, KeyError, TypeError, ValueError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    raise SystemExit(main())

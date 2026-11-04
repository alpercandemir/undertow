#!/usr/bin/env python3
"""Append human pilot feedback. Entries are retained; corrections are additional entries."""

import argparse
import datetime
import fcntl
import json
import os
import uuid
from pathlib import Path


def append_feedback(path, report, finding_id, reviewer, assessment, action, rationale,
                    technical="uncertain", importance="uncertain", usefulness="unassessed", fingerprint=None):
    if not any(finding["id"] == finding_id for finding in report["findings"]):
        raise ValueError("Finding does not belong to report")
    if not reviewer.strip() or not rationale.strip():
        raise ValueError("Named reviewer and rationale required")
    if assessment not in {"correct", "incorrect", "uncertain"}:
        raise ValueError("Invalid human assessment")
    entry = dict(schema_version=1, feedback_id=str(uuid.uuid4()), run_id=report["run_id"],
                 finding_id=finding_id, fingerprint=fingerprint, reviewer=reviewer,
                 assessment=assessment, technical_correctness=technical, importance=importance,
                 regression_usefulness=usefulness, action=action, rationale=rationale,
                 recorded_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+", encoding="utf-8") as output:
        fcntl.flock(output, fcntl.LOCK_EX)
        output.seek(0)
        for line in output:
            existing = json.loads(line)
            if existing.get("schema_version") != 1:
                raise ValueError("Unsupported feedback history; preserve it before migrating")
        output.seek(0, 2)
        output.write(json.dumps(entry) + "\n")
        output.flush()
        os.fsync(output.fileno())
    return entry


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    for name in ["finding-id", "reviewer", "rationale"]:
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--assessment", choices=["correct", "incorrect", "uncertain"], required=True)
    parser.add_argument("--action", choices=["fixed", "accepted_risk", "false_positive", "investigate", "none"], required=True)
    parser.add_argument("--technical", choices=["correct", "incorrect", "uncertain"], default="uncertain")
    parser.add_argument("--importance", choices=["important", "minor", "uncertain"], default="uncertain")
    parser.add_argument("--usefulness", choices=["actionable", "not_useful", "unassessed"], default="unassessed")
    args = parser.parse_args()
    report = json.loads((args.report_dir / "report.json").read_text())
    publication = args.report_dir / "publication.json"
    fingerprint = json.loads(publication.read_text()).get("finding_fingerprints", {}).get(args.finding_id) if publication.exists() else None
    entry = append_feedback(args.output, report, args.finding_id, args.reviewer, args.assessment,
                            args.action, args.rationale, args.technical, args.importance, args.usefulness, fingerprint)
    print("Feedback recorded: " + entry["feedback_id"])


if __name__ == "__main__":
    main()

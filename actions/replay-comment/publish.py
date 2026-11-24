"""Render replay JSON from this workflow run and update a dedicated PR comment."""

import json
import os
from pathlib import Path
import re
import sys

actions = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(actions / "change-review"), str(actions / "behavior-replay")]
import github_review as api
from discover import fingerprint, invocation


MARKER = "<!-- undertow-test-replay -->"
EVIDENCE_ID = re.compile(r"[0-9a-f]{64}")


def cell(value):
    if not isinstance(value, str) or len(value) > 512:
        raise ValueError("Invalid replay text.")
    # Character references keep report text inert inside a GitHub Markdown table.
    return "".join(c if c.isascii() and (c.isalnum() or c in " .$") else f"&#{ord(c)};"
                   for c in " ".join(value.split()))


def comparison(report, base, head):
    before, after = report["base"], report["head"]
    if report["schema_version"] != 1 or (before["sha"], after["sha"]) != (base, head):
        raise ValueError("The artifact does not describe the current PR comparison.")
    outcomes = {"passed": "Passed", "assertion_failed": "Assertion failed", "unavailable": "Unavailable"}
    lines = [f"<code>{cell(report['test'])}</code>", "", "| Test result | Base | PR head |",
             "| --- | --- | --- |",
             f"| Assertion | {outcomes[before['status']]} | {outcomes[after['status']]} |"]
    left, right = before.get("observations", {}), after.get("observations", {})
    if not isinstance(left, dict) or not isinstance(right, dict) or max(len(left), len(right)) > 12:
        raise ValueError("Invalid replay observations.")
    for key in dict.fromkeys([*left, *right]):
        lines.append(f"| {cell(key)} | {cell(left.get(key, 'Unrecorded'))} | {cell(right.get(key, 'Unrecorded'))} |")
    return "\n".join(lines)


def value_cell(value):
    return cell(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True))


def outcome_cell(result):
    if "raised" in result:
        return "Raised " + cell(result["raised"])
    return value_cell(result["returned"])


def behavior(finding):
    before, after = finding["base"], finding["head"]
    conclusion = "Behavior difference observed"
    if "raised" in after:
        conclusion = "New exception on PR head for this input"
    elif "raised" in before:
        conclusion = "PR head now returns a value for this input"
    lines = [f"**{conclusion}.** <code>{cell(finding['target'])}</code>",
             f"<code>{cell(invocation(finding))}</code>", "",
             "| Observed | Base | PR head |", "| --- | --- | --- |"]
    old, new = before.get("returned"), after.get("returned")
    if isinstance(old, dict) and isinstance(new, dict) and old.keys() == new.keys():
        for key in sorted(old):
            left, right = value_cell(old[key]), value_cell(new[key])
            if json.dumps(old[key], sort_keys=True) != json.dumps(new[key], sort_keys=True):
                lines.append(f"| {cell(key)} | {left} | {right} |")
    else:
        lines.append(f"| Result | {outcome_cell(before)} | {outcome_cell(after)} |")
    return "\n".join(lines)


def sections_for(report, base, head, covered, publish_test_replay):
    if report["schema_version"] != 1 or (report["base"]["sha"], report["head"]["sha"]) != (base, head):
        raise ValueError("The artifact does not describe the current PR comparison.")
    if report.get("kind") == "behavior":
        if report["status"] != "observed_difference":
            return []
        findings = report["findings"]
        if not isinstance(findings, list) or not 1 <= len(findings) <= 6:
            raise ValueError("Invalid behavior findings.")
        sections = []
        for finding in findings:
            key = fingerprint(finding)
            if key not in covered:
                sections.append(behavior(finding))
                covered.add(key)
        return sections
    if publish_test_replay:
        statuses = report["base"]["status"], report["head"]["status"]
        if statuses == ("assertion_failed", "passed"):
            return ["**New or changed expectation met on PR head.**\n\n" + comparison(report, base, head)]
        if statuses == ("passed", "assertion_failed"):
            return ["**Possible regression. The selected assertion failed on PR head.**\n\n"
                    + comparison(report, base, head)]
    return []


def main():
    base, head, number = api.event_comparison()
    repo = os.environ["GITHUB_REPOSITORY"]
    if not number or os.environ["GITHUB_EVENT_NAME"] != "pull_request" or not api.REPOSITORY.fullmatch(repo):
        raise ValueError("A pull_request event is required.")
    token = os.environ["UNDERTOW_GITHUB_TOKEN"]
    merge_base = api.github("GET", f"/repos/{repo}/compare/{base}...{head}?per_page=1", token)["merge_base_commit"]["sha"]
    pull = api.github("GET", f"/repos/{repo}/pulls/{number}", token)
    if pull["head"]["repo"]["full_name"].casefold() != repo.casefold():
        raise ValueError("Publishing requires a same-repository pull request.")
    covered = set(os.environ.get("COVERED_EVIDENCE", "").split())
    if any(not EVIDENCE_ID.fullmatch(value) for value in covered):
        raise ValueError("Covered evidence must contain only behavior evidence IDs.")
    covered.update(re.findall(r"<!-- undertow-covered: ([0-9a-f]{64}) -->", pull.get("body") or ""))
    publish_test_replay = os.environ.get("PUBLISH_TEST_REPLAY") == "true"
    root = Path(os.environ["REPORT_DIR"]).resolve()
    reports = sorted(root.glob("*/report.json"))
    if not 1 <= len(reports) <= 12:
        raise ValueError("Select between one and twelve replay artifacts.")
    sections = []
    for path in reports:
        if path.resolve() != path or path.stat().st_size > 131072:
            raise ValueError("Invalid replay artifact.")
        sections.extend(sections_for(json.loads(path.read_text(encoding="utf-8")), merge_base, head,
                                     covered, publish_test_replay))
    run_url = f"{api.github_url('GITHUB_SERVER_URL')}/{repo}/actions/runs/{int(os.environ['GITHUB_RUN_ID'])}"
    body = "\n\n".join(["## Undertow behavior replay", *sections,
                        f"Base `{merge_base[:7]}` · PR head `{head[:7]}` · [Run summary and evidence]({run_url})"]) if sections else ""
    if len(body.encode()) > 60000:
        raise ValueError("Replay comment exceeds the supported size.")
    api.update_comment(repo, number, base, head, token, body, marker=MARKER)


if __name__ == "__main__":
    main()

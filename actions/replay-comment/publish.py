"""Render replay JSON from this workflow run and update a dedicated PR comment."""

import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "change-review"))
import github_review as api


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


def main():
    base, head, number = api.event_comparison()
    repo = os.environ["GITHUB_REPOSITORY"]
    if not number or os.environ["GITHUB_EVENT_NAME"] != "pull_request" or not api.REPOSITORY.fullmatch(repo):
        raise ValueError("A pull_request event is required.")
    token = os.environ["UNDERTOW_GITHUB_TOKEN"]
    merge_base = api.github("GET", f"/repos/{repo}/compare/{base}...{head}?per_page=1", token)["merge_base_commit"]["sha"]
    root = Path(os.environ["REPORT_DIR"]).resolve()
    reports = sorted(root.glob("*/report.json"))
    if not 1 <= len(reports) <= 12:
        raise ValueError("Select between one and twelve replay artifacts.")
    sections = []
    for path in reports:
        if path.resolve() != path or path.stat().st_size > 131072:
            raise ValueError("Invalid replay artifact.")
        sections.append(comparison(json.loads(path.read_text(encoding="utf-8")), merge_base, head))
    run_url = f"{api.github_url('GITHUB_SERVER_URL')}/{repo}/actions/runs/{int(os.environ['GITHUB_RUN_ID'])}"
    body = "\n\n".join(["## Undertow test replay", *sections,
                        f"Base `{merge_base[:7]}` · PR head `{head[:7]}` · [Run summary and logs]({run_url})"])
    if len(body.encode()) > 60000:
        raise ValueError("Replay comment exceeds the supported size.")
    api.update_comment(repo, number, base, head, token, body, marker="<!-- undertow-test-replay -->")


if __name__ == "__main__":
    main()

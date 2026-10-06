#!/usr/bin/env python3
"""Publisher verifies a same-run allowlisted artifact from the trusted review job."""

import hashlib
import json
import os
from pathlib import Path

EXPECTED_FILES = {
    "undertow.jar", "report.json", "report.md", "evidence.json",
    "trace.jsonl", "pr.json", "provenance.json", "publication.json",
}
MAX_ARTIFACT_BYTES = 100_000_000


def verify(root, environment):
    if {path.name for path in root.iterdir()} != EXPECTED_FILES:
        raise ValueError("Unexpected artifact files")
    for path in root.iterdir():
        if (path.is_symlink() or not path.is_file()
                or path.stat().st_size > MAX_ARTIFACT_BYTES):
            raise ValueError("Invalid artifact type/size")

    manifest = json.loads((root / "provenance.json").read_text(encoding="utf-8"))
    for key, variable in [
        ("repository", "GITHUB_REPOSITORY"),
        ("run_id", "GITHUB_RUN_ID"),
        ("harness_sha", "GITHUB_SHA"),
    ]:
        if manifest[key] != environment[variable]:
            raise ValueError("Artifact workflow provenance mismatch")

    hashed_files = EXPECTED_FILES - {"provenance.json"}
    for name, digest in manifest["hashes"].items():
        if name not in hashed_files:
            raise ValueError("Artifact content hash mismatch")
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
            raise ValueError("Artifact content hash mismatch")
    if set(manifest["hashes"]) != hashed_files:
        raise ValueError("Incomplete artifact manifest")

    pull_request = json.loads((root / "pr.json").read_text(encoding="utf-8"))
    if (pull_request["repository"] != environment["GITHUB_REPOSITORY"]
            or pull_request["number"] != int(environment["PR_NUMBER"])):
        raise ValueError("Artifact PR mismatch")
    report = json.loads((root / "report.json").read_text(encoding="utf-8"))
    if (report["base_sha"] != pull_request.get("analysis_diff_base", pull_request["base_sha"])
            or report["head_sha"] != pull_request["head_sha"]):
        raise ValueError("Report commit mismatch")
    if report.get("schema_version") == 2:
        if (report["details"]["policy_commit"] != pull_request.get("policy_commit", pull_request["base_sha"])
                or report["details"]["target_head"] != pull_request.get("target_head", pull_request["base_sha"])):
            raise ValueError("Report policy/target mismatch")


if __name__ == "__main__":
    verify(Path("review-artifact"), os.environ)
